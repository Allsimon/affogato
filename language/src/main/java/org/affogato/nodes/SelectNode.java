package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.Types;

public final class SelectNode extends NixNode {
    @Child
    private NixNode target;

    @Children
    private final AttrKey[] path;

    @Child
    private NixNode fallback;

    public SelectNode(NixNode target, AttrKey[] path, NixNode fallback) {
        this.target = target;
        this.path = path;
        this.fallback = fallback;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object v = target.execute(frame);
        for (AttrKey key : path) {
            String name = key.name(frame);
            if (!(v instanceof NixAttrs attrs)) {
                if (fallback != null) return fallback.execute(frame);
                throw notASet(v);
            }
            Object r = attrs.get(name);
            if (r == null) {
                if (fallback != null) return fallback.execute(frame);
                throw missing(name, attrs);
            }
            v = r;
        }
        return v;
    }

    @TruffleBoundary
    private NixException notASet(Object v) {
        return new NixException("expected a set but found " + Types.describe(v), this);
    }

    @TruffleBoundary
    private NixException missing(String name, NixAttrs attrs) {
        return new NixException("attribute '" + name + "' missing", this);
    }
}
