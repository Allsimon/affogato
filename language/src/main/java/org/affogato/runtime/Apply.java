package org.affogato.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

public final class Apply {
    private Apply() {}

    /** Applies a function value to one (possibly lazy) argument and returns a forced result. */
    @TruffleBoundary
    public static Object apply(Object fn, Object arg) {
        fn = Thunk.force(fn);
        if (fn instanceof NixLambda l) return l.target.call(l.closure, arg);
        if (fn instanceof PrimOp p) {
            if (p.arity == 1) return Thunk.force(p.impl.call(new Object[] {arg}));
            return new PrimOp.Partial(p, new Object[] {arg});
        }
        if (fn instanceof PrimOp.Partial pa) {
            Object[] args = java.util.Arrays.copyOf(pa.args(), pa.args().length + 1);
            args[args.length - 1] = arg;
            if (args.length == pa.op().arity) return Thunk.force(pa.op().impl.call(args));
            return new PrimOp.Partial(pa.op(), args);
        }
        if (fn instanceof NixAttrs a) {
            Object functor = a.getRaw("__functor");
            if (functor != null) return apply(apply(functor, a), arg);
        }
        throw new NixException("attempt to call something which is not a function but " + Types.describe(fn));
    }

    public static Object apply(Object fn, Object a, Object b) {
        return apply(apply(fn, a), b);
    }
}
