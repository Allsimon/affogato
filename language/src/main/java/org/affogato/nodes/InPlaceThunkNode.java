package org.affogato.nodes;

import org.affogato.runtime.*;

/** A lazy expression evaluated on the frame it captured. */
public final class InPlaceThunkNode extends NixNode {
    private final NixNode body;

    public InPlaceThunkNode(NixNode body) {
        this.body = body;
    }

    @Override
    public Object execute(Object[] frame) {
        return body.execute(frame);
    }

    @Override
    public Object executeLazy(Object[] frame) {
        return new Thunk(body, frame);
    }
}
