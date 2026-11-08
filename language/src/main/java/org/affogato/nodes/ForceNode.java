package org.affogato.nodes;

import org.affogato.runtime.*;

public final class ForceNode extends NixNode {
    private final NixNode value;

    public ForceNode(NixNode value) {
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
