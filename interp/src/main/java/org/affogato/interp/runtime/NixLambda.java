package org.affogato.interp.runtime;

import org.affogato.interp.nodes.Root;

public final class NixLambda {
    public final Root target;
    public final Object[] closure;
    public final LambdaInfo info;

    public NixLambda(Root target, Object[] closure, LambdaInfo info) {
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
