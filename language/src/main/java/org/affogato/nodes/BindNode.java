package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/** Stores lazy values into frame slots before evaluating a body: {@code let}, {@code with} and hidden bindings. */
public final class BindNode extends NixNode {
    @CompilationFinal(dimensions = 1)
    private final int[] slots;

    @Children
    private final NixNode[] values;

    @Child
    private NixNode body;

    public BindNode(int[] slots, NixNode[] values, NixNode body) {
        this.slots = slots;
        this.values = values;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (slots.length > Lazy.MAX_EXPLODED) {
            Lazy.bind(slots, values, frame.materialize());
        } else {
            bind(frame);
        }
        return body.execute(frame);
    }

    @ExplodeLoop
    private void bind(VirtualFrame frame) {
        for (int i = 0; i < slots.length; i++) frame.setObject(slots[i], values[i].executeLazy(frame));
    }
}
