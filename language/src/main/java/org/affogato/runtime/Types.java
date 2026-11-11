package org.affogato.runtime;

public final class Types {
    private Types() {}

    /** The name returned by {@code builtins.typeOf}. */
    public static String typeOf(Object v) {
        v = Thunk.force(v);
        if (v instanceof Long) return "int";
        if (v instanceof Double) return "float";
        if (v instanceof Boolean) return "bool";
        if (v instanceof NixString) return "string";
        if (v instanceof NixPath) return "path";
        if (v == NixNull.INSTANCE) return "null";
        if (v instanceof NixAttrs) return "set";
        if (v instanceof NixList) return "list";
        if (v instanceof NixLambda || v instanceof PrimOp || v instanceof PrimOp.Partial) return "lambda";
        throw new IllegalStateException("not a Nix value: " + v);
    }

    /** Phrases used in Nix's "value is ... while a ... was expected" errors. */
    public static String describe(Object v) {
        v = Thunk.force(v);
        if (v instanceof Long) return "an integer";
        if (v instanceof Double) return "a float";
        if (v instanceof Boolean) return "a Boolean";
        if (v instanceof NixString) return "a string";
        if (v instanceof NixPath) return "a path";
        if (v == NixNull.INSTANCE) return "null";
        if (v instanceof NixAttrs) return "a set";
        if (v instanceof NixList) return "a list";
        if (v instanceof NixLambda) return "a function";
        if (v instanceof PrimOp) return "a built-in function";
        if (v instanceof PrimOp.Partial) return "a partially applied built-in function";
        return v.toString();
    }

    private static NixException expected(String what, Object v) {
        return new NixException("expected " + what + " but found " + describe(v));
    }

    public static NixAttrs attrs(Object v) {
        v = Thunk.force(v);
        if (v instanceof NixAttrs a) return a;
        throw expected("a set", v);
    }

    public static NixList list(Object v) {
        v = Thunk.force(v);
        if (v instanceof NixList l) return l;
        throw expected("a list", v);
    }

    public static long integer(Object v) {
        v = Thunk.force(v);
        if (v instanceof Long l) return l;
        throw expected("an integer", v);
    }

    public static boolean bool(Object v) {
        v = Thunk.force(v);
        if (v instanceof Boolean b) return b;
        throw expected("a Boolean", v);
    }

    /** Nix's {@code forceStringNoCtx}. */
    public static String stringNoContext(Object v) {
        NixString s = string(v);
        if (s.context != null) {
            String c = s.context[0];
            int sep = c.indexOf('!', 1);
            String path = c.startsWith("=")
                    ? c.substring(1) + " (deep)"
                    : c.startsWith("!") ? c.substring(sep + 1) + "^" + c.substring(1, sep) : c;
            throw new NixException(
                    "the string '" + s.value + "' is not allowed to refer to a store path (such as '" + path + "')");
        }
        return s.value;
    }

    public static NixString string(Object v) {
        v = Thunk.force(v);
        if (v instanceof NixString s) return s;
        throw expected("a string", v);
    }

    /** Nix's {@code forceFunction}: a function, or a set with {@code __functor}. */
    public static Object callable(Object v) {
        v = Thunk.force(v);
        if (v instanceof NixAttrs a && a.has("__functor")) return v;
        return function(v);
    }

    public static Object function(Object v) {
        v = Thunk.force(v);
        if (v instanceof NixLambda || v instanceof PrimOp || v instanceof PrimOp.Partial) return v;
        throw expected("a function", v);
    }

    public static boolean isFunction(Object v) {
        return v instanceof NixLambda || v instanceof PrimOp || v instanceof PrimOp.Partial;
    }

    public static double number(Object v) {
        v = Thunk.force(v);
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d;
        throw expected("a number", v);
    }
}
