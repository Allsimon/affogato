package org.affogato;

import java.util.ArrayDeque;
import org.affogato.runtime.Thunk;

/**
 * Forces the cells of the containers a strict consumer walks on helper threads, ahead of demand, after numtide/iets
 * (docs/explanation/evaluation.md, "The fiber scheduler"). Only the demand path reports errors: a helper drops a task
 * that fails, traces or waits in a cycle, and the thunks it held go back to unevaluated.
 */
public final class Parallel {
    /** Whether thunks may be forced by several threads, so that claiming one takes a compare-and-set. */
    public static volatile boolean on;

    private static final int BATCH = 16;
    private static final int MIN_CELLS = 4;
    private static final int MAX_CHAIN = 64;

    /** {@code depth} bounds the helper's calls as tightly as the demand path's, or one call more tightly. */
    private record Task(Object[] cells, int from, int to, int depth) {}

    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private final EvalThread[] helpers;
    private int idle;
    private boolean stopped;

    Parallel(NixContext ctx, int workers, long stackSize) {
        on = true;
        helpers = new EvalThread[workers - 1];
        for (int i = 0; i < helpers.length; i++) {
            EvalThread h = new EvalThread(this::work, "nix-eval-" + (i + 1), stackSize, true);
            h.ctx = ctx;
            h.setDaemon(true);
            helpers[i] = h;
        }
    }

    void start() {
        for (EvalThread h : helpers) h.start();
    }

    void stop() {
        synchronized (this) {
            stopped = true;
            tasks.clear();
            notifyAll();
        }
    }

    /** Hands the unclaimed thunks among the first {@code n} {@code cells} to the helpers. */
    public static void fanout(Object[] cells, int n) {
        if (!on || n < MIN_CELLS) return;
        if (!(Thread.currentThread() instanceof EvalThread t) || t.ctx == null) return;
        Parallel p = t.ctx.pool;
        if (p != null) p.submit(cells, n, t.callDepth + 1);
    }

    private void submit(Object[] cells, int n, int depth) {
        ArrayDeque<Task> batch = null;
        for (int i = 0; i < n; i += BATCH) {
            int end = Math.min(n, i + BATCH);
            for (int j = i; j < end; j++) {
                if (cells[j] instanceof Thunk t && t.idle()) {
                    if (batch == null) batch = new ArrayDeque<>();
                    batch.add(new Task(cells, i, end, depth));
                    break;
                }
            }
        }
        if (batch == null) return;
        synchronized (this) {
            if (stopped) return;
            tasks.addAll(batch);
            if (idle > 0) notifyAll();
        }
    }

    private void work() {
        EvalThread me = EvalThread.current();
        for (; ; ) {
            Task task;
            synchronized (this) {
                while (!stopped && tasks.isEmpty()) {
                    idle++;
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    } finally {
                        idle--;
                    }
                }
                if (stopped) return;
                task = tasks.pollLast();
            }
            me.abort = false;
            me.callDepth = task.depth;
            try {
                for (int i = task.from; i < task.to; i++) {
                    if (task.cells[i] instanceof Thunk t && t.idle()) t.force();
                }
            } catch (RuntimeException | StackOverflowError e) {
                // The demand path evaluates these thunks again, and reports the errors in order.
            } finally {
                me.waitingOn = null;
            }
        }
    }

    /** Whether the current thread is a helper, whose effects would come out of order. */
    public static boolean helper() {
        return on && Thread.currentThread() instanceof EvalThread t && t.helper;
    }

    /** Drops the current helper's task. */
    public static void abort() {
        throw new HelperAbort();
    }

    /**
     * Notes that the current thread waits for {@code owner} to evaluate {@code thunk}, and breaks a cycle of threads
     * waiting for each other: a helper in one drops its task; the demand path has the helper it waits for drop its own.
     */
    public static void waiting(Thunk thunk, Thread owner) {
        if (!(Thread.currentThread() instanceof EvalThread me)) return;
        if (me.abort) {
            me.waitingOn = null;
            throw new HelperAbort();
        }
        me.waitingOn = thunk;
        Thread o = owner;
        for (int i = 0; i < MAX_CHAIN && o instanceof EvalThread e; i++) {
            if (e == me) {
                if (me.helper) {
                    me.waitingOn = null;
                    throw new HelperAbort();
                }
                if (owner instanceof EvalThread h && h.helper) {
                    h.abort = true;
                    Thunk.wakeAll();
                }
                return;
            }
            Thunk next = e.waitingOn;
            if (next == null) return;
            o = next.owner();
        }
    }

    /** The current thread waits no more. */
    public static void waited() {
        if (Thread.currentThread() instanceof EvalThread me) me.waitingOn = null;
    }
}
