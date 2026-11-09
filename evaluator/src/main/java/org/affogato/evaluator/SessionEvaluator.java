package org.affogato.evaluator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.affogato.Session;
import org.affogato.lsp.Evaluator;
import org.affogato.lsp.Json;

/**
 * nixpkgs and option sets evaluated in a {@link Session} of its own, on one thread; {@link #main} answers for the
 * expressions given, and its {@link Evaluator#project} for those of a project.
 */
public final class SessionEvaluator implements AutoCloseable {
    private static final long WAIT_MILLIS = 200;
    private static final long DEVENV_MILLIS = 2000;

    private static final String NIXPKGS = "nixpkgs";
    /** Prefixes the name of each option set, which the client chooses, to keep it apart from nixpkgs. */
    private static final String OPTIONS = "options.";
    /** Prefixes the option sets of each project, apart from the client's. */
    private static final String PROJECT = "project ";

    private final Session session;
    private final String directory;
    private final Function<String, String> printConfig;
    private String expression;
    private Map<String, String> optionExpressions;
    private final Map<String, Map<String, String>> projects = new LinkedHashMap<>();
    private final Map<String, View> views = new HashMap<>();
    private final View main = new View(null);

    /**
     * Evaluates {@code expression} as nixpkgs, and each of {@code optionExpressions} as the options of an option set
     * by name, relative to {@code directory}, with {@code nixPath} as {@code NIX_PATH} when not empty; and asks
     * {@code printConfig} for the output of {@code devenv lsp --print-config} in the root of a devenv project, or null.
     */
    public SessionEvaluator(
            String expression,
            Map<String, String> optionExpressions,
            String directory,
            String nixPath,
            Function<String, String> printConfig) {
        this.session = new Session(nixPath, "", "");
        this.directory = directory;
        this.printConfig = printConfig;
        this.expression = expression;
        this.optionExpressions = Collections.unmodifiableMap(new LinkedHashMap<>(optionExpressions));
        configureSession();
    }

    /** The evaluator of the expressions given, whose {@link Evaluator#project} gives those of projects. */
    public Evaluator main() {
        return main;
    }

    private void configureSession() {
        Map<String, Session.Root> roots = new LinkedHashMap<>();
        roots.put(NIXPKGS, new Session.Root(expression, false));
        for (Map.Entry<String, String> e : optionExpressions.entrySet())
            roots.put(OPTIONS + e.getKey(), new Session.Root(e.getValue(), true));
        projects.forEach((key, sets) ->
                sets.forEach((name, e) -> roots.put(PROJECT + key + "/" + name, new Session.Root(e, true))));
        session.configure(roots, directory);
        prefetch();
    }

    private void prefetch() {
        prefetched = List.of(names(NIXPKGS, List.of()), names(NIXPKGS, List.of("lib")));
    }

    private List<CompletableFuture<List<String>>> prefetched = List.of();
    private volatile Evaluator.Progress progress;
    /** The futures under way for each title shown, and the end of each. */
    private final Map<String, Set<Future<?>>> working = new HashMap<>();

    private final Map<String, Runnable> ends = new HashMap<>();

    private CompletableFuture<List<String>> names(String root, List<String> path) {
        return track("Evaluating " + title(root), session.names(root, path), true);
    }

    private CompletableFuture<Session.Description> describe(String root, List<String> path) {
        return track("Evaluating " + title(root), session.describe(root, path), true);
    }

    private static String title(String root) {
        String name = root.startsWith(Session.arguments(""))
                ? root.substring(Session.arguments("").length())
                : root;
        if (name.startsWith(OPTIONS)) return "the " + name.substring(OPTIONS.length()) + " options";
        if (!name.startsWith(PROJECT)) return name;
        String project = name.substring(PROJECT.length(), name.lastIndexOf('/'));
        return "the " + name.substring(name.lastIndexOf('/') + 1) + " options of "
                + project.substring(project.lastIndexOf('/') + 1);
    }

    /** Shows {@code title} while {@code future} runs, once it has run longer than a request waits when {@code late}. */
    private <T> CompletableFuture<T> track(String title, CompletableFuture<T> future, boolean late) {
        if (progress == null || future.isDone()) return future;
        if (!late) begin(title, future);
        else
            CompletableFuture.delayedExecutor(WAIT_MILLIS, TimeUnit.MILLISECONDS)
                    .execute(() -> begin(title, future));
        return future;
    }

    private void begin(String title, CompletableFuture<?> future) {
        Evaluator.Progress p = progress;
        if (p == null || future.isDone()) return;
        Set<Future<?>> under;
        synchronized (working) {
            under = working.computeIfAbsent(title, t -> new HashSet<>());
            if (!under.add(future)) return;
            if (under.size() == 1) ends.put(title, p.begin(title));
        }
        future.whenComplete((v, e) -> {
            Runnable end = null;
            synchronized (working) {
                if (under.remove(future) && under.isEmpty()) {
                    working.remove(title);
                    end = ends.remove(title);
                }
            }
            if (end != null) end.run();
        });
    }

    synchronized void progress(Evaluator.Progress progress) {
        this.progress = progress;
        for (CompletableFuture<?> f : prefetched) track("Evaluating nixpkgs", f, false);
    }

    /** What {@code devenv lsp --print-config} gave for a project, while its files are as they were then. */
    private record Devenv(long yaml, long lock, CompletableFuture<String> options) {}

    private final Map<String, Devenv> devenv = new HashMap<>();

    /** The devenv CLI's options of the project at {@code root}, waiting for it on the first call alone. */
    synchronized String devenvOptions(String root) {
        long yaml = new File(root, "devenv.yaml").lastModified();
        long lock = new File(root, "devenv.lock").lastModified();
        Devenv known = devenv.get(root);
        if (known != null && known.yaml() == yaml && known.lock() == lock)
            return known.options().getNow(null);
        CompletableFuture<String> options = track(
                "Running devenv lsp --print-config", CompletableFuture.supplyAsync(() -> devenvExpression(root)), true);
        devenv.put(root, new Devenv(yaml, lock, options));
        return get(options, null, TimeUnit.MILLISECONDS.toNanos(DEVENV_MILLIS));
    }

    private String devenvExpression(String root) {
        String out = printConfig.apply(root);
        try {
            return out != null
                            && Json.parse(out) instanceof Map<?, ?> config
                            && config.get("nixd") instanceof Map<?, ?> nixd
                            && nixd.get("options") instanceof Map<?, ?> options
                            && options.get("devenv") instanceof Map<?, ?> set
                            && set.get("expr") instanceof String expr
                    ? expr
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The output of {@code devenv lsp --print-config} run in {@code root} by the {@code devenv} on the path, or null. */
    public static String printConfig(String root) {
        try {
            Process process = new ProcessBuilder("devenv", "lsp", "--print-config")
                    .directory(new File(root))
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean isArgument(List<String> path) {
        return !path.isEmpty() && path.getFirst().equals(Evaluator.MODULE_ARGUMENTS);
    }

    /** The value, {@code failed} when its evaluation broke off, null when it takes longer than a request may wait. */
    private static <T> T get(Future<T> future, T failed, long nanos) {
        try {
            return future.get(Math.max(nanos, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (ExecutionException e) {
            return failed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The sorted union of the names, or null when one takes longer than a request may wait. */
    private static List<String> union(List<Future<List<String>>> futures) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        TreeSet<String> names = new TreeSet<>();
        for (Future<List<String>> f : futures) {
            List<String> some = get(f, List.of(), deadline - System.nanoTime());
            if (some == null) return null;
            names.addAll(some);
        }
        return List.copyOf(names);
    }

    /** The client's option sets and nixpkgs when {@code key} is null, else those of the project {@code key}. */
    private final class View implements Evaluator {
        private final String key;

        View(String key) {
            this.key = key;
        }

        private List<String> optionRoots() {
            List<String> roots = new ArrayList<>();
            if (key == null) for (String name : optionExpressions.keySet()) roots.add(OPTIONS + name);
            else for (String name : projects.get(key).keySet()) roots.add(PROJECT + key + "/" + name);
            return roots;
        }

        /** Where nixpkgs is looked up: its root, or the {@code pkgs} argument of the project's modules. */
        private <T> CompletableFuture<T> nixpkgs(
                List<String> path, BiFunction<String, List<String>, CompletableFuture<T>> query) {
            List<String> roots = optionRoots();
            if (key == null || roots.isEmpty()) return query.apply(NIXPKGS, path);
            List<String> pkgs = new ArrayList<>(path);
            pkgs.addFirst("pkgs");
            return query.apply(Session.arguments(roots.getFirst()), pkgs);
        }

        @Override
        public List<String> names(List<String> path) {
            synchronized (SessionEvaluator.this) {
                if (!isArgument(path))
                    return get(
                            nixpkgs(path, SessionEvaluator.this::names),
                            List.of(),
                            TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS));
                List<Future<List<String>>> futures = new ArrayList<>();
                for (String root : optionRoots())
                    futures.add(SessionEvaluator.this.names(Session.arguments(root), path.subList(1, path.size())));
                return union(futures);
            }
        }

        /** The descriptions to look the path up in, in order: nixpkgs, or the arguments of each option set. */
        private List<CompletableFuture<Session.Description>> lookups(List<String> path) {
            if (!isArgument(path)) return List.of(nixpkgs(path, SessionEvaluator.this::describe));
            List<CompletableFuture<Session.Description>> lookups = new ArrayList<>();
            for (String root : optionRoots())
                lookups.add(SessionEvaluator.this.describe(Session.arguments(root), path.subList(1, path.size())));
            return lookups;
        }

        @Override
        public List<Attribute> describeAll(List<List<String>> paths) {
            synchronized (SessionEvaluator.this) {
                List<List<CompletableFuture<Session.Description>>> lookups = new ArrayList<>();
                for (List<String> path : paths) lookups.add(lookups(path));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
                List<Attribute> attributes = new ArrayList<>();
                for (List<CompletableFuture<Session.Description>> futures : lookups) {
                    Session.Description found = Session.Description.NONE;
                    for (Future<Session.Description> f : futures) {
                        found = get(f, Session.Description.NONE, deadline - System.nanoTime());
                        if (found == null || found.type() != null) break;
                    }
                    attributes.add(attribute(found));
                }
                return attributes;
            }
        }

        @Override
        public void whenDescribed(List<List<String>> paths, Runnable then) {
            synchronized (SessionEvaluator.this) {
                CompletableFuture.allOf(paths.stream()
                                .flatMap(path -> lookups(path).stream())
                                .toArray(CompletableFuture[]::new))
                        .whenComplete((v, e) -> then.run());
            }
        }

        @Override
        public List<String> optionNames(List<String> path) {
            synchronized (SessionEvaluator.this) {
                List<Future<List<String>>> futures = new ArrayList<>();
                for (String root : optionRoots()) futures.add(SessionEvaluator.this.names(root, path));
                return union(futures);
            }
        }

        @Override
        public Attribute describeOption(List<String> path) {
            synchronized (SessionEvaluator.this) {
                List<Future<Session.Description>> futures = new ArrayList<>();
                for (String root : optionRoots()) futures.add(SessionEvaluator.this.describe(root, path));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
                for (Future<Session.Description> f : futures) {
                    Session.Description d = get(f, Session.Description.NONE, deadline - System.nanoTime());
                    if (d == null || d.type() != null) return attribute(d);
                }
                return attribute(Session.Description.NONE);
            }
        }

        @Override
        public void configureOptions(Map<String, String> expressions) {
            synchronized (SessionEvaluator.this) {
                if (expressions.equals(optionExpressions)) return;
                optionExpressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
                configureSession();
            }
        }

        @Override
        public void progress(Evaluator.Progress progress) {
            SessionEvaluator.this.progress(progress);
        }

        @Override
        public String devenvOptions(String root) {
            return SessionEvaluator.this.devenvOptions(root);
        }

        @Override
        public Evaluator project(String key, Map<String, String> options) {
            synchronized (SessionEvaluator.this) {
                if (!options.equals(projects.get(key))) {
                    projects.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(options)));
                    configureSession();
                }
                return views.computeIfAbsent(key, View::new);
            }
        }

        @Override
        public void configure(String expression) {
            synchronized (SessionEvaluator.this) {
                if (expression.equals(SessionEvaluator.this.expression)) return;
                SessionEvaluator.this.expression = expression;
                configureSession();
            }
        }

        @Override
        public void reset() {
            synchronized (SessionEvaluator.this) {
                session.reset();
                prefetch();
            }
        }

        @Override
        public void saved(String path) {
            synchronized (SessionEvaluator.this) {
                if (session.reset(path)) prefetch();
            }
        }
    }

    private static Evaluator.Attribute attribute(Session.Description d) {
        return d == null
                ? null
                : new Evaluator.Attribute(
                        d.type(),
                        d.value(),
                        d.name(),
                        d.version(),
                        d.description(),
                        d.homepage(),
                        d.doc(),
                        d.defaultValue(),
                        d.example(),
                        d.file(),
                        d.line(),
                        d.column());
    }

    @Override
    public void close() {
        session.close();
    }
}
