package org.affogato.runtime;

import org.affogato.nodes.NixRootNode;

public final class NixLambda {
    public final NixRootNode target;
    public final Object[] closure;

    public NixLambda(NixRootNode target, Object[] closure) {
        this.target = target;
        this.closure = closure;
    }

    public LambdaInfo info() {
        return target.info;
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
