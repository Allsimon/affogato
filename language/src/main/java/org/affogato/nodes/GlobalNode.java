package org.affogato.nodes;

import org.affogato.NixContext;
import org.affogato.runtime.Thunk;

/** A global whose value differs between contexts, so that translated files can be shared. */
public final class GlobalNode extends NixNode {
    private final String name;

    public GlobalNode(String name) {
        this.name = name;
    }

    @Override
    public Object execute(Object[] frame) {
        return Thunk.force(executeLazy(frame));
    }

    @Override
    public Object executeLazy(Object[] frame) {
        return NixContext.get().globals.get(name);
    }
}
