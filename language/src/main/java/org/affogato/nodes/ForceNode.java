package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.Thunk;

public final class ForceNode extends NixNode {
    @Child
    private NixNode value;

    public ForceNode(NixNode value) {
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return Thunk.force(value.execute(frame));
    }

    @Override
    public Object executeLazy(VirtualFrame frame) {
        return value.execute(frame);
    }
}
