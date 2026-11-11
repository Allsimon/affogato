package org.affogato.nodes;

import org.affogato.runtime.*;

public final class AttrsNode extends NixNode {
    private final Name[] keys;
    private final NixNode[] values;
    private final NixNode[] dynNames;
    private final NixNode[] dynValues;
    private final SourceSection[] dynPositions;
    private final int overridesIndex;
    private final int[] recSlots;

    /**
     * {@code keys} must be sorted; values are evaluated lazily. For a {@code rec} set defining {@code __overrides},
     * {@code overridesIndex} is its key index and {@code recSlots} the slots of the keys.
     */
    public AttrsNode(
            Name[] keys,
            NixNode[] values,
            NixNode[] dynNames,
            NixNode[] dynValues,
            SourceSection[] dynPositions,
            int overridesIndex,
            int[] recSlots) {
        this.keys = keys;
        this.values = values;
        this.dynNames = dynNames;
        this.dynValues = dynValues;
        this.dynPositions = dynPositions;
        this.overridesIndex = overridesIndex;
        this.recSlots = recSlots;
    }

    @Override
    public Object execute(Object[] frame) {
        Object[] vals = new Object[values.length];
        for (int i = 0; i < values.length; i++) vals[i] = values[i].executeLazy(frame);
        NixAttrs attrs = new NixAttrs(keys, vals);
        if (overridesIndex >= 0) attrs = applyOverrides(frame, attrs);
        for (int i = 0; i < dynNames.length; i++) {
            Object name = dynNames[i].execute(frame);
            if (name == NixNull.INSTANCE) continue;
            Name key = new Name(Types.string(name).value, dynPositions[i]);
            attrs = addDynamic(attrs, key, dynValues[i].executeLazy(frame), true);
        }
        return attrs;
    }

    /** Port of the {@code __overrides} handling in {@code ExprAttrs::eval}. */
    private NixAttrs applyOverrides(Object[] frame, NixAttrs attrs) {
        NixAttrs overrides = Types.attrs(attrs.values()[overridesIndex]);
        for (int i = 0; i < overrides.size(); i++) {
            Name k = overrides.keys()[i];
            int j = NixAttrs.search(keys, k);
            if (j >= 0) {
                attrs.values()[j] = overrides.values()[i];
                frame[recSlots[j]] = overrides.values()[i];
            } else {
                attrs = addDynamic(attrs, k.withoutPosition(), overrides.values()[i], false);
            }
        }
        return attrs;
    }

    private NixAttrs addDynamic(NixAttrs attrs, Name name, Object value, boolean unique) {
        int i = attrs.indexOf(name);
        if (i >= 0 && !unique) {
            attrs.values()[i] = value;
            return attrs;
        }
        if (i >= 0) throw new NixException("dynamic attribute '" + name.name + "' already defined", this);
        int at = -i - 1;
        int n = attrs.size();
        Name[] k = new Name[n + 1];
        Object[] v = new Object[n + 1];
        System.arraycopy(attrs.keys(), 0, k, 0, at);
        System.arraycopy(attrs.values(), 0, v, 0, at);
        k[at] = name;
        v[at] = value;
        System.arraycopy(attrs.keys(), at, k, at + 1, n - at);
        System.arraycopy(attrs.values(), at, v, at + 1, n - at);
        return new NixAttrs(k, v);
    }
}
