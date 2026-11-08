package org.affogato.nodes;

import org.affogato.runtime.*;

public final class LambdaNode extends NixNode {
    private final NixRootNode target;
    private final NixLambda.LambdaInfo info;

    public LambdaNode(NixRootNode target, NixLambda.LambdaInfo info) {
        this.target = target;
        this.info = info;
    }

    @Override
    public Object execute(Object[] frame) {
        return new NixLambda(target, frame, info);
    }
}
