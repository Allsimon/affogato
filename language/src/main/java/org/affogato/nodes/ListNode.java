package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import org.affogato.runtime.NixList;

public final class ListNode extends NixNode {
    @Children
    private final NixNode[] items;

    public ListNode(NixNode[] items) {
        this.items = items;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return new NixList(items.length > Lazy.MAX_EXPLODED ? Lazy.all(items, frame.materialize()) : lazyItems(frame));
    }

    @ExplodeLoop
    private Object[] lazyItems(VirtualFrame frame) {
        Object[] out = new Object[items.length];
        for (int i = 0; i < items.length; i++) out[i] = items[i].executeLazy(frame);
        return out;
    }
}
