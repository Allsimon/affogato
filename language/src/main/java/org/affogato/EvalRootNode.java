package org.affogato;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.affogato.builtins.Json;
import org.affogato.runtime.Apply;
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
 * Sources named by an absolute path are files; any other name means an expression relative to the working directory.
 */
final class EvalRootNode extends RootNode {
    private final Source source;

    EvalRootNode(NixLanguage language, Source source) {
        super(language);
        this.source = source;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return run();
    }

    @TruffleBoundary
    private String run() {
        NixContext ctx = NixContext.get();
        var options = ctx.env.getOptions();
        String name = source.getName();
        String content = source.getCharacters().toString();
        Object v;
        if (name.startsWith("/")) {
            v = Thunk.force(ctx.parse(content, name, NixContext.parentDir(name), new String[0])
                    .call((Object) null));
        } else {
            v = Thunk.force(ctx.parse(content, NixContext.STRING_SOURCE, System.getProperty("user.dir"), new String[0])
                    .call((Object) null));
        }
        Map<String, Object> args = autoArgs(ctx, options.get(NixLanguage.Args));
        v = autoCall(v, args);
        for (String key : attrPath(options.get(NixLanguage.Attr))) {
            if (v instanceof NixList l && key.chars().allMatch(Character::isDigit) && !key.isEmpty()) {
                int i = Integer.parseInt(key);
                if (i >= l.size()) throw new NixException("list index " + i + " in selection path is out of range");
                v = l.get(i);
            } else {
                Object r = Types.attrs(v).get(key);
                if (r == null)
                    throw new NixException("attribute '" + key + "' in selection path '" + options.get(NixLanguage.Attr)
                            + "' not found");
                v = r;
            }
            v = autoCall(v, args);
        }
        return switch (options.get(NixLanguage.Output)) {
            case "strict" -> {
                Printer.forceDeep(v);
                yield Printer.print(v);
            }
            case "json" -> Json.toJson(v, true).value;
            case "raw" -> Types.string(org.affogato.runtime.Coerce.toNixString(v, true, false)).value;
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
                                        .call((Object) null)));
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
