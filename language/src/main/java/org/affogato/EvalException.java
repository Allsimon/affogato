package org.affogato;

/** An evaluation error, as Nix reports it: {@code throw}, {@code abort}, a type error, a timeout. */
public final class EvalException extends Exception {
    /** {@code file:line:column} of the expression that failed, or null. */
    public final String location;

    EvalException(String message, String location) {
        super(message, null, false, false);
        this.location = location;
    }
}
