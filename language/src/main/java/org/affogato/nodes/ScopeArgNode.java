package org.affogato.nodes;

import org.affogato.runtime.*;

/** Reads a variable of a {@code scopedImport} scope, passed as the root's argument. */
public final class ScopeArgNode extends NixNode {
    private final String name;

    public ScopeArgNode(String name) {
        this.name = name;
    }

    @Override
    public Object execute(Object[] frame) {
        return ((NixAttrs) frame[1]).getRaw(name);
    }
}
