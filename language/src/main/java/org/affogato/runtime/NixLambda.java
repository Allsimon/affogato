package org.affogato.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.source.SourceSection;

public final class NixLambda {
    public final RootCallTarget target;
    public final MaterializedFrame closure;
    public final LambdaInfo info;

    public NixLambda(RootCallTarget target, MaterializedFrame closure, LambdaInfo info) {
        this.target = target;
        this.closure = closure;
        this.info = info;
    }

    /** Formals are null for a plain {@code x: body} lambda. */
    public record LambdaInfo(
            String name,
            String arg,
            String[] formals,
            boolean[] hasDefault,
            SourceSection[] formalPositions,
            boolean ellipsis,
            SourceSection pos) {}
}
