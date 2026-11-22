package org.affogato.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Runs the programs an update needs, killed with what they started when the thread waiting for them is interrupted. */
final class Processes {
    private Processes() {}

    /** What a program printed, and its exit status. */
    record Result(int status, String out, String err) {
        /** The last line it printed on its error output, else on its output. */
        String last() {
            String text = err.isBlank() ? out.strip() : err.strip();
            return text.substring(text.lastIndexOf('\n') + 1);
        }
    }

    static Result run(List<String> command, Path dir, Map<String, String> env, boolean mergeErrors)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(mergeErrors);
        if (dir != null) builder.directory(dir.toFile());
        if (env != null) builder.environment().putAll(env);
        Process p;
        try {
            p = builder.start();
        } catch (IOException e) {
            throw new IOException(command.getFirst() + " not found" + hint(command.getFirst()), e);
        }
        try {
            p.getOutputStream().close();
            CompletableFuture<String> out = read(p.getInputStream());
            CompletableFuture<String> err =
                    mergeErrors ? CompletableFuture.completedFuture("") : read(p.getErrorStream());
            int status = p.waitFor();
            return new Result(status, out.get(), err.get());
        } catch (InterruptedException e) {
            kill(p);
            throw e;
        } catch (ExecutionException e) {
            kill(p);
            throw e.getCause() instanceof UncheckedIOException u ? u.getCause() : new IOException(e.getCause());
        }
    }

    private static String hint(String program) {
        return switch (Path.of(program).getFileName().toString()) {
            case "nix", "nix-build", "nix-prefetch-url", "nix-instantiate" -> ": it comes with Nix";
            default -> "";
        };
    }

    private static CompletableFuture<String> read(InputStream in) {
        CompletableFuture<String> out = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (in) {
                out.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                out.completeExceptionally(new UncheckedIOException(e));
            }
        });
        return out;
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
