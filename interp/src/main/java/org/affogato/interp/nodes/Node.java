package org.affogato.interp.nodes;

import org.affogato.interp.runtime.SourceSection;

public abstract class Node {
    private SourceSection section;

    /** Evaluates to weak head normal form: never returns a {@link org.affogato.interp.runtime.Thunk}. */
    public abstract Object execute(Object[] frame);

    /** Evaluates for storage in a lazy position; may return a thunk instead of a value. */
    public Object executeLazy(Object[] frame) {
        return execute(frame);
    }

    public final Node at(SourceSection s) {
        this.section = s;
        return this;
    }

    public SourceSection getSourceSection() {
        return section;
    }
}
