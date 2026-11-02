package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.Thunk;
import org.affogato.runtime.Types;

/** A variable bound by no lexical scope, looked up in the enclosing {@code with} scopes, innermost first. */
public final class WithVarNode extends NixNode {
    private final String name;

    @CompilationFinal(dimensions = 1)
    private final int[] depths;

    @CompilationFinal(dimensions = 1)
    private final int[] slots;

    public WithVarNode(String name, int[] depths, int[] slots) {
        this.name = name;
        this.depths = depths;
        this.slots = slots;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return Thunk.force(executeLazy(frame));
    }

    @Override
    @ExplodeLoop
    public Object executeLazy(VirtualFrame frame) {
        for (int i = 0; i < depths.length; i++) {
            NixAttrs scope = Types.attrs(Frames.up(frame, depths[i]).getObject(slots[i]));
            Object v = scope.getRaw(name);
            if (v != null) return v;
        }
        throw undefined();
    }

    @TruffleBoundary
    private NixException undefined() {
        return new NixException("undefined variable '" + name + "'", this);
    }
}
