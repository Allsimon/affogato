package org.affogato.runtime;

import org.affogato.nodes.NixRootNode;

public final class NixLambda {
    public final NixRootNode target;
    public final Object[] closure;
    public final LambdaInfo info;

    public NixLambda(NixRootNode target, Object[] closure, LambdaInfo info) {
        this.target = target;
        this.closure = closure;
        this.info = info;
    }

    /**
     * Formals are null for a plain {@code x: body} lambda. {@code formalNames}, sorted, and {@code formalDefaults}
     * are what {@code functionArgs} returns.
     */
    public record LambdaInfo(
            String name,
            String arg,
            String[] formals,
            boolean[] hasDefault,
            Name[] formalNames,
            Object[] formalDefaults,
            boolean ellipsis,
            SourceSection pos) {}
}
