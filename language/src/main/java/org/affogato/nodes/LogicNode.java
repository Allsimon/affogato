package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.Types;

/** {@code &&}, {@code ||} and {@code ->}, which short-circuit on the left operand. */
public final class LogicNode extends NixNode {
    public enum Kind {
        AND,
        OR,
        IMPL
    }

    private final Kind kind;

    @Child
    private NixNode left;

    @Child
    private NixNode right;

    public LogicNode(Kind kind, NixNode left, NixNode right) {
        this.kind = kind;
        this.left = left;
        this.right = right;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        boolean l = Types.bool(left.execute(frame));
        return switch (kind) {
            case AND -> l && Types.bool(right.execute(frame));
            case OR -> l || Types.bool(right.execute(frame));
            case IMPL -> !l || Types.bool(right.execute(frame));
        };
    }
}
