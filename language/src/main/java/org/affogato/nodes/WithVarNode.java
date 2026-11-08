package org.affogato.nodes;

import org.affogato.runtime.*;

/** A variable bound by no lexical scope, looked up in the enclosing {@code with} scopes, innermost first. */
public final class WithVarNode extends NixNode {
    private final String name;
    private final int[] depths;
    private final int[] slots;

    public WithVarNode(String name, int[] depths, int[] slots) {
        this.name = name;
        this.depths = depths;
        this.slots = slots;
    }

    @Override
    public Object execute(Object[] frame) {
        return Thunk.force(executeLazy(frame));
    }

    @Override
    public Object executeLazy(Object[] frame) {
        for (int i = 0; i < depths.length; i++) {
            NixAttrs scope = Types.attrs(Frames.up(frame, depths[i])[slots[i]]);
            Object v = scope.getRaw(name);
            if (v != null) return v;
        }
        throw new NixException("undefined variable '" + name + "'", this);
    }
}
