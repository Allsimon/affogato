package org.affogato.nodes;

import org.affogato.runtime.*;

/** String interpolation, or path interpolation when the first part is a path. */
public final class InterpNode extends NixNode {
    private final NixNode[] parts;
    private final boolean isPath;

    public InterpNode(NixNode[] parts, boolean isPath) {
        this.parts = parts;
        this.isPath = isPath;
    }

    @Override
    public Object execute(Object[] frame) {
        StringBuilder sb = new StringBuilder();
        String[] ctx = null;
        for (int i = 0; i < parts.length; i++) {
            Object v = parts[i].execute(frame);
            if (isPath && i == 0) {
                sb.append(((NixPath) v).path());
                continue;
            }
            NixString s = Coerce.toNixString(v, false, !isPath);
            sb.append(s.value);
            ctx = NixString.merge(ctx, s.context);
        }
        if (isPath) {
            if (ctx != null)
                throw new NixException("a string that refers to a store path cannot be appended to a path", this);
            return new NixPath(Coerce.canonicalize(sb.toString()));
        }
        return new NixString(sb.toString(), ctx);
    }
}
