package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.Types;

public final class NotNode extends NixNode {
    @Child
    private NixNode operand;

    public NotNode(NixNode operand) {
        this.operand = operand;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return !Types.bool(operand.execute(frame));
    }
}
