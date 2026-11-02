package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.source.SourceSection;
import java.util.Arrays;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.NixNull;
import org.affogato.runtime.Types;

public final class AttrsNode extends NixNode {
    @CompilationFinal(dimensions = 1)
    private final String[] keys;

    @Children
    private final NixNode[] values;

    @CompilationFinal(dimensions = 1)
    private final SourceSection[] positions;

    @Children
    private final NixNode[] dynNames;

    @Children
    private final NixNode[] dynValues;

    @CompilationFinal(dimensions = 1)
    private final SourceSection[] dynPositions;

    private final int overridesIndex;

    @CompilationFinal(dimensions = 1)
    private final int[] recSlots;

    /**
     * {@code keys} must be sorted; values are evaluated lazily. For a {@code rec} set defining {@code __overrides},
     * {@code overridesIndex} is its key index and {@code recSlots} the slots of the keys.
     */
    public AttrsNode(
            String[] keys,
            NixNode[] values,
            SourceSection[] positions,
            NixNode[] dynNames,
            NixNode[] dynValues,
            SourceSection[] dynPositions,
            int overridesIndex,
            int[] recSlots) {
        this.overridesIndex = overridesIndex;
        this.recSlots = recSlots;
        this.keys = keys;
        this.values = values;
        this.positions = positions;
        this.dynNames = dynNames;
        this.dynValues = dynValues;
        this.dynPositions = dynPositions;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] vals = values.length > Lazy.MAX_EXPLODED ? Lazy.all(values, frame.materialize()) : lazyValues(frame);
        NixAttrs attrs = new NixAttrs(keys, vals, positions);
        if (overridesIndex >= 0) attrs = applyOverrides(frame.materialize(), attrs);
        if (dynNames.length == 0) return attrs;
        for (int i = 0; i < dynNames.length; i++) {
            Object name = dynNames[i].execute(frame);
            if (name == NixNull.INSTANCE) continue;
            attrs = addDynamic(attrs, Types.string(name).value, dynValues[i].executeLazy(frame), dynPositions[i]);
        }
        return attrs;
    }

    @ExplodeLoop
    private Object[] lazyValues(VirtualFrame frame) {
        Object[] vals = new Object[values.length];
        for (int i = 0; i < values.length; i++) vals[i] = values[i].executeLazy(frame);
        return vals;
    }

    /** Port of the {@code __overrides} handling in {@code ExprAttrs::eval}. */
    @TruffleBoundary
    private NixAttrs applyOverrides(MaterializedFrame frame, NixAttrs attrs) {
        NixAttrs overrides = Types.attrs(attrs.values()[overridesIndex]);
        for (int i = 0; i < overrides.size(); i++) {
            String k = overrides.keys()[i];
            int j = Arrays.binarySearch(keys, k);
            if (j >= 0) {
                attrs.values()[j] = overrides.values()[i];
                frame.setObject(recSlots[j], overrides.values()[i]);
            } else {
                attrs = addDynamic(attrs, k, overrides.values()[i], null, false);
            }
        }
        return attrs;
    }

    @TruffleBoundary
    private NixAttrs addDynamic(NixAttrs attrs, String name, Object value, SourceSection pos) {
        return addDynamic(attrs, name, value, pos, true);
    }

    private NixAttrs addDynamic(NixAttrs attrs, String name, Object value, SourceSection pos, boolean unique) {
        int i = attrs.indexOf(name);
        if (i >= 0 && !unique) {
            attrs.values()[i] = value;
            return attrs;
        }
        if (i >= 0) throw new NixException("dynamic attribute '" + name + "' already defined", this);
        int at = -i - 1;
        int n = attrs.size();
        String[] k = new String[n + 1];
        Object[] v = new Object[n + 1];
        SourceSection[] p = new SourceSection[n + 1];
        System.arraycopy(attrs.keys(), 0, k, 0, at);
        System.arraycopy(attrs.values(), 0, v, 0, at);
        if (attrs.positions() != null) System.arraycopy(attrs.positions(), 0, p, 0, at);
        k[at] = name;
        v[at] = value;
        p[at] = pos;
        System.arraycopy(attrs.keys(), at, k, at + 1, n - at);
        System.arraycopy(attrs.values(), at, v, at + 1, n - at);
        if (attrs.positions() != null) System.arraycopy(attrs.positions(), at, p, at + 1, n - at);
        return new NixAttrs(k, v, p);
    }
}
