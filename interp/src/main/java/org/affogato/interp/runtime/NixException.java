package org.affogato.interp.runtime;

import org.affogato.interp.nodes.Node;

public final class NixException extends RuntimeException {
    /** Whether {@code builtins.tryEval} may catch this error ({@code throw} and failed assertions). */
    public final boolean catchable;

    public final transient Node location;

    public NixException(String message) {
        this(message, null, false);
    }

    public NixException(String message, Node location) {
        this(message, location, false);
    }

    /** Without a stack trace: nixpkgs throws many through {@code tryEval}, at Java stack depths in the thousands. */
    public NixException(String message, Node location, boolean catchable) {
        super(message, null, false, false);
        this.location = location;
        this.catchable = catchable;
    }

    public static NixException typeError(String expected, Object actual) {
        return new NixException("expected " + expected + " but found " + Types.describe(actual));
    }
}
