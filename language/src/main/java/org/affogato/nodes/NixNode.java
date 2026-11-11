package org.affogato.nodes;

import org.affogato.runtime.Source;
import org.affogato.runtime.SourceSection;

public abstract class NixNode {
    private Source source;
    private int offset;

    /** Evaluates to weak head normal form: never returns a {@link org.affogato.runtime.Thunk}. */
    public abstract Object execute(Object[] frame);

    /** Evaluates for storage in a lazy position; may return a thunk instead of a value. */
    public Object executeLazy(Object[] frame) {
        return execute(frame);
    }

    public final NixNode at(Source source, int offset) {
        this.source = source;
        this.offset = offset;
        return this;
    }

    public SourceSection getSourceSection() {
        return source == null ? null : source.createSection(offset, 0);
    }
}
