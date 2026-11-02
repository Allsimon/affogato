package org.affogato.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import org.affogato.runtime.Thunk;

/** An expression in a lazy position, compiled as its own root so that it can be suspended. */
public final class ThunkNode extends NixNode {
    private final RootCallTarget target;

    @Child
    private DirectCallNode direct;

    public ThunkNode(RootCallTarget target) {
        this.target = target;
        this.direct = DirectCallNode.create(target);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return direct.call(frame.materialize());
    }

    @Override
    public Object executeLazy(VirtualFrame frame) {
        return new Thunk(target, frame.materialize());
    }
}
