package org.affogato.nodes;

import org.affogato.parser.Ast.Op;
import org.affogato.runtime.*;

public final class BinaryNode extends NixNode {
    private final Op op;
    private final NixNode left;
    private final NixNode right;

    public BinaryNode(Op op, NixNode left, NixNode right) {
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
