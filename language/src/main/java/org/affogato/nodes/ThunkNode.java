package org.affogato.nodes;

import org.affogato.runtime.*;

/** An expression in a lazy position with a frame of its own, so that it can be suspended. */
public final class ThunkNode extends NixNode {
    private final NixRootNode target;

    public ThunkNode(NixRootNode target) {
        this.target = target;
    }

    @Override
    public Object execute(Object[] frame) {
        return target.call(frame, null);
    }

    @Override
    public Object executeLazy(Object[] frame) {
        return new Thunk(target, frame);
    }
}
