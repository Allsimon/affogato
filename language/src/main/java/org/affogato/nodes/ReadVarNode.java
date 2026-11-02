package org.affogato.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.Thunk;

public final class ReadVarNode extends NixNode {
    private final int depth;
    private final int slot;

    public ReadVarNode(int depth, int slot) {
        this.depth = depth;
        this.slot = slot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Frame f = Frames.up(frame, depth);
        Object v = f.getObject(slot);
        if (v instanceof Thunk t) {
            v = t.force();
            f.setObject(slot, v);
        }
        return v;
    }

    @Override
    public Object executeLazy(VirtualFrame frame) {
        return Frames.up(frame, depth).getObject(slot);
    }
}
