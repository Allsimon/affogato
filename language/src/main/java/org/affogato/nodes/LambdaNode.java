package org.affogato.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.NixLambda;

public final class LambdaNode extends NixNode {
    private final RootCallTarget target;
    private final NixLambda.LambdaInfo info;

    public LambdaNode(RootCallTarget target, NixLambda.LambdaInfo info) {
        this.target = target;
        this.info = info;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return new NixLambda(target, frame.materialize(), info);
    }
}
