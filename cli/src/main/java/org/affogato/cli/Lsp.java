package org.affogato.cli;

import java.io.IOException;
import java.util.List;
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

    @Override
    public Integer call() throws IOException {
        try (Session session = new Session("", "", "", nixpkgs, System.getProperty("user.dir"))) {
            Server.serve(System.in, System.out, new Nixpkgs(session, nixpkgs));
        }
        return 0;
    }

    private static final class Nixpkgs implements Evaluator {
        private final Session session;
        private String expression;

        Nixpkgs(Session session, String expression) {
            this.session = session;
            this.expression = expression;
            prefetch();
        }

        private void prefetch() {
            session.names(List.of());
            session.names(List.of("lib"));
        }

        @Override
        public List<String> names(List<String> path) {
            return get(session.names(path), List.of());
        }

        /** The value, {@code failed} when its evaluation broke off, null when it takes longer than a request may wait. */
        private static <T> T get(Future<T> future, T failed) {
            try {
                return future.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
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
        public Attribute describe(List<String> path) {
            Session.Description d = get(session.describe(path), Session.Description.NONE);
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
                            d.file(),
                            d.line(),
                            d.column());
        }

        @Override
        public void configure(String expression) {
            if (expression.equals(this.expression)) return;
            this.expression = expression;
            session.configure(expression, System.getProperty("user.dir"));
            prefetch();
        }

        @Override
        public void reset() {
            session.reset();
            prefetch();
        }
    }
}
