package org.affogato.nodes;

import org.affogato.runtime.*;

public final class ConstantNode extends NixNode {
    private final Object value;

    public ConstantNode(Object value) {
        this.value = value;
    }

    @Override
    public Object execute(Object[] frame) {
        return value;
    }
}
