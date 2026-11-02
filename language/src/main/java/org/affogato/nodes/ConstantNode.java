package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class ConstantNode extends NixNode {
    private final Object value;

    public ConstantNode(Object value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }
}
