package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;

/** Suspends many lazy children without unrolling: python-packages.nix's 10k-attribute set is too large to install. */
final class Lazy {
    static final int MAX_EXPLODED = 64;

    private Lazy() {}

    @TruffleBoundary
    static Object[] all(NixNode[] nodes, MaterializedFrame frame) {
        Object[] out = new Object[nodes.length];
        for (int i = 0; i < nodes.length; i++) out[i] = nodes[i].executeLazy(frame);
        return out;
    }

    @TruffleBoundary
    static void bind(int[] slots, NixNode[] nodes, MaterializedFrame frame) {
        for (int i = 0; i < slots.length; i++) frame.setObject(slots[i], nodes[i].executeLazy(frame));
    }
}
