package org.affogato.interp.nodes;

import java.util.Arrays;
import java.util.Set;
import org.affogato.interp.NixContext;
import org.affogato.interp.runtime.*;
import org.affogato.parser.Ast.Op;

/** Port of the Truffle nodes of org.affogato.nodes, one class each. */
public final class Nodes {
    private Nodes() {}

    static Object[] up(Object[] frame, int depth) {
        Object[] f = frame;
        for (int i = 0; i < depth; i++) f = (Object[]) f[0];
        return f;
    }

    public static final class ConstantNode extends Node {
        private final Object value;

        public ConstantNode(Object value) {
            this.value = value;
        }

        @Override
        public Object execute(Object[] frame) {
            return value;
        }
    }

    public static final class ForceNode extends Node {
        private final Node value;

        public ForceNode(Node value) {
            this.value = value;
        }

        @Override
        public Object execute(Object[] frame) {
            return Thunk.force(value.execute(frame));
        }

        @Override
        public Object executeLazy(Object[] frame) {
            return value.execute(frame);
        }
    }

    public static final class ReadVarNode extends Node {
        private final int depth;
        private final int slot;

        public ReadVarNode(int depth, int slot) {
            this.depth = depth;
            this.slot = slot;
        }

        @Override
        public Object execute(Object[] frame) {
            Object[] f = up(frame, depth);
            Object v = f[slot];
            if (v instanceof Thunk t) {
                v = t.force();
                f[slot] = v;
            }
            return v;
        }

        @Override
        public Object executeLazy(Object[] frame) {
            return up(frame, depth)[slot];
        }
    }

    /** A variable bound by no lexical scope, looked up in the enclosing {@code with} scopes, innermost first. */
    public static final class WithVarNode extends Node {
        private final String name;
        private final int[] depths;
        private final int[] slots;

        public WithVarNode(String name, int[] depths, int[] slots) {
            this.name = name;
            this.depths = depths;
            this.slots = slots;
        }

        @Override
        public Object execute(Object[] frame) {
            return Thunk.force(executeLazy(frame));
        }

        @Override
        public Object executeLazy(Object[] frame) {
            for (int i = 0; i < depths.length; i++) {
                NixAttrs scope = Types.attrs(up(frame, depths[i])[slots[i]]);
                Object v = scope.getRaw(name);
                if (v != null) return v;
            }
            throw new NixException("undefined variable '" + name + "'", this);
        }
    }

    /** Reads a variable of a {@code scopedImport} scope, passed as the root's argument. */
    public static final class ScopeArgNode extends Node {
        private final String name;

        public ScopeArgNode(String name) {
            this.name = name;
        }

        @Override
        public Object execute(Object[] frame) {
            return ((NixAttrs) frame[1]).getRaw(name);
        }
    }

    /** An expression in a lazy position with a frame of its own, so that it can be suspended. */
    public static final class ThunkNode extends Node {
        private final Root target;

        public ThunkNode(Root target) {
            this.target = target;
        }

        @Override
        public Object execute(Object[] frame) {
            return target.call(frame, null);
        }

        @Override
        public Object executeLazy(Object[] frame) {
            return new Thunk(target, frame);
        }
    }

    /** A lazy expression evaluated on the frame it captured. */
    public static final class InPlaceThunkNode extends Node {
        private final Node body;

        public InPlaceThunkNode(Node body) {
            this.body = body;
        }

        @Override
        public Object execute(Object[] frame) {
            return body.execute(frame);
        }

        @Override
        public Object executeLazy(Object[] frame) {
            return new Thunk(body, frame);
        }
    }

    /** Stores lazy values into frame slots before evaluating a body: {@code let}, {@code with} and hidden bindings. */
    public static final class BindNode extends Node {
        private final int[] slots;
        private final Node[] values;
        private final Node body;

        public BindNode(int[] slots, Node[] values, Node body) {
            this.slots = slots;
            this.values = values;
            this.body = body;
        }

        @Override
        public Object execute(Object[] frame) {
            for (int i = 0; i < slots.length; i++) frame[slots[i]] = values[i].executeLazy(frame);
            return body.execute(frame);
        }
    }

    public static final class LambdaNode extends Node {
        private final Root target;
        private final NixLambda.LambdaInfo info;

        public LambdaNode(Root target, NixLambda.LambdaInfo info) {
            this.target = target;
            this.info = info;
        }

        @Override
        public Object execute(Object[] frame) {
            return new NixLambda(target, frame, info);
        }
    }

    /** Binds the argument (and formals, if any) in the lambda's frame, then evaluates the body. */
    public static final class LambdaBodyNode extends Node {
        private final String fnName;
        private final int argSlot;
        private final String[] formals;
        private final int[] formalSlots;
        private final Node[] defaults;
        private final boolean ellipsis;
        private final Node body;

        public LambdaBodyNode(
                String fnName,
                int argSlot,
                String[] formals,
                int[] formalSlots,
                Node[] defaults,
                boolean ellipsis,
                Node body) {
            this.fnName = fnName;
            this.argSlot = argSlot;
            this.formals = formals;
            this.formalSlots = formalSlots;
            this.defaults = defaults;
            this.ellipsis = ellipsis;
            this.body = body;
        }

        @Override
        public Object execute(Object[] frame) {
            NixContext ctx = NixContext.get();
            if (ctx.cancelled) throw new Cancelled();
            if (++ctx.callDepth > NixContext.MAX_CALL_DEPTH) {
                ctx.callDepth--;
                throw new NixException("stack overflow; max-call-depth exceeded", this);
            }
            try {
                Object arg = frame[1];
                if (argSlot >= 0) frame[argSlot] = arg;
                if (formals != null) bindFormals(frame, arg);
                return body.execute(frame);
            } finally {
                ctx.callDepth--;
            }
        }

        private void bindFormals(Object[] frame, Object arg) {
            NixAttrs attrs = Types.attrs(arg);
            int matched = 0;
            for (int i = 0; i < formals.length; i++) {
                Object v = attrs.getRaw(formals[i]);
                if (v == null) {
                    if (defaults[i] == null)
                        throw new NixException(fn() + " called without required argument '" + formals[i] + "'", this);
                    v = defaults[i].executeLazy(frame);
                } else {
                    matched++;
                }
                frame[formalSlots[i]] = v;
            }
            if (!ellipsis && matched < attrs.size()) {
                for (String k : attrs.keys()) {
                    if (!Arrays.asList(formals).contains(k))
                        throw new NixException(fn() + " called with unexpected argument '" + k + "'", this);
                }
            }
        }

        private String fn() {
            return fnName == null ? "anonymous lambda" : "function '" + fnName + "'";
        }
    }

    /** Not a {@link NixException}, so that {@code tryEval} cannot catch it. */
    public static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("evaluation cancelled", null, false, false);
        }
    }

    public static final class CallNode extends Node {
        private final Node fn;
        private final Node[] args;

        public CallNode(Node fn, Node[] args) {
            this.fn = fn;
            this.args = args;
        }

        @Override
        public Object execute(Object[] frame) {
            Object f = fn.execute(frame);
            for (Node a : args) f = Apply.apply(f, a.executeLazy(frame));
            return f;
        }
    }

    public static final class ListNode extends Node {
        private final Node[] items;

        public ListNode(Node[] items) {
            this.items = items;
        }

        @Override
        public Object execute(Object[] frame) {
            Object[] out = new Object[items.length];
            for (int i = 0; i < items.length; i++) out[i] = items[i].executeLazy(frame);
            return new NixList(out);
        }
    }

    /** One element of an attribute path: a static name, or a dynamic expression when {@code name} is null. */
    public record AttrKey(String name, Node dynamic) {
        String name(Object[] frame) {
            return name != null ? name : Types.string(dynamic.execute(frame)).value;
        }
    }

    public static final class SelectNode extends Node {
        private final Node target;
        private final AttrKey[] path;
        private final Node fallback;

        public SelectNode(Node target, AttrKey[] path, Node fallback) {
            this.target = target;
            this.path = path;
            this.fallback = fallback;
        }

        @Override
        public Object execute(Object[] frame) {
            Object v = target.execute(frame);
            for (AttrKey key : path) {
                String name = key.name(frame);
                if (!(v instanceof NixAttrs attrs)) {
                    if (fallback != null) return fallback.execute(frame);
                    throw new NixException("expected a set but found " + Types.describe(v), this);
                }
                Object r = attrs.get(name);
                if (r == null) {
                    if (fallback != null) return fallback.execute(frame);
                    throw new NixException("attribute '" + name + "' missing", this);
                }
                v = r;
            }
            return v;
        }
    }

    public static final class HasAttrNode extends Node {
        private final Node target;
        private final AttrKey[] path;

        public HasAttrNode(Node target, AttrKey[] path) {
            this.target = target;
            this.path = path;
        }

        @Override
        public Object execute(Object[] frame) {
            Object v = target.execute(frame);
            for (int i = 0; i < path.length; i++) {
                String name = path[i].name(frame);
                if (!(v instanceof NixAttrs attrs)) return false;
                Object r = attrs.getRaw(name);
                if (r == null) return false;
                if (i < path.length - 1) v = Thunk.force(r);
            }
            return true;
        }
    }

    public static final class AttrsNode extends Node {
        private final String[] keys;
        private final Node[] values;
        private final SourceSection[] positions;
        private final Node[] dynNames;
        private final Node[] dynValues;
        private final SourceSection[] dynPositions;
        private final int overridesIndex;
        private final int[] recSlots;

        /**
         * {@code keys} must be sorted; values are evaluated lazily. For a {@code rec} set defining {@code __overrides},
         * {@code overridesIndex} is its key index and {@code recSlots} the slots of the keys.
         */
        public AttrsNode(
                String[] keys,
                Node[] values,
                SourceSection[] positions,
                Node[] dynNames,
                Node[] dynValues,
                SourceSection[] dynPositions,
                int overridesIndex,
                int[] recSlots) {
            this.keys = keys;
            this.values = values;
            this.positions = positions;
            this.dynNames = dynNames;
            this.dynValues = dynValues;
            this.dynPositions = dynPositions;
            this.overridesIndex = overridesIndex;
            this.recSlots = recSlots;
        }

        @Override
        public Object execute(Object[] frame) {
            Object[] vals = new Object[values.length];
            for (int i = 0; i < values.length; i++) vals[i] = values[i].executeLazy(frame);
            NixAttrs attrs = new NixAttrs(keys, vals, positions);
            if (overridesIndex >= 0) attrs = applyOverrides(frame, attrs);
            for (int i = 0; i < dynNames.length; i++) {
                Object name = dynNames[i].execute(frame);
                if (name == NixNull.INSTANCE) continue;
                attrs = addDynamic(
                        attrs, Types.string(name).value, dynValues[i].executeLazy(frame), dynPositions[i], true);
            }
            return attrs;
        }

        /** Port of the {@code __overrides} handling in {@code ExprAttrs::eval}. */
        private NixAttrs applyOverrides(Object[] frame, NixAttrs attrs) {
            NixAttrs overrides = Types.attrs(attrs.values()[overridesIndex]);
            for (int i = 0; i < overrides.size(); i++) {
                String k = overrides.keys()[i];
                int j = Arrays.binarySearch(keys, k);
                if (j >= 0) {
                    attrs.values()[j] = overrides.values()[i];
                    frame[recSlots[j]] = overrides.values()[i];
                } else {
                    attrs = addDynamic(attrs, k, overrides.values()[i], null, false);
                }
            }
            return attrs;
        }

        private NixAttrs addDynamic(NixAttrs attrs, String name, Object value, SourceSection pos, boolean unique) {
            int i = attrs.indexOf(name);
            if (i >= 0 && !unique) {
                attrs.values()[i] = value;
                return attrs;
            }
            if (i >= 0) throw new NixException("dynamic attribute '" + name + "' already defined", this);
            int at = -i - 1;
            int n = attrs.size();
            String[] k = new String[n + 1];
            Object[] v = new Object[n + 1];
            SourceSection[] p = new SourceSection[n + 1];
            System.arraycopy(attrs.keys(), 0, k, 0, at);
            System.arraycopy(attrs.values(), 0, v, 0, at);
            if (attrs.positions() != null) System.arraycopy(attrs.positions(), 0, p, 0, at);
            k[at] = name;
            v[at] = value;
            p[at] = pos;
            System.arraycopy(attrs.keys(), at, k, at + 1, n - at);
            System.arraycopy(attrs.values(), at, v, at + 1, n - at);
            if (attrs.positions() != null) System.arraycopy(attrs.positions(), at, p, at + 1, n - at);
            return new NixAttrs(k, v, p);
        }
    }

    public static final class IfNode extends Node {
        private final Node cond;
        private final Node then;
        private final Node otherwise;

        public IfNode(Node cond, Node then, Node otherwise) {
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public Object execute(Object[] frame) {
            return Types.bool(cond.execute(frame)) ? then.execute(frame) : otherwise.execute(frame);
        }
    }

    public static final class NotNode extends Node {
        private final Node operand;

        public NotNode(Node operand) {
            this.operand = operand;
        }

        @Override
        public Object execute(Object[] frame) {
            return !Types.bool(operand.execute(frame));
        }
    }

    public static final class AssertNode extends Node {
        private final Node cond;
        private final Node body;
        private final String text;

        public AssertNode(Node cond, Node body, String text) {
            this.cond = cond;
            this.body = body;
            this.text = text;
        }

        @Override
        public Object execute(Object[] frame) {
            if (!Types.bool(cond.execute(frame))) throw new NixException("assertion '" + text + "' failed", this, true);
            return body.execute(frame);
        }
    }

    /** {@code &&}, {@code ||} and {@code ->}, which short-circuit on the left operand. */
    public static final class LogicNode extends Node {
        private final Op op;
        private final Node left;
        private final Node right;

        public LogicNode(Op op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public Object execute(Object[] frame) {
            boolean l = Types.bool(left.execute(frame));
            return switch (op) {
                case AND -> l && Types.bool(right.execute(frame));
                case OR -> l || Types.bool(right.execute(frame));
                default -> !l || Types.bool(right.execute(frame));
            };
        }
    }

    public static final class BinaryNode extends Node {
        private final Op op;
        private final Node left;
        private final Node right;

        public BinaryNode(Op op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public Object execute(Object[] frame) {
            Object l = left.execute(frame);
            Object r = right.execute(frame);
            return switch (op) {
                case EQ -> Ops.equal(l, r);
                case NEQ -> !Ops.equal(l, r);
                case UPDATE -> Ops.update(l, r);
                case CONCAT -> Ops.concatLists(l, r);
                case ADD -> Ops.add(l, r);
                case SUB -> Ops.sub(l, r);
                case MUL -> Ops.mul(l, r);
                case DIV -> Ops.div(l, r);
                case LT -> Ops.lessThan(l, r);
                case GT -> Ops.lessThan(r, l);
                case LE -> !Ops.lessThan(r, l);
                case GE -> !Ops.lessThan(l, r);
                default -> throw new IllegalStateException(op.toString());
            };
        }
    }

    /** String interpolation, or path interpolation when the first part is a path. */
    public static final class InterpNode extends Node {
        private final Node[] parts;
        private final boolean isPath;

        public InterpNode(Node[] parts, boolean isPath) {
            this.parts = parts;
            this.isPath = isPath;
        }

        @Override
        public Object execute(Object[] frame) {
            StringBuilder sb = new StringBuilder();
            Set<String> ctx = null;
            for (int i = 0; i < parts.length; i++) {
                Object v = parts[i].execute(frame);
                if (isPath && i == 0) {
                    sb.append(((NixPath) v).path());
                    continue;
                }
                NixString s = Coerce.toNixString(v, false, !isPath);
                sb.append(s.value);
                ctx = NixString.merge(ctx, s.context);
            }
            if (isPath) {
                if (ctx != null)
                    throw new NixException("a string that refers to a store path cannot be appended to a path", this);
                return new NixPath(Coerce.canonicalize(sb.toString()));
            }
            return new NixString(sb.toString(), ctx);
        }
    }
}
