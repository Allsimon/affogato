package org.affogato.nodes;

import org.affogato.runtime.*;

public final class IfNode extends NixNode {
    private final NixNode cond;
    private final NixNode then;
    private final NixNode otherwise;

    public IfNode(NixNode cond, NixNode then, NixNode otherwise) {
        this.cond = cond;
        this.then = then;
        this.otherwise = otherwise;
    }

    @Override
    public Object execute(Object[] frame) {
        return Types.bool(cond.execute(frame)) ? then.execute(frame) : otherwise.execute(frame);
    }
}
