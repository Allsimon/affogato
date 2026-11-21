package org.affogato;

/** Speculative forcing on helper threads, after numtide/iets (docs/explanation/evaluation.md, "The fiber scheduler"). */
public final class Parallel {
    /** Whether thunks may be forced by several threads, so that claiming one takes a compare-and-set. */
    public static volatile boolean on;

    private Parallel() {}
}
