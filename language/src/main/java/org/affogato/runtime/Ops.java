package org.affogato.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.Arrays;

public final class Ops {
    private Ops() {}

    /** {@code ==}: its operands are fresh values in Nix, so they are never the same cell. */
    @TruffleBoundary
    public static boolean equal(Object a, Object b) {
        return equalForced(Thunk.force(a), Thunk.force(b));
    }

    /**
     * Nix's {@code eqValues} on values held in lists, sets or arguments: forced first, then equal if they are the same
     * cell. The same forced object stands in for the same cell, which Nix code observes on functions.
     */
    @TruffleBoundary
    public static boolean equalCells(Object a, Object b) {
        a = Thunk.force(a);
        b = Thunk.force(b);
        return a == b || equalForced(a, b);
    }

    private static boolean equalForced(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) return x.longValue() == y.longValue();
        if ((a instanceof Long || a instanceof Double) && (b instanceof Long || b instanceof Double)) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof NixString x && b instanceof NixString y) return x.value.equals(y.value);
        if (a instanceof NixPath x && b instanceof NixPath y) return x.path().equals(y.path());
        if (a instanceof Boolean x && b instanceof Boolean y) return x.equals(y);
        if (a == NixNull.INSTANCE) return b == NixNull.INSTANCE;
        if (a instanceof NixList x && b instanceof NixList y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!equalCells(x.items[i], y.items[i])) return false;
            }
            return true;
        }
        if (a instanceof NixAttrs x && b instanceof NixAttrs y) {
            if (x.isDerivation() && y.isDerivation()) {
                Object p = x.getRaw("outPath");
                Object q = y.getRaw("outPath");
                if (p != null && q != null) return equalCells(p, q);
            }
            if (!Arrays.equals(x.keys(), y.keys())) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!equalCells(x.values()[i], y.values()[i])) return false;
            }
            return true;
        }
        return false;
    }

    @TruffleBoundary
    public static boolean lessThan(Object a, Object b) {
        a = Thunk.force(a);
        b = Thunk.force(b);
        if (a instanceof Long x && b instanceof Long y) return x < y;
        if ((a instanceof Long || a instanceof Double) && (b instanceof Long || b instanceof Double)) {
            return ((Number) a).doubleValue() < ((Number) b).doubleValue();
        }
        if (a instanceof NixString x && b instanceof NixString y) return x.value.compareTo(y.value) < 0;
        if (a instanceof NixPath x && b instanceof NixPath y) return x.path().compareTo(y.path()) < 0;
        if (a instanceof NixList x && b instanceof NixList y) {
            for (int i = 0; ; i++) {
                if (i == y.size()) return false;
                if (i == x.size()) return true;
                if (!equalCells(x.items[i], y.items[i])) return lessThan(x.items[i], y.items[i]);
            }
        }
        throw new NixException("cannot compare " + Types.describe(a) + " with " + Types.describe(b));
    }

    @TruffleBoundary
    public static Object add(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.addExact(x, y);
            } catch (ArithmeticException e) {
                throw new NixException("integer overflow in adding " + x + " + " + y);
            }
        }
        if (a instanceof Long || a instanceof Double) {
            if (!(b instanceof Long || b instanceof Double)) {
                throw new NixException("cannot add " + Types.describe(b) + " to " + Types.describe(a));
            }
            return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        }
        if (a instanceof NixPath p) {
            NixString s = Coerce.toNixString(b, false, false);
            if (s.hasContext())
                throw new NixException("a string that refers to a store path cannot be appended to a path");
            return new NixPath(Coerce.canonicalize(p.path() + s.value));
        }
        NixString l = Coerce.toNixString(a, false, true);
        NixString r = Coerce.toNixString(b, false, true);
        return new NixString(l.value + r.value, NixString.merge(l.context, r.context));
    }

    private static NixException arith(Object a, Object b) {
        Object bad = a instanceof Long || a instanceof Double ? b : a;
        return new NixException("expected a number but found " + Types.describe(bad));
    }

    @TruffleBoundary
    public static Object sub(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.subtractExact(x, y);
            } catch (ArithmeticException e) {
                throw new NixException("integer overflow in subtracting " + x + " - " + y);
            }
        }
        if (!(a instanceof Number && b instanceof Number)) throw arith(a, b);
        return ((Number) a).doubleValue() - ((Number) b).doubleValue();
    }

    @TruffleBoundary
    public static Object mul(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            try {
                return Math.multiplyExact(x, y);
            } catch (ArithmeticException e) {
                throw new NixException("integer overflow in multiplying " + x + " * " + y);
            }
        }
        if (!(a instanceof Number && b instanceof Number)) throw arith(a, b);
        return ((Number) a).doubleValue() * ((Number) b).doubleValue();
    }

    @TruffleBoundary
    public static Object div(Object a, Object b) {
        if (!(a instanceof Number && b instanceof Number)) throw arith(a, b);
        if (((Number) b).doubleValue() == 0) throw new NixException("division by zero");
        if (a instanceof Long x && b instanceof Long y) {
            if (x == Long.MIN_VALUE && y == -1) throw new NixException("integer overflow in dividing " + x + " / " + y);
            return x / y;
        }
        return ((Number) a).doubleValue() / ((Number) b).doubleValue();
    }

    @TruffleBoundary
    public static Object update(Object a, Object b) {
        return Types.attrs(a).update(Types.attrs(b));
    }

    @TruffleBoundary
    public static Object concatLists(Object a, Object b) {
        NixList x = Types.list(a);
        NixList y = Types.list(b);
        if (x.size() == 0) return y;
        if (y.size() == 0) return x;
        Object[] r = Arrays.copyOf(x.items, x.size() + y.size());
        System.arraycopy(y.items, 0, r, x.size(), y.size());
        return new NixList(r);
    }
}
