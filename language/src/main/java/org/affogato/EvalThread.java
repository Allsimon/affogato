package org.affogato;

import org.affogato.runtime.Thunk;

/** A thread that evaluates Nix: the context it evaluates in, and its own call depth. */
public final class EvalThread extends Thread {
    NixContext ctx;
    public int callDepth;

    /** Whether it forces thunks ahead of demand, for {@link Parallel}, rather than for the value asked. */
    final boolean helper;

    /** The thunk it waits for another thread to evaluate. */
    volatile Thunk waitingOn;

    /** Set by the demand path when this helper waits in a cycle with it. */
    volatile boolean abort;

    /** Whether it is a helper waiting for a thunk, which a spare helper stands in for meanwhile. */
    boolean blocked;

    public EvalThread(Runnable task, String name, long stackSize) {
        this(task, name, stackSize, false);
    }

    EvalThread(Runnable task, String name, long stackSize, boolean helper) {
        super(null, task, name, stackSize);
        this.helper = helper;
    }

    public static EvalThread current() {
        return (EvalThread) Thread.currentThread();
    }

    public NixContext context() {
        return ctx;
    }
}
