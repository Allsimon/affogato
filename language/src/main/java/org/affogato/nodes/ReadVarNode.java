package org.affogato.nodes;

import org.affogato.runtime.*;

public final class ReadVarNode extends NixNode {
    private final int depth;
    private final int slot;

    public ReadVarNode(int depth, int slot) {
        this.depth = depth;
        this.slot = slot;
    }

    @Override
    public Object execute(Object[] frame) {
        Object[] f = Frames.up(frame, depth);
        Object v = f[slot];
        if (v instanceof Thunk t) {
            v = t.force();
            f[slot] = v;
        }
        return v;
    }

    @Override
    public Object executeLazy(Object[] frame) {
        return Frames.up(frame, depth)[slot];
    }
}
