package org.affogato.runtime;

import java.util.function.Supplier;
import org.affogato.nodes.NixNode;
import org.affogato.nodes.NixRootNode;

public final class Thunk {
    /** Stands in for {@code env} while the thunk is being evaluated. */
    private static final Object EVALUATING = new Object();

    /** Stands in for {@code env} once evaluated: {@code code} is then the value. */
    private static final Object DONE = new Object();

    /**
     * A {@link NixRootNode} called with {@code env} as parent frame, a {@link NixNode} executed on {@code env}, a
     * {@link Supplier}, a {@link MapAttrs} entry whose key is {@code env}, an {@link Eager}, or a function applied to
     * {@code env}.
     */
    private Object code;

    private Object env;

    /** A suspended expression evaluated on {@code frame}, the frame it was translated for. */
    public Thunk(NixNode body, Object[] frame) {
        this.code = body;
        this.env = frame;
    }

    /** A suspended root: forcing calls {@code target} with {@code env} as its parent frame. */
    public Thunk(NixRootNode target, Object env) {
        this.code = target;
        this.env = env;
    }

    public Thunk(Supplier<Object> supplier) {
        this.code = supplier;
    }

    private Thunk(Object fn, Object arg, boolean application) {
        this.code = fn;
        this.env = arg;
    }

    /**
     * Computed on demand, but held as a value by Nix, so it prints as evaluated. {@code supplier} must be pure and
     * cannot recurse: such thunks may be forced from several threads at once.
     */
    public static Thunk eager(Supplier<Object> supplier) {
        return new Thunk(new Eager(supplier), null, false);
    }

    /** Keeps its value itself: a thunk's two fields cannot be published together to another thread. */
    private static final class Eager {
        private final Supplier<Object> supplier;
        private Object value;

        Eager(Supplier<Object> supplier) {
            this.supplier = supplier;
        }

        Object get() {
            Object v = value;
            if (v == null) value = v = supplier.get();
            return v;
        }
    }

    public static Thunk apply(Object fn, Object arg) {
        return new Thunk(fn, arg, true);
    }

    /** The values of {@code mapAttrs fn attrs}; most are never forced, so each holds only its key. */
    public static Object[] mapAttrs(Object fn, NixAttrs attrs) {
        MapAttrs m = new MapAttrs(fn, attrs);
        Object[] out = new Object[attrs.size()];
        for (int i = 0; i < out.length; i++) out[i] = new Thunk(m, attrs.keys()[i], true);
        return out;
    }

    private record MapAttrs(Object fn, NixAttrs attrs) {}

    public static Thunk apply(Object fn, Object a, Object b) {
        return new Thunk(() -> Apply.apply(Apply.apply(fn, a), b));
    }

    public boolean isEvaluated() {
        return env == DONE || code instanceof Eager;
    }

    public Object force() {
        return env == DONE ? code : evaluate();
    }

    private Object evaluate() {
        Object code = this.code;
        if (code instanceof Eager x) return x.get();
        Object e = env;
        if (e == EVALUATING) throw new NixException("infinite recursion encountered" + describeSource());
        env = EVALUATING;
        try {
            Object v = switch (code) {
                case NixRootNode target -> target.call(e, null);
                case NixNode body -> body.execute((Object[]) e);
                case Supplier<?> supplier -> supplier.get();
                case MapAttrs m -> {
                    String key = (String) e;
                    yield Apply.apply(Apply.apply(m.fn, new NixString(key)), m.attrs.getRaw(key));
                }
                default -> Apply.apply(code, e);
            };
            v = Thunk.force(v);
            this.code = v;
            env = DONE;
            return v;
        } finally {
            if (env == EVALUATING) env = e;
        }
    }

    private String describeSource() {
        if (code instanceof NixNode body && body.getSourceSection() != null) {
            var s = body.getSourceSection();
            return "\n       while evaluating <thunk> at " + s.getSource().getName() + ":" + s.getStartLine() + ":"
                    + s.getStartColumn();
        }
        if (code instanceof NixRootNode rt && rt.section != null) {
            var s = rt.section;
            return "\n       while evaluating " + rt.name + " at "
                    + s.getSource().getName() + ":" + s.getStartLine() + ":" + s.getStartColumn();
        }
        return "";
    }

    public static Object force(Object o) {
        return o instanceof Thunk t ? t.force() : o;
    }
}
