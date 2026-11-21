package org.affogato.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Supplier;
import org.affogato.Parallel;
import org.affogato.nodes.NixNode;
import org.affogato.nodes.NixRootNode;

public final class Thunk {
    /** Stands in for {@code env} once evaluated: {@code code} is then the value. */
    private static final Object DONE = new Object();

    /** {@code env}, which holds the evaluating thread while the thunk is evaluated. */
    private static final VarHandle ENV;

    static {
        try {
            ENV = MethodHandles.lookup().findVarHandle(Thunk.class, "env", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * A {@link NixRootNode} called with {@code env} as parent frame, a {@link NixNode} executed on {@code env}, a
     * {@link Supplier}, a {@link MapAttrs} entry whose key is {@code env}, a {@link ZipAttrs} entry whose name and
     * values are {@code env}, an {@link Eager}, or a function applied to {@code env}.
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

    /** Makes the values of one {@code zipAttrsWith fn}: {@link ZipAttrs#entry}. */
    public static ZipAttrs zipAttrs(Object fn) {
        return new ZipAttrs(fn);
    }

    public record ZipAttrs(Object fn) {
        /** {@code fn name values}, suspended; {@code entry} is the name, a string, followed by the values. */
        public Thunk entry(Object[] entry) {
            return new Thunk(this, entry, true);
        }
    }

    public boolean isEvaluated() {
        return ENV.getAcquire(this) == DONE || code instanceof Eager;
    }

    public Object force() {
        Object e = ENV.getAcquire(this);
        return e == DONE ? code : evaluate(e);
    }

    private Object evaluate(Object e) {
        if (code instanceof Eager x) return x.get();
        Thread me = Thread.currentThread();
        if (e instanceof Thread || e instanceof Waited) {
            if (e == me || e instanceof Waited w && w.owner == me) {
                throw new NixException("infinite recursion encountered" + describeSource());
            }
            return await();
        }
        if (!Parallel.on) {
            env = me;
        } else if (!ENV.compareAndSet(this, e, me)) {
            return force();
        }
        boolean ok = false;
        try {
            Object v = switch (code) {
                case NixRootNode target -> target.call(e, null);
                case NixNode body -> body.execute((Object[]) e);
                case Supplier<?> supplier -> supplier.get();
                case MapAttrs m -> {
                    Name key = (Name) e;
                    yield Apply.apply(Apply.apply(m.fn, new NixString(key.name)), m.attrs.getRaw(key));
                }
                case ZipAttrs z -> {
                    Object[] entry = (Object[]) e;
                    NixList values = new NixList(java.util.Arrays.copyOfRange(entry, 1, entry.length));
                    yield Apply.apply(Apply.apply(z.fn, new NixString((String) entry[0])), values);
                }
                default -> Apply.apply(code, e);
            };
            v = Thunk.force(v);
            this.code = v;
            publish(DONE);
            ok = true;
            return v;
        } finally {
            if (!ok) publish(e);
        }
    }

    /** Replaces the evaluating thread in {@code env}, waking the threads that wait for it. */
    private void publish(Object state) {
        if (!Parallel.on) {
            ENV.setRelease(this, state);
        } else if (ENV.getAndSet(this, state) instanceof Waited) {
            synchronized (Waited.class) {
                Waited.class.notifyAll();
            }
        }
    }

    /** Stands in for {@code env} while {@code owner} evaluates the thunk and other threads wait for it. */
    private record Waited(Thread owner) {}

    /** Waits for the thread evaluating this thunk to finish or give up, then forces it. */
    private Object await() {
        for (; ; ) {
            Object s = ENV.getAcquire(this);
            if (s instanceof Thread owner) {
                if (Parallel.on) ENV.compareAndSet(this, s, new Waited(owner));
                else Thread.yield();
            } else if (s instanceof Waited) {
                synchronized (Waited.class) {
                    while (ENV.getAcquire(this) == s) {
                        try {
                            Waited.class.wait();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new NixException("interrupted");
                        }
                    }
                }
            } else {
                return force();
            }
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
