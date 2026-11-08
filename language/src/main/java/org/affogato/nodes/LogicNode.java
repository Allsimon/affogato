package org.affogato.nodes;

import org.affogato.parser.Ast.Op;
import org.affogato.runtime.*;

/** {@code &&}, {@code ||} and {@code ->}, which short-circuit on the left operand. */
public final class LogicNode extends NixNode {
    private final Op op;
    private final NixNode left;
    private final NixNode right;

    public LogicNode(Op op, NixNode left, NixNode right) {
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
