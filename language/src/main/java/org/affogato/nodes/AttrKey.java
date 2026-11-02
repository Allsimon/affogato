package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import org.affogato.runtime.Types;

/** One element of an attribute path: a static name, or a dynamic expression when {@code name} is null. */
public final class AttrKey extends NixNode {
    private final String name;

    @Child
    private NixNode dynamic;

    public AttrKey(String name, NixNode dynamic) {
        this.name = name;
        this.dynamic = dynamic;
    }

    public String name(VirtualFrame frame) {
        return name != null ? name : Types.string(dynamic.execute(frame)).value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return name(frame);
    }

    @Override
    public String toString() {
        return name != null ? name : "${...}";
    }
}
