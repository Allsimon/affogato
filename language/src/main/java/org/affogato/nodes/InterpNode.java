package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.Set;
import org.affogato.runtime.Coerce;
import org.affogato.runtime.NixException;
import org.affogato.runtime.NixPath;
import org.affogato.runtime.NixString;

/** String interpolation, or path interpolation when the first part is a path. */
public final class InterpNode extends NixNode {
    @Children
    private final NixNode[] parts;

    private final boolean isPath;

    public InterpNode(NixNode[] parts, boolean isPath) {
        this.parts = parts;
        this.isPath = isPath;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] strings = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Object v = parts[i].execute(frame);
            strings[i] = isPath && i == 0 ? v : Coerce.toNixString(v, false, !isPath);
        }
        return concat(strings);
    }

    @TruffleBoundary
    private Object concat(Object[] strings) {
        StringBuilder sb = new StringBuilder();
        Set<String> ctx = null;
        for (int i = 0; i < strings.length; i++) {
            if (isPath && i == 0) {
                sb.append(((NixPath) strings[i]).path());
                continue;
            }
            NixString s = (NixString) strings[i];
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
