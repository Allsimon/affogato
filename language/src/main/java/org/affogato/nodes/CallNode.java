package org.affogato.nodes;

import org.affogato.runtime.*;

public final class CallNode extends NixNode {
    private final NixNode fn;
    private final NixNode[] args;

    public CallNode(NixNode fn, NixNode[] args) {
        this.fn = fn;
        this.args = args;
    }

    @Override
    public Object execute(Object[] frame) {
        Object f = fn.execute(frame);
        for (NixNode a : args) f = Apply.apply(f, a.executeLazy(frame));
        return f;
    }
}
