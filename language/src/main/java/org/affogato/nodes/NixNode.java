package org.affogato.nodes;

import org.affogato.runtime.SourceSection;

public abstract class NixNode {
    private SourceSection section;

    /** Evaluates to weak head normal form: never returns a {@link org.affogato.runtime.Thunk}. */
    public abstract Object execute(Object[] frame);

    /** Evaluates for storage in a lazy position; may return a thunk instead of a value. */
    public Object executeLazy(Object[] frame) {
        return execute(frame);
    }

    public final NixNode at(SourceSection s) {
        this.section = s;
        return this;
    }

    public SourceSection getSourceSection() {
        return section;
    }
}
