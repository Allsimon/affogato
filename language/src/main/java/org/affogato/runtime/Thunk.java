package org.affogato.runtime;

import java.util.function.Supplier;
import org.affogato.nodes.NixNode;
import org.affogato.nodes.NixRootNode;

public final class Thunk {
    /** Stands in for {@code env} while the thunk is being evaluated. */
    private static final Object EVALUATING = new Object();

    /**
     * A {@link NixRootNode} called with {@code env} as parent frame, a {@link NixNode} executed on {@code env}, a {@link Supplier}, a {@link MapAttrs} entry whose key is {@code
     * env}, or a function applied to {@code env}.
     */
    private Object code;

    private Object env;
    private Object value;

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
        return value != null;
    }

    public Object force() {
        if (value != null) return value;
        return evaluate();
    }

    private Object evaluate() {
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
            value = Thunk.force(v);
            code = null;
            env = null;
            return value;
        } finally {
            if (value == null) env = e;
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
