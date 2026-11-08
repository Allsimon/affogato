package org.affogato.nodes;

import org.affogato.runtime.*;

public final class HasAttrNode extends NixNode {
    private final NixNode target;
    private final AttrKey[] path;

    public HasAttrNode(NixNode target, AttrKey[] path) {
        this.target = target;
        this.path = path;
    }

    @Override
    public Object execute(Object[] frame) {
        Object v = target.execute(frame);
        for (int i = 0; i < path.length; i++) {
            String name = path[i].name(frame);
            if (!(v instanceof NixAttrs attrs)) return false;
            Object r = attrs.getRaw(name);
            if (r == null) return false;
            if (i < path.length - 1) v = Thunk.force(r);
        }
        return true;
    }
}
