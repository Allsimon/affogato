package org.affogato.nodes;

import org.affogato.runtime.*;

public final class LambdaNode extends NixNode {
    private final NixRootNode target;

    public LambdaNode(NixRootNode target) {
        this.target = target;
    }

    @Override
    public Object execute(Object[] frame) {
        return new NixLambda(target, frame);
    }
}
