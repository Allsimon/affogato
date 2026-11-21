package org.affogato;

/** A thread that evaluates Nix: the context it evaluates in, and its own call depth. */
public final class EvalThread extends Thread {
    NixContext ctx;
    public int callDepth;

    public EvalThread(Runnable task, String name, long stackSize) {
        super(null, task, name, stackSize);
    }

    public static EvalThread current() {
        return (EvalThread) Thread.currentThread();
    }

    public NixContext context() {
        return ctx;
    }
}
