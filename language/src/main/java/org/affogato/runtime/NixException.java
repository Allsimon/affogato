package org.affogato.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

public final class NixException extends AbstractTruffleException {
    /** Whether {@code builtins.tryEval} may catch this error ({@code throw} and failed assertions). */
    public final boolean catchable;

    public NixException(String message) {
        this(message, null, false);
    }

    public NixException(String message, Node location) {
        this(message, location, false);
    }

    public NixException(String message, Node location, boolean catchable) {
        super(message, location);
        this.catchable = catchable;
    }

    public static NixException typeError(String expected, Object actual) {
        return new NixException("expected " + expected + " but found " + Types.describe(actual));
    }
}
