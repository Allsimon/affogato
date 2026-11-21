package org.affogato;

/** Drops a helper's task, whose thunks the demand path then evaluates itself; {@code tryEval} cannot catch it. */
public final class HelperAbort extends RuntimeException {
    HelperAbort() {
        super("helper task dropped", null, false, false);
    }
}
