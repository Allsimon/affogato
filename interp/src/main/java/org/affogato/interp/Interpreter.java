package org.affogato.interp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.affogato.interp.builtins.Json;
import org.affogato.interp.runtime.Apply;
import org.affogato.interp.runtime.NixAttrs;
import org.affogato.interp.runtime.NixException;
import org.affogato.interp.runtime.NixLambda;
import org.affogato.interp.runtime.NixList;
import org.affogato.interp.runtime.NixString;
import org.affogato.interp.runtime.Printer;
import org.affogato.interp.runtime.Thunk;
import org.affogato.interp.runtime.Types;

/**
 * Evaluates a top-level source the way {@code nix-instantiate} does: auto-call, select the {@code -A} path, render.
 * Sources named by an absolute path are files; any other name means an expression relative to the working directory.
 * A port of org.affogato.EvalRootNode without Truffle.
 */
public final class Interpreter {
    /** Same meaning as the language's {@code nix.*} options. */
    public record Options(String nixPath, String system, String storeDir, String output, String attr, String args) {}

    private static final long STACK_SIZE = 1L << 30;

    private Interpreter() {}

    /**
     * Runs on a thread of its own, with a stack deep enough for the tree walker, and stops it after {@code
     * timeoutMillis} (0 for none).
     */
    public static String evaluate(Options options, String name, String content, long timeoutMillis)
            throws InterruptedException {
        NixContext ctx = new NixContext(options.storeDir(), options.system(), options.nixPath());
        String[] result = new String[1];
        RuntimeException[] error = new RuntimeException[1];
        Error[] fatal = new Error[1];
        Thread t = new Thread(
                null,
                () -> {
                    try {
                        result[0] = run(ctx, options, name, content);
                    } catch (RuntimeException e) {
                        error[0] = e;
                    } catch (StackOverflowError e) {
                        error[0] = new NixException("stack overflow");
                    } catch (Error e) {
                        fatal[0] = e;
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
            throw new NixException("evaluation timed out after " + timeoutMillis + " ms");
        }
        if (fatal[0] != null) throw fatal[0];
        if (error[0] != null) throw error[0];
        return result[0];
    }

    private static String run(NixContext ctx, Options options, String name, String content) {
        ctx.enter();
        try {
            return render(ctx, options, name, content);
        } finally {
            NixContext.leave();
        }
    }

    private static String render(NixContext ctx, Options options, String name, String content) {
        Object v;
        if (name.startsWith("/")) {
            v = Thunk.force(ctx.parse(content, name, NixContext.parentDir(name), new String[0])
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
            case "strict" -> {
                Printer.forceDeep(v);
                yield Printer.print(v);
            }
            case "json" -> Json.toJson(v, true).value;
            case "raw" -> Types.string(org.affogato.interp.runtime.Coerce.toNixString(v, true, false)).value;
            case "drv-path" -> {
                NixAttrs drv = Types.attrs(v);
                Object p = drv.get("drvPath");
                if (p == null) throw new NixException("expression does not evaluate to a derivation");
                Object out = drv.get("outputName");
                String suffix = out instanceof NixString o && !o.value.equals("out") ? "!" + o.value : "";
                yield Types.string(p).value + suffix;
            }
            default -> Printer.print(v);
        };
    }

    private static Map<String, Object> autoArgs(NixContext ctx, String spec) {
        Map<String, Object> args = new TreeMap<>();
        if (spec.isEmpty()) return args;
        for (String entry : spec.split("\u0001")) {
            String[] f = entry.split("\u0002", 3);
            if (f[0].equals("str")) {
                args.put(f[1], new NixString(f[2]));
            } else {
                args.put(
                        f[1],
                        Thunk.force(
                                ctx.parse(f[2], NixContext.STRING_SOURCE, System.getProperty("user.dir"), new String[0])
                                        .call(null, null)));
            }
        }
        return args;
    }

    /** Port of {@code EvalState::autoCallFunction}. */
    private static Object autoCall(Object v, Map<String, Object> args) {
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
