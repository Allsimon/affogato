package org.affogato.nodes;

import org.affogato.runtime.*;

public final class SelectNode extends NixNode {
    private final NixNode target;
    private final AttrKey[] path;
    private final NixNode fallback;

    public SelectNode(NixNode target, AttrKey[] path, NixNode fallback) {
        this.target = target;
        this.path = path;
        this.fallback = fallback;
    }

    @Override
    public Object execute(Object[] frame) {
        Object v = target.execute(frame);
        for (AttrKey key : path) {
            String name = key.name(frame);
            if (!(v instanceof NixAttrs attrs)) {
                if (fallback != null) return fallback.execute(frame);
                throw new NixException("expected a set but found " + Types.describe(v), this);
            }
            Object r = attrs.get(name);
            if (r == null) {
                if (fallback != null) return fallback.execute(frame);
                throw new NixException("attribute '" + name + "' missing", this);
            }
            v = r;
        }
        return v;
    }
}
