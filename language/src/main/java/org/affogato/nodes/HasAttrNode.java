package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.Thunk;

public final class HasAttrNode extends NixNode {
    @Child
    private NixNode target;

    @Children
    private final AttrKey[] path;

    public HasAttrNode(NixNode target, AttrKey[] path) {
        this.target = target;
        this.path = path;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
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
