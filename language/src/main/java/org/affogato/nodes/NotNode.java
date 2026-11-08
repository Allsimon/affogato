package org.affogato.nodes;

import org.affogato.runtime.*;

public final class NotNode extends NixNode {
    private final NixNode operand;

    public NotNode(NixNode operand) {
        this.operand = operand;
    }

    @Override
    public Object execute(Object[] frame) {
        return !Types.bool(operand.execute(frame));
    }
}
