package org.affogato.nodes;

import org.affogato.runtime.*;

public final class AssertNode extends NixNode {
    private final NixNode cond;
    private final NixNode body;
    private final String text;

    public AssertNode(NixNode cond, NixNode body, String text) {
        this.cond = cond;
        this.body = body;
        this.text = text;
    }

    @Override
    public Object execute(Object[] frame) {
        if (!Types.bool(cond.execute(frame))) throw new NixException("assertion '" + text + "' failed", this, true);
        return body.execute(frame);
    }
}
