package org.affogato.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class Git {
    private Git() {}

    /** Why {@code path}, a file or a directory, cannot be committed on its own, or null when it can. */
    static String dirty(Path path) throws IOException, InterruptedException {
        Path abs = path.toAbsolutePath().normalize();
        String status = run(
                abs.getParent(),
                "status",
                "--porcelain",
                "--",
                abs.getFileName().toString());
        if (status.isEmpty()) return null;
        return status.startsWith("??") ? "not tracked by git" : "uncommitted changes";
    }

    /** Commits {@code paths}, files or directories whose new files it adds, and nothing else. */
    static synchronized void commit(List<Path> paths, String message) throws IOException, InterruptedException {
        Path dir = paths.getFirst().toAbsolutePath().normalize().getParent();
        List<String> names = new ArrayList<>();
        for (Path p : paths)
            names.add(dir.relativize(p.toAbsolutePath().normalize()).toString());
        List<String> add = new ArrayList<>(List.of("add", "-A", "--"));
        add.addAll(names);
        run(dir, add.toArray(String[]::new));
        List<String> commit = new ArrayList<>(List.of("commit", "--quiet", "--only", "-m", message, "--"));
        commit.addAll(names);
        run(dir, commit.toArray(String[]::new));
    }

    private static String run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.waitFor() != 0) throw new IOException("git " + args[0] + ": " + out);
        return out;
    }
}
