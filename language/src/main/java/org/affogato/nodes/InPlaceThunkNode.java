package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.Thunk;

/** A lazy expression evaluated on the frame it captured, without a call target and frame of its own. */
public final class InPlaceThunkNode extends NixNode {
    @Child
    private NixNode body;

    public InPlaceThunkNode(NixNode body) {
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.execute(frame);
    }

    @Override
    public Object executeLazy(VirtualFrame frame) {
        return new Thunk(body, frame.materialize());
    }
}
