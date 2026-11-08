package org.affogato;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.affogato.builtins.Json;
import org.affogato.nodes.NixNode;
import org.affogato.runtime.Apply;
import org.affogato.runtime.Coerce;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.NixLambda;
import org.affogato.runtime.NixList;
import org.affogato.runtime.NixString;
import org.affogato.runtime.Printer;
import org.affogato.runtime.Thunk;
import org.affogato.runtime.Types;

/**
 * Evaluates a top-level source the way {@code nix-instantiate} does: auto-call, select the {@code -A} path, render.
 */
public final class Interpreter {
    public enum Output {
        EVAL,
        STRICT,
        JSON,
        RAW,
        DRV_PATH
    }

    /** An argument for auto-calling, as {@code --arg} with an expression or {@code --argstr} with a string. */
    public record Arg(String name, String value, boolean string) {}

    /**
     * {@code nixPath} is as {@code NIX_PATH}, and empty values take the defaults: {@code NIX_PATH}, the host's system,
     * {@code NIX_STORE_DIR} or {@code /nix/store}.
     */
    public record Options(String nixPath, String system, String storeDir, Output output, String attr, List<Arg> args) {}

    static final long STACK_SIZE = 1L << 30;

    private Interpreter() {}

    /**
     * Evaluates {@code content}, read from {@code file}, or an expression relative to the working directory when
     * {@code file} is null. It runs on a thread with a stack deep enough for the tree walker, stopped at its next
     * function call once {@code timeoutMillis} have passed (0 for no limit).
     */
    public static String evaluate(Options options, String file, String content, long timeoutMillis)
            throws EvalException, InterruptedException {
        NixContext ctx = new NixContext(options.storeDir(), options.system(), options.nixPath());
        Object[] outcome = new Object[1];
        Thread t = new Thread(
                null,
                () -> {
                    try {
                        outcome[0] = run(ctx, options, file, content);
                    } catch (Throwable e) {
                        outcome[0] = e;
                    }
                },
                "nix-eval",
                STACK_SIZE);
        t.setDaemon(true);
        t.start();
        t.join(timeoutMillis);
        if (t.isAlive()) {
            ctx.cancelled = true;
            t.join();
            throw new EvalException("evaluation timed out after " + timeoutMillis + " ms", null);
        }
        return switch (outcome[0]) {
            case String s -> s;
            case NixException e -> throw new EvalException(e.getMessage(), location(e.location));
            case StackOverflowError e -> throw new EvalException("stack overflow", null);
            default -> throw new IllegalStateException("internal error", (Throwable) outcome[0]);
        };
    }

    private static String location(NixNode node) {
        if (node == null || node.getSourceSection() == null) return null;
        var s = node.getSourceSection();
        return s.getSource().getName() + ":" + s.getStartLine() + ":" + s.getStartColumn();
    }

    private static String run(NixContext ctx, Options options, String file, String content) {
        ctx.enter();
        try {
            return render(ctx, options, file, content);
        } finally {
            NixContext.leave();
        }
    }

    private static String render(NixContext ctx, Options options, String file, String content) {
        Object v;
        if (file != null) {
            v = Thunk.force(ctx.parse(content, file, NixContext.parentDir(file), new String[0])
                    .call(null, null));
        } else {
            v = Thunk.force(ctx.parse(content, NixContext.STRING_SOURCE, System.getProperty("user.dir"), new String[0])
                    .call(null, null));
        }
        Map<String, Object> args = autoArgs(ctx, options.args());
        v = autoCall(v, args);
        for (String key : attrPath(options.attr())) {
            if (v instanceof NixList l && key.chars().allMatch(Character::isDigit) && !key.isEmpty()) {
                int i = Integer.parseInt(key);
                if (i >= l.size()) throw new NixException("list index " + i + " in selection path is out of range");
                v = l.get(i);
            } else {
                Object r = Types.attrs(v).get(key);
                if (r == null)
                    throw new NixException(
                            "attribute '" + key + "' in selection path '" + options.attr() + "' not found");
                v = r;
            }
            v = autoCall(v, args);
        }
        return switch (options.output()) {
            case STRICT -> {
                Printer.forceDeep(v);
                yield Printer.print(v);
            }
            case JSON -> Json.toJson(v, true).value;
            case RAW -> Types.string(Coerce.toNixString(v, true, false)).value;
            case DRV_PATH -> {
                NixAttrs drv = Types.attrs(v);
                Object p = drv.get("drvPath");
                if (p == null) throw new NixException("expression does not evaluate to a derivation");
                Object out = drv.get("outputName");
                String suffix = out instanceof NixString o && !o.value.equals("out") ? "!" + o.value : "";
                yield Types.string(p).value + suffix;
            }
            case EVAL -> Printer.print(v);
        };
    }

    private static Map<String, Object> autoArgs(NixContext ctx, List<Arg> spec) {
        Map<String, Object> args = new TreeMap<>();
        for (Arg a : spec) {
            args.put(
                    a.name(),
                    a.string()
                            ? new NixString(a.value())
                            : Thunk.force(ctx.parse(
                                            a.value(),
                                            NixContext.STRING_SOURCE,
                                            System.getProperty("user.dir"),
                                            new String[0])
                                    .call(null, null)));
        }
        return args;
    }

    /** Port of {@code EvalState::autoCallFunction}. */
    static Object autoCall(Object v, Map<String, Object> args) {
        v = Thunk.force(v);
        if (v instanceof NixAttrs a && a.has("__functor")) {
            Object f = Apply.apply(a.get("__functor"), a);
            if (f instanceof NixLambda) return autoCall(f, args);
            return v;
        }
        if (!(v instanceof NixLambda l) || l.info.formals() == null) return v;
        TreeMap<String, Object> m = new TreeMap<>();
        for (int i = 0; i < l.info.formals().length; i++) {
            String f = l.info.formals()[i];
            if (args.containsKey(f)) {
                m.put(f, args.get(f));
            } else if (!l.info.hasDefault()[i]) {
                throw new NixException("cannot evaluate a function that has an argument without a value ('" + f + "')");
            }
        }
        return Apply.apply(v, NixAttrs.of(m));
    }

    /** Splits an attribute path on dots, honouring double-quoted segments. */
    static List<String> attrPath(String s) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '.' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quoted) throw new NixException("missing closing quote in selection path '" + s + "'");
        out.add(cur.toString());
        return out;
    }
}
