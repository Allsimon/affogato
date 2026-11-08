package org.affogato.nodes;

import org.affogato.runtime.*;

/** Stores lazy values into frame slots before evaluating a body: {@code let}, {@code with} and hidden bindings. */
public final class BindNode extends NixNode {
    private final int[] slots;
    private final NixNode[] values;
    private final NixNode body;

    public BindNode(int[] slots, NixNode[] values, NixNode body) {
        this.slots = slots;
        this.values = values;
        this.body = body;
    }

    @Override
    public Object execute(Object[] frame) {
        for (int i = 0; i < slots.length; i++) frame[slots[i]] = values[i].executeLazy(frame);
        return body.execute(frame);
    }
}
