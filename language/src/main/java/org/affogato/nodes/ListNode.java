package org.affogato.nodes;

import org.affogato.runtime.*;

public final class ListNode extends NixNode {
    private final NixNode[] items;

    public ListNode(NixNode[] items) {
        this.items = items;
    }

    @Override
    public Object execute(Object[] frame) {
        Object[] out = new Object[items.length];
        for (int i = 0; i < items.length; i++) out[i] = items[i].executeLazy(frame);
        return new NixList(out);
    }
}
