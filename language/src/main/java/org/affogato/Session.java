package org.affogato;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.Thunk;

/**
 * An expression evaluated on a thread of its own and kept between queries, for a long-lived client such as the
 * language server, which resets it when files change.
 */
public final class Session implements AutoCloseable {
    private static final int MAX_QUERIES = 256;

    private final String nixPath;
    private final String system;
    private final String storeDir;
    private final ExecutorService thread;
    private final Map<List<String>, CompletableFuture<List<String>>> names = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<String>, CompletableFuture<List<String>>> eldest) {
            return size() > MAX_QUERIES;
        }
    };
    private String expression;
    private String baseDir;
    private volatile NixContext ctx;
    private Object root;

    /** Empty values take the defaults, as in {@link Interpreter.Options}. */
    public Session(String nixPath, String system, String storeDir, String expression, String baseDir) {
        this.nixPath = nixPath;
        this.system = system;
        this.storeDir = storeDir;
        this.expression = expression;
        this.baseDir = baseDir;
        thread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(null, r, "nix-session", Interpreter.STACK_SIZE);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The sorted attribute names of the value at {@code path} in the expression, auto-called at each step as
     * {@code nix-instantiate} does; empty when it is no attribute set or fails to evaluate.
     */
    public synchronized CompletableFuture<List<String>> names(List<String> path) {
        List<String> key = List.copyOf(path);
        CompletableFuture<List<String>> f = names.get(key);
        if (f == null) {
            f = CompletableFuture.supplyAsync(() -> evaluate(key), thread);
            names.put(key, f);
        }
        return f;
    }

    /** Evaluates {@code expression}, relative to {@code baseDir}, from now on. */
    public synchronized void configure(String expression, String baseDir) {
        this.expression = expression;
        this.baseDir = baseDir;
        reset();
    }

    /** Drops every value, stopping the evaluation under way, so that the next query reads the files again. */
    public synchronized void reset() {
        names.clear();
        NixContext c = ctx;
        if (c != null) c.cancelled = true;
        thread.execute(() -> ctx = null);
    }

    private List<String> evaluate(List<String> path) {
        String source;
        String dir;
        synchronized (this) {
            source = expression;
            dir = baseDir;
        }
        try {
            if (ctx == null) {
                NixContext c = new NixContext(storeDir, system, nixPath);
                ctx = c;
                c.enter();
                root = null;
                root = Interpreter.autoCall(
                        Thunk.force(c.parse(source, NixContext.STRING_SOURCE, dir, new String[0])
                                .call(null, null)),
                        Map.of());
            } else {
                ctx.enter();
                if (root == null) return List.of();
            }
            Object v = root;
            for (String key : path) {
                if (!(Thunk.force(v) instanceof NixAttrs a) || (v = a.get(key)) == null) return List.of();
                v = Interpreter.autoCall(v, Map.of());
            }
            return Thunk.force(v) instanceof NixAttrs a ? List.of(a.keys()) : List.of();
        } catch (NixException | StackOverflowError e) {
            return List.of();
        } finally {
            NixContext.leave();
        }
    }

    @Override
    public void close() {
        NixContext c = ctx;
        if (c != null) c.cancelled = true;
        thread.shutdownNow();
    }
}
