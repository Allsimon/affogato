package org.affogato.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.affogato.lint.Edit;
import org.affogato.lint.Packages;
import org.affogato.lint.Packages.Package;
import org.affogato.lsp.Updates;
import org.affogato.parser.ParseException;
import org.affogato.parser.Parser;
import org.affogato.update.Updater;
import org.affogato.update.Workspace;
import org.affogato.update.Workspace.Found;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "update",
        description = "Update the packages fetched from a forge, a registry or a URL to their latest version: their"
                + " version, their hashes and their lock files.",
        exitCodeListHeading = "%nExit status%n",
        exitCodeList = {
            "0:Every package was checked, and updated unless --check is given.",
            "1:A package failed, or one has an update under --check.",
            "2:The command line is invalid."
        },
        footerHeading = "%nPackages%n",
        footer = {
            "A package is a call of fetchFromGitHub, fetchFromGitLab, fetchFromGitea, fetchFromCodeberg,"
                    + " fetchFromSourcehut or fetchFromBitbucket whose tag is a string, or follows a version binding"
                    + " as in rev = \"v$${version}\"; of fetchPypi or fetchCrate; or of fetchurl or fetchzip whose URL"
                    + " has the version. Its newest version is the latest release of the repository, else its newest"
                    + " tag, of the same shape as the current tag; the newest version on PyPI, crates.io, npm,"
                    + " RubyGems or Hackage; else the newest file of the directory listing of the URL. Pre-releases"
                    + " count only when the current version is one. A package pinned to a commit, whose version is"
                    + " 0-unstable-YYYY-MM-DD, moves to the head of the default branch. The hash of a source is that"
                    + " of nix-prefetch-url; with fetchSubmodules or another option that changes it, the source is"
                    + " built with lib.fakeHash.",
            "",
            "A hash of dependencies, such as cargoHash, vendorHash or the hash of cargoDeps = fetchCargoVendor { },"
                    + " is set to lib.fakeHash, then to the hash Nix reports when it builds what the hash is of: the"
                    + " package of the flake whose attribute names it, else nixpkgs' callPackage of its file. That"
                    + " nixpkgs is the checkout the file is in, else --nixpkgs, else <nixpkgs>. The Cargo.lock of"
                    + " cargoLock.lockFile is copied from the new source; the nugetDeps of buildDotnetModule and the"
                    + " data of gradle.fetchDeps are written by their fetch-deps and updateScript. When a build fails,"
                    + " the files are left as they were.",
            "",
            "A package with a passthru.updateScript of its own, rather than nix-update-script or gitUpdater, is"
                    + " updated by running it, unless --no-update-script is given.",
            "",
            "GITHUB_TOKEN, else GH_TOKEN, authenticates the requests to GitHub, whose rate limit is otherwise 60"
                    + " an hour."
        })
final class Update implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Parameters(
            paramLabel = "PATH|NAME",
            description = "Files, or directories to search for .nix files, . by default; and the names of the"
                    + " packages to update, every one by default.")
    List<String> arguments = List.of();

    @Option(
            names = {"-c", "--check"},
            description = "Write nothing, and fail if a package has an update, or with --refresh a hash that is wrong.")
    boolean check;

    @Option(
            names = "--refresh",
            description = "Refresh the hashes of each package at the version it has, or the commit it is pinned to,"
                    + " instead of updating it.")
    boolean refresh;

    @Option(
            names = "--branch",
            description = "Also update the packages pinned to a commit whose version is not 0-unstable-YYYY-MM-DD to"
                    + " the head of their default branch, as VERSION-unstable-YYYY-MM-DD.")
    boolean branch;

    @Option(
            names = "--build",
            description = "Build each package updated before writing it, and leave it as it was when the build fails.")
    boolean build;

    @Option(
            names = "--test",
            description = "Build the passthru.tests of each package updated as well, as --build does.")
    boolean test;

    @Option(
            names = "--no-update-script",
            description = "Update the packages that have a passthru.updateScript as the others, instead of running it.")
    boolean noUpdateScript;

    @Option(
            names = {"-j", "--jobs"},
            paramLabel = "N",
            description = "Check, and update, N packages at a time; 8 by default.")
    int jobs = 8;

    @Option(
            names = {"-v", "--verbose"},
            description = "Also list the packages that are up to date or skipped.")
    boolean verbose;

    @Option(
            names = "--github-api",
            paramLabel = "URL",
            description = "GitHub's API; https://api.github.com by default.")
    String api = "https://api.github.com";

    @Option(
            names = "--no-eval",
            description = "Do not evaluate the flake.nix of a directory, whose package attribute names are otherwise"
                    + " used.")
    boolean noEval;

    @Option(
            names = "--no-build",
            description = "Build nothing to update the hashes of dependencies, such as cargoHash, and the lock files;"
                    + " list them instead.")
    boolean noBuild;

    @Option(
            names = "--nixpkgs",
            paramLabel = "PATH",
            description = "The nixpkgs whose callPackage builds the dependencies of a package outside a flake and a"
                    + " nixpkgs checkout.")
    Path nixpkgs;

    @Option(names = "--prefetcher", hidden = true)
    String prefetcher = "nix-prefetch-url";

    @Option(names = "--nix-build", hidden = true)
    String nixBuild = "nix-build";

    @Option(names = "--nix", hidden = true)
    String nix = "nix";

    /** Stands for a host's URL, as {@code gitlab.com=http://localhost:1234}. */
    @Option(names = "--host", hidden = true)
    Map<String, String> hosts = new LinkedHashMap<>();

    private sealed interface Outcome {}

    private record Newer(Updater.Newer to, List<String> hashes) implements Outcome {}

    /** To be updated by its update script. */
    private record Script() implements Outcome {}

    /** Updated; {@code how} says to what. */
    private record Updated(String how) implements Outcome {}

    private record Current() implements Outcome {}

    private record Skipped(String why) implements Outcome {}

    private record Failed(String why) implements Outcome {}

    @Override
    public Integer call() throws IOException, InterruptedException {
        PrintWriter err = spec.commandLine().getErr();
        if (jobs < 1) {
            err.println("error: --jobs must be at least 1");
            return 2;
        }
        List<Path> paths = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (String a : arguments) {
            if (Files.exists(Path.of(a))) paths.add(Path.of(a));
            else names.add(a);
        }
        if (paths.isEmpty()) paths.add(Path.of("."));
        long start = System.nanoTime();
        Status line =
                new Status(err, System.console() != null && System.console().isTerminal());
        int failed = 0;
        Workspace.Discovery discovery = Workspace.find(paths, !noEval, p -> {
            try {
                return Sources.read(p);
            } catch (IOException e) {
                throw new IOException(Sources.message(e), e);
            }
        });
        for (Workspace.Problem problem : discovery.problems()) {
            if (problem.warning()) err.println(problem.file() + ": warning: " + problem.message());
            else if (problem.text() == null) err.println("error: " + problem.message());
            else if (problem.offset() >= 0)
                err.println(problem.file() + ":" + Sources.position(problem.text(), problem.offset()) + ": error: "
                        + problem.message());
            else err.println(problem.file() + ": error: " + problem.message());
            if (!problem.warning()) failed++;
        }
        List<Found> found = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        for (Found f : discovery.found()) {
            if (!Workspace.named(f, List.copyOf(names))) continue;
            found.add(f);
            for (String n : names) if (Workspace.named(f, List.of(n))) matched.add(n);
        }
        for (String n : names) {
            if (matched.contains(n)) continue;
            err.println("error: no package or path named " + n);
            failed++;
        }

        Updater.Options defaults = Updater.Options.defaults();
        Updater updater = new Updater(
                new Updater.Options(api, defaults.githubToken(), prefetcher, nixBuild, nix, nixpkgs, hosts));
        List<Outcome> outcomes = new ArrayList<>();
        try (ExecutorService pool =
                Executors.newFixedThreadPool(jobs, Thread.ofVirtual().factory())) {
            List<Future<Outcome>> futures = new ArrayList<>();
            AtomicInteger checked = new AtomicInteger();
            String checking = refresh ? "fetching " : "checking ";
            for (Found f : found) {
                futures.add(pool.submit(() -> {
                    try {
                        return check(f, updater);
                    } finally {
                        line.show(checking + checked.incrementAndGet() + "/" + found.size());
                    }
                }));
            }
            for (Future<Outcome> f : futures) outcomes.add(result(f));
        }

        List<List<String>> left = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) left.add(List.of());
        Map<Path, List<Integer>> byFile = new LinkedHashMap<>();
        for (int i = 0; i < found.size(); i++) {
            Outcome o = outcomes.get(i);
            if (o instanceof Newer && (!check || refresh) || o instanceof Script)
                byFile.computeIfAbsent(found.get(i).file(), k -> new ArrayList<>())
                        .add(i);
        }
        try (ExecutorService pool =
                Executors.newFixedThreadPool(jobs, Thread.ofVirtual().factory())) {
            List<Future<?>> futures = new ArrayList<>();
            for (List<Integer> indices : byFile.values()) {
                futures.add(pool.submit(() -> {
                    for (int i : indices) {
                        List<String> notUpdated = new ArrayList<>();
                        Outcome done;
                        try {
                            done = apply(found.get(i), outcomes.get(i), updater, notUpdated, line);
                        } catch (RuntimeException e) {
                            done = new Failed("internal error: " + e);
                        }
                        synchronized (outcomes) {
                            outcomes.set(i, done);
                            left.set(i, notUpdated);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) result(f);
        }

        int width = found.stream().mapToInt(f -> f.name().length()).max().orElse(0);
        int versionWidth = found.stream()
                .mapToInt(f -> f.pkg().version() == null ? 0 : f.pkg().version().length())
                .max()
                .orElse(0);
        int updated = 0;
        int current = 0;
        int skipped = 0;
        line.clear();
        for (int i = 0; i < found.size(); i++) {
            Found f = found.get(i);
            List<String> notUpdated = left.get(i);
            String status = switch (outcomes.get(i)) {
                case Newer n -> {
                    updated++;
                    yield (refresh ? check ? "hashes to refresh" : "hashes refreshed" : "-> " + n.to().version())
                            + notUpdated(notUpdated);
                }
                case Updated u -> {
                    updated++;
                    yield u.how() + notUpdated(notUpdated);
                }
                case Script s -> {
                    updated++;
                    yield "has an update script";
                }
                case Current c -> {
                    current++;
                    yield verbose ? refresh ? "hashes up to date" : "up to date" : null;
                }
                case Skipped s -> {
                    skipped++;
                    yield verbose ? "skipped: " + s.why() : null;
                }
                case Failed x -> {
                    failed++;
                    yield "error: " + x.why();
                }
            };
            if (status == null) continue;
            String version = f.pkg().version() == null ? "" : f.pkg().version();
            err.println(pad(f.name(), width) + "  " + pad(version, versionWidth) + "  " + status + "  " + f.file());
        }
        String done = refresh ? check ? " to refresh, " : " refreshed, " : check ? " to update, " : " updated, ";
        err.println(updated + done + current + " up to date, " + skipped
                + " skipped" + (failed > 0 ? ", " + failed + " failed" : "") + " in "
                + Rewrite.duration(System.nanoTime() - start));
        return failed > 0 || check && updated > 0 ? 1 : 0;
    }

    private static <T> T result(Future<T> f) throws InterruptedException {
        try {
            return f.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static String notUpdated(List<String> left) {
        return left.isEmpty() ? "" : " (" + String.join(", ", left) + " not updated)";
    }

    private Outcome check(Found f, Updater updater) {
        Package p = f.pkg();
        try {
            if (refresh) {
                if (p.sources().isEmpty()) return new Skipped(p.skipped());
                return new Newer(Updater.current(p), updater.hashes(p, Updater.current(p)));
            }
            if (p.updateScript() && !noUpdateScript && !check && (f.target() != null || p.route() != null))
                return new Script();
            if (!p.updatable(branch)) return new Skipped(p.skipped());
            Updater.Newer newer = updater.newer(p);
            if (newer == null) return new Current();
            return new Newer(newer, check ? List.of() : updater.hashes(p, newer));
        } catch (IOException e) {
            return new Failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed("interrupted");
        }
    }

    /** Writes, unless {@code --check}, the update of {@code f}; its outcome. */
    private Outcome apply(Found f, Outcome o, Updater updater, List<String> notUpdated, Status line) {
        try {
            if (o instanceof Script) return script(f, updater, line);
            Newer n = (Newer) o;
            Updates.Progress progress = (step, part) -> line.show(f.name() + ": " + step);
            String src = Sources.read(f.file());
            Updates.Update u = refresh
                    ? updater.refresh(f.file(), src, f.index(), f.target(), n.hashes(), !noBuild, progress)
                    : updater.update(f.file(), src, f.index(), f.target(), n.to(), n.hashes(), !noBuild, progress);
            notUpdated.addAll(u.notUpdated());
            if (refresh && u.edits().isEmpty() && u.files().isEmpty()) return new Current();
            if (check) return o;
            String result = Edit.apply(src, u.edits());
            Parser.parse(result);
            write(f.file(), u, result);
            if (build || test) {
                try {
                    line.show(f.name() + ": building");
                    updater.verify(f.file(), result, f.index(), f.target(), false);
                    if (test) {
                        line.show(f.name() + ": testing");
                        updater.verify(f.file(), result, f.index(), f.target(), true);
                    }
                } catch (IOException e) {
                    Files.writeString(f.file(), src, StandardCharsets.UTF_8);
                    for (Map.Entry<String, Updates.Change> e2 : u.files().entrySet())
                        Files.writeString(Path.of(e2.getKey()), e2.getValue().before(), StandardCharsets.UTF_8);
                    return new Failed(e.getMessage());
                }
            }
            return o;
        } catch (IOException | ParseException e) {
            return new Failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed("interrupted");
        }
    }

    private static void write(Path file, Updates.Update u, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
        for (Map.Entry<String, Updates.Change> e : u.files().entrySet())
            Files.writeString(Path.of(e.getKey()), e.getValue().after(), StandardCharsets.UTF_8);
    }

    /** Runs the update script of {@code f}, which writes the files itself. */
    private Outcome script(Found f, Updater updater, Status line) throws IOException, InterruptedException {
        String before = Sources.read(f.file());
        line.show(f.name() + ": running its update script");
        updater.runUpdateScript(f.file(), before, f.index(), f.target());
        String after = Sources.read(f.file());
        if (after.equals(before)) return new Current();
        String version = version(after, f.index());
        return new Updated("-> " + (version != null ? version : "?") + " (update script)");
    }

    private static String version(String text, int index) {
        try {
            List<Package> pkgs = Packages.find(text);
            return index < pkgs.size() ? pkgs.get(index).version() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A last line on a terminal that says what goes on, rewritten in place; nothing elsewhere. */
    private static final class Status {
        private final PrintWriter err;
        private final boolean terminal;
        private final int width;

        Status(PrintWriter err, boolean terminal) {
            this.err = err;
            this.terminal = terminal;
            String columns = System.getenv("COLUMNS");
            width = Math.max(20, (columns != null && columns.matches("\\d{1,4}") ? Integer.parseInt(columns) : 80) - 1);
        }

        synchronized void show(String text) {
            if (!terminal) return;
            err.print("\r\033[K" + (text.length() > width ? text.substring(0, width - 1) + "…" : text));
            err.flush();
        }

        synchronized void clear() {
            if (!terminal) return;
            err.print("\r\033[K");
            err.flush();
        }
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }
}
