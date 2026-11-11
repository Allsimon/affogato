package org.affogato.builtins;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.affogato.NixContext;
import org.affogato.runtime.*;

public final class Builtins {
    /** Builtins bound without the {@code __} prefix; the others are bound only with it. */
    private static final Set<String> GLOBAL = Set.of(
            "abort",
            "baseNameOf",
            "break",
            "derivation",
            "derivationStrict",
            "dirOf",
            "false",
            "fetchGit",
            "fetchMercurial",
            "fetchTarball",
            "fetchTree",
            "fromTOML",
            "import",
            "isNull",
            "map",
            "null",
            "placeholder",
            "removeAttrs",
            "scopedImport",
            "throw",
            "toString",
            "true");

    public static final String NIX_VERSION = "2.34.9";

    final TreeMap<String, Object> values = new TreeMap<>();

    /** Context-free, so translated files can be shared across contexts; created once so that each has one identity. */
    private static final TreeMap<String, Object> PRIMOPS = primOps();

    private Builtins() {}

    void add(String name, int arity, PrimOp.Impl impl) {
        values.put(name, new PrimOp(name, arity, impl));
    }

    void constant(String name, Object value) {
        values.put(name, value);
    }

    private static TreeMap<String, Object> primOps() {
        Builtins b = new Builtins();
        b.core();
        b.types();
        b.arithmetic();
        Lists.register(b);
        AttrSets.register(b);
        Strings.register(b);
        Json.register(b);
        Xml.register(b);
        Toml.register(b);
        Files.register(b);
        Flakes.register(b);
        Context.register(b);
        Derivations.register(b);
        return b.values;
    }

    public static void install(NixContext ctx) {
        Builtins b = new Builtins();
        b.values.putAll(PRIMOPS);
        b.constants(ctx);
        TreeMap<String, Object> withSelf = new TreeMap<>(b.values);
        withSelf.put("builtins", null);
        Object[] vals = new Object[withSelf.size()];
        String[] names = withSelf.keySet().toArray(new String[0]);
        Name[] keys = new Name[names.length];
        NixAttrs self = new NixAttrs(keys, vals);
        for (int i = 0; i < names.length; i++) {
            keys[i] = ctx.name(names[i]);
            vals[i] = names[i].equals("builtins") ? self : b.values.get(names[i]);
        }

        ctx.globals.put("builtins", self);
        for (var e : b.values.entrySet()) {
            String name = e.getKey();
            ctx.globals.put(GLOBAL.contains(name) ? name : "__" + name, e.getValue());
        }
    }

    static NixException catchable(String message) {
        return new NixException(message, null, true);
    }

    private static final PrintStream STDERR = new PrintStream(System.err, true, StandardCharsets.ISO_8859_1);

    static void trace(String message) {
        STDERR.println(message);
    }

    private void constants(NixContext ctx) {
        constant("true", true);
        constant("false", false);
        constant("null", NixNull.INSTANCE);
        constant("currentSystem", new NixString(ctx.system));
        constant("currentTime", System.currentTimeMillis() / 1000);
        constant("langVersion", 6L);
        constant("nixVersion", new NixString(NIX_VERSION));
        constant("storeDir", new NixString(ctx.storeDir));
        Object[] np = new Object[ctx.nixPath.size()];
        for (int i = 0; i < np.length; i++) {
            TreeMap<String, Object> m = new TreeMap<>();
            m.put("prefix", new NixString(ctx.nixPath.get(i)[0]));
            m.put("path", new NixString(ctx.nixPath.get(i)[1]));
            np[i] = NixAttrs.of(m);
        }
        constant("nixPath", new NixList(np));
        constant("derivation", new Thunk(() -> ctx.importFile(Derivations.COREPKGS + "derivation.nix")));
    }

    private void core() {

        add("abort", 1, a -> {
            String msg = Coerce.toNixString(a[0], false, false).value;
            throw new NixException("evaluation aborted with the following error message: '" + msg + "'");
        });
        add("throw", 1, a -> {
            throw catchable(Coerce.toNixString(a[0], false, false).value);
        });
        add("import", 1, a -> NixContext.get().importFile(Files.realPath(a[0])));
        add("scopedImport", 2, a -> NixContext.get().scopedImport(Types.attrs(a[0]), Files.realPath(a[1])));
        add("seq", 2, a -> {
            Thunk.force(a[0]);
            return a[1];
        });
        add("deepSeq", 2, a -> {
            Printer.forceDeep(a[0]);
            return a[1];
        });
        add("tryEval", 1, a -> {
            TreeMap<String, Object> m = new TreeMap<>();
            try {
                m.put("value", Thunk.force(a[0]));
                m.put("success", true);
            } catch (NixException e) {
                if (!e.catchable) throw e;
                m.put("value", false);
                m.put("success", false);
            }
            return NixAttrs.of(m);
        });
        add("trace", 2, a -> {
            Object v = Thunk.force(a[0]);
            trace("trace: " + (v instanceof NixString s ? s.value : Printer.print(v)));
            return a[1];
        });
        add("warn", 2, a -> {
            trace("evaluation warning: " + Types.string(a[0]).value);
            return a[1];
        });
        add("traceVerbose", 2, a -> a[1]);
        add("addErrorContext", 2, a -> a[1]);
        add("break", 1, a -> a[0]);
        add("unsafeGetAttrPos", 2, a -> {
            String name = Types.string(a[0]).value;
            var pos = Types.attrs(a[1]).position(name);
            if (pos == null) return NixNull.INSTANCE;
            TreeMap<String, Object> m = new TreeMap<>();
            m.put("file", new NixString(pos.getSource().getName()));
            m.put("line", (long) pos.getStartLine());
            m.put("column", (long) pos.getStartColumn());
            return NixAttrs.of(m);
        });
        for (String f : List.of("fetchurl", "fetchTarball", "fetchGit", "fetchMercurial")) {
            add(f, 1, a -> {
                throw new NixException("'builtins." + f + "' is not supported by affogato yet");
            });
        }
    }

    private void types() {
        add("typeOf", 1, a -> new NixString(Types.typeOf(a[0])));
        add("isNull", 1, a -> Thunk.force(a[0]) == NixNull.INSTANCE);
        add("isFunction", 1, a -> Types.isFunction(Thunk.force(a[0])));
        add("isInt", 1, a -> Thunk.force(a[0]) instanceof Long);
        add("isFloat", 1, a -> Thunk.force(a[0]) instanceof Double);
        add("isString", 1, a -> Thunk.force(a[0]) instanceof NixString);
        add("isBool", 1, a -> Thunk.force(a[0]) instanceof Boolean);
        add("isPath", 1, a -> Thunk.force(a[0]) instanceof NixPath);
        add("isAttrs", 1, a -> Thunk.force(a[0]) instanceof NixAttrs);
        add("isList", 1, a -> Thunk.force(a[0]) instanceof NixList);
    }

    private static Object num(Object v) {
        v = Thunk.force(v);
        if (v instanceof Long || v instanceof Double) return v;
        throw new NixException("expected an integer but found " + Types.describe(v));
    }

    private void arithmetic() {
        add("add", 2, a -> Ops.add(num(a[0]), num(a[1])));
        add("sub", 2, a -> Ops.sub(num(a[0]), num(a[1])));
        add("mul", 2, a -> Ops.mul(num(a[0]), num(a[1])));
        add("div", 2, a -> Ops.div(num(a[0]), num(a[1])));
        add("lessThan", 2, a -> Ops.lessThan(a[0], a[1]));
        add("bitAnd", 2, a -> Types.integer(a[0]) & Types.integer(a[1]));
        add("bitOr", 2, a -> Types.integer(a[0]) | Types.integer(a[1]));
        add("bitXor", 2, a -> Types.integer(a[0]) ^ Types.integer(a[1]));
        add("ceil", 1, a -> (long) Math.ceil(Types.number(a[0])));
        add("floor", 1, a -> (long) Math.floor(Types.number(a[0])));
    }
}
