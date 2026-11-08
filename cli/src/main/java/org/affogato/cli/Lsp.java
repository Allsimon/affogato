package org.affogato.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.affogato.Session;
import org.affogato.lsp.Evaluator;
import org.affogato.lsp.Server;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "lsp", description = "Run a language server for Nix over stdin and stdout.")
public final class Lsp implements Callable<Integer> {
    private static final long WAIT_MILLIS = 200;

    @Option(
            names = "--nixpkgs",
            paramLabel = "EXPR",
            description = "Complete pkgs and lib from this expression (default: ${DEFAULT-VALUE}).")
    String nixpkgs = "import <nixpkgs> { }";

    @Option(
            names = "--nixos-options",
            paramLabel = "EXPR",
            description = "Complete the options of NixOS modules from this expression (default: ${DEFAULT-VALUE}).")
    String nixosOptions = "(import <nixpkgs/nixos> { configuration = { }; }).options";

    @Override
    public Integer call() throws IOException {
        try (Worker worker = new Worker(nixpkgs, Map.of("nixos", nixosOptions))) {
            Server.serve(System.in, System.out, worker);
        }
        return 0;
    }

    private static final class Worker implements Evaluator, AutoCloseable {
        private final Session session;
        private String expression;
        private final Map<String, Session> options = new LinkedHashMap<>();
        private Map<String, String> optionExpressions = Map.of();

        Worker(String expression, Map<String, String> optionExpressions) {
            session = new Session("", "", "", expression, dir());
            this.expression = expression;
            prefetch();
            configureOptions(optionExpressions);
        }

        private static String dir() {
            return System.getProperty("user.dir");
        }

        private void prefetch() {
            session.names(List.of());
            session.names(List.of("lib"));
        }

        @Override
        public List<String> names(List<String> path) {
            return get(session.names(path), List.of(), TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS));
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

        @Override
        public List<Attribute> describeAll(List<List<String>> paths) {
            List<Future<Session.Description>> futures = new ArrayList<>();
            for (List<String> path : paths) futures.add(session.describe(path));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            List<Attribute> attributes = new ArrayList<>();
            for (Future<Session.Description> f : futures)
                attributes.add(attribute(get(f, Session.Description.NONE, deadline - System.nanoTime())));
            return attributes;
        }

        @Override
        public synchronized List<String> optionNames(List<String> path) {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (Session s : options.values()) futures.add(s.names(path));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            TreeSet<String> names = new TreeSet<>();
            for (Future<List<String>> f : futures) {
                List<String> some = get(f, List.of(), deadline - System.nanoTime());
                if (some == null) return null;
                names.addAll(some);
            }
            return List.copyOf(names);
        }

        @Override
        public synchronized Attribute describeOption(List<String> path) {
            List<Future<Session.Description>> futures = new ArrayList<>();
            for (Session s : options.values()) futures.add(s.describe(path));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            for (Future<Session.Description> f : futures) {
                Session.Description d = get(f, Session.Description.NONE, deadline - System.nanoTime());
                if (d == null || d.type() != null) return attribute(d);
            }
            return attribute(Session.Description.NONE);
        }

        @Override
        public synchronized void configureOptions(Map<String, String> expressions) {
            if (expressions.equals(optionExpressions)) return;
            optionExpressions = Map.copyOf(expressions);
            for (Session s : options.values()) s.close();
            options.clear();
            for (Map.Entry<String, String> e : expressions.entrySet())
                options.put(e.getKey(), new Session("", "", "", e.getValue(), dir(), true));
        }

        private static Attribute attribute(Session.Description d) {
            return d == null
                    ? null
                    : new Attribute(
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
        public void configure(String expression) {
            if (expression.equals(this.expression)) return;
            this.expression = expression;
            session.configure(expression, dir());
            prefetch();
        }

        @Override
        public synchronized void reset() {
            session.reset();
            prefetch();
            for (Session s : options.values()) s.reset();
        }

        @Override
        public synchronized void close() {
            session.close();
            for (Session s : options.values()) s.close();
        }
    }
}
