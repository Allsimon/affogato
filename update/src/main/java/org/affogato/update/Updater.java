package org.affogato.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import org.affogato.lint.Edit;
import org.affogato.lint.Packages;
import org.affogato.lint.Packages.Dependency;
import org.affogato.lint.Packages.Lock;
import org.affogato.lint.Packages.Package;
import org.affogato.lint.Packages.Source;
import org.affogato.lsp.Json;
import org.affogato.lsp.Updates;

/**
 * Moves the packages {@link Packages} finds to their latest version: the version, the hash of each source by {@code
 * nix-prefetch-url} or by building it, the lock files, and the hashes of dependencies by building them with a fake
 * hash. The edits come back relative to the text given; no file is written.
 */
public final class Updater implements Updates {
    /**
     * {@code nixpkgs} builds the packages outside a flake and a nixpkgs checkout, {@code <nixpkgs>} when null; the
     * programs are looked up on the PATH unless absolute. {@code hosts} maps a host, as {@code gitlab.com}, to the
     * base URL that stands for it.
     */
    public record Options(
            String githubApi,
            String githubToken,
            String prefetcher,
            String nixBuild,
            String nix,
            Path nixpkgs,
            Map<String, String> hosts) {
        public Options(
                String githubApi, String githubToken, String prefetcher, String nixBuild, String nix, Path nixpkgs) {
            this(githubApi, githubToken, prefetcher, nixBuild, nix, nixpkgs, Map.of());
        }

        /** GitHub's API with GITHUB_TOKEN, else GH_TOKEN, and Nix's programs from the PATH. */
        public static Options defaults() {
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isEmpty()) token = System.getenv("GH_TOKEN");
            return new Options("https://api.github.com", token, "nix-prefetch-url", "nix-build", "nix", null);
        }
    }

    /** The flake whose package {@code attr} a package is, which builds its dependencies. */
    public record Target(Path flake, String attr) {}

    /** Where the flake's package {@code attr} sets its version; {@code line} and {@code column} count from 1. */
    public record Position(String attr, Path file, int line, int column) {}

    /** A version to update to, and the commit when it is the head of a branch rather than a release. */
    public record Newer(String version, String commit) {}

    private static final String POSITIONS = """
        let
          packages = (builtins.getFlake "path:%s").packages.${builtins.currentSystem} or { };
          at = name: drv:
            let pos = builtins.tryEval (builtins.unsafeGetAttrPos "version" drv);
            in if pos.success && pos.value != null then [ (pos.value // { inherit name; }) ] else [ ];
        in
        builtins.concatLists (builtins.attrValues (builtins.mapAttrs at packages))
        """;

    /** The command of {@code passthru.updateScript}, as a list, of {@code drv}. */
    private static final String SCRIPT = """
        let
          drv = %s;
          s = drv.updateScript;
          c = if builtins.isAttrs s && !(s ? outPath) && s ? command then s.command else s;
        in
        if builtins.isList c then c else [ c ]\
        """;

    private static final long EVALUATION_TIMEOUT = 120_000;

    /** How long {@link #newer(String, String, int, Consumer, Consumer)} keeps what it found. */
    private static final long CACHED = 3_600_000;

    private final Options options;
    private final Releases releases;
    private final Prefetch prefetch;
    private final Build build;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, Newer newer, String error) {}

    public Updater(Options options) {
        this.options = options;
        releases = new Releases(new Http(options.githubApi(), options.githubToken(), options.hosts()));
        prefetch = new Prefetch(options.prefetcher());
        build = new Build(options.nixBuild(), options.nix(), options.nixpkgs());
    }

    /**
     * The newest version of {@code p} past its own, or null when it is the newest: the head of the default branch for
     * a package pinned to a commit.
     */
    public Newer newer(Package p) throws IOException, InterruptedException {
        Source first = p.sources().getFirst();
        if (p.commit() != null) {
            Releases.Head head = releases.head(first);
            if (head.commit().equals(p.commit())) return null;
            return new Newer(Packages.unstable(p.version(), head.date()), head.commit());
        }
        String v = releases.newer(first, p.version());
        return v == null ? null : new Newer(v, null);
    }

    /** The version {@code p} has, and its commit when pinned to one: what refreshing its hashes keeps. */
    public static Newer current(Package p) {
        return new Newer(p.version(), p.commit());
    }

    /** The hash of each source of {@code p} at {@code n}, null for those that are built to get it. */
    public List<String> hashes(Package p, Newer n) throws IOException, InterruptedException {
        return hashes(p, n, new Steps(Progress.NONE, 0));
    }

    private List<String> hashes(Package p, Newer n, Steps steps) throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        for (Source s : p.sources()) {
            if (s.built() != null) {
                out.add(null);
                continue;
            }
            String url = Releases.archive(s, n.version(), n.commit());
            steps.next("Fetching " + url);
            out.add(prefetch.hash(url, s.unpack()));
        }
        return out;
    }

    /**
     * The edits to {@code text}, the content of {@code file}, that move its package at {@code index} to {@code n}
     * with the hashes of its sources; and, when {@code buildDependencies}, the hashes of the sources that are built,
     * of its dependencies, built as the package of {@code target}, else with nixpkgs' callPackage of the file, and its
     * lock files.
     */
    public Update update(
            Path file,
            String text,
            int index,
            Target target,
            Newer n,
            List<String> hashes,
            boolean buildDependencies,
            Progress progress)
            throws IOException, InterruptedException {
        Package p = Packages.find(text).get(index);
        Steps steps = new Steps(progress, builds(p, buildDependencies));
        return update(file, text, p, target, n, hashes, buildDependencies, steps);
    }

    private Update update(
            Path file,
            String text,
            Package p,
            Target target,
            Newer n,
            List<String> hashes,
            boolean buildDependencies,
            Steps steps)
            throws IOException, InterruptedException {
        List<Edit> edits = new ArrayList<>(p.update(n.version(), n.commit(), hashes));
        List<String> left = new ArrayList<>();
        List<String> sourceHashes = new ArrayList<>(hashes);
        for (int i = 0; i < p.sources().size(); i++) {
            Source s = p.sources().get(i);
            if (hashes.get(i) != null) continue;
            String attr = String.join(".", s.attr());
            if (!buildDependencies)
                throw new IOException(attr + ": " + s.built() + " needs a build for its hash, which --no-build skips");
            steps.next("Building " + attr + " for " + s.built());
            String hash = built(file, text, p, target, edits, new Edit(s.hashStart(), s.hashEnd(), ""), s.attr(), attr);
            Edit e = new Edit(s.hashStart(), s.hashEnd(), "\"" + hash + "\"");
            edits.add(e);
            sourceHashes.set(i, hash);
        }
        Map<String, Change> files = new LinkedHashMap<>();
        for (Lock lock : p.locks()) {
            if (!buildDependencies) {
                left.add(lock.path());
                continue;
            }
            steps.next("Generating " + lock.path());
            Path at = file.toAbsolutePath().getParent().resolve(lock.path()).normalize();
            String before = Files.isRegularFile(at) ? Files.readString(at, StandardCharsets.UTF_8) : "";
            String after;
            try {
                after = lock(file, Edit.apply(text, withRoute(p, target, edits)), p, target, n, sourceHashes, lock, at);
            } catch (IOException e) {
                throw new IOException(lock.path() + ": " + e.getMessage(), e);
            }
            if (after == null) left.add(lock.path());
            else if (!after.equals(before)) {
                files.put(at.toString(), new Change(before, after));
                if (lock.outputHashes()) left.add("cargoLock.outputHashes");
            }
        }
        for (Dependency d : p.dependencies()) {
            if (!buildDependencies || d.start() < 0) {
                left.add(d.name());
                continue;
            }
            steps.next("Building " + d.attr() + " for " + d.name());
            String hash = built(file, text, p, target, edits, d.update(""), List.of(d.attr()), d.name());
            edits.add(d.update(hash));
        }
        return new Update(n.version(), List.copyOf(edits), List.copyOf(left), files);
    }

    /** The hash of {@code attr} built with a fake hash in place of {@code hash}, after {@code edits}. */
    private String built(
            Path file,
            String text,
            Package p,
            Target target,
            List<Edit> edits,
            Edit hash,
            List<String> attr,
            String name)
            throws IOException, InterruptedException {
        List<Edit> fake = new ArrayList<>(edits);
        fake.add(new Edit(hash.start(), hash.end(), "\"" + Build.FAKE_HASH + "\""));
        try {
            return build.hash(file, Edit.apply(text, withRoute(p, target, fake)), target, path(p, target, attr, name));
        } catch (IOException e) {
            throw new IOException(name + ": " + e.getMessage(), e);
        }
    }

    /** {@code edits}, and those of the route to the package when it is built with callPackage. */
    private static List<Edit> withRoute(Package p, Target target, List<Edit> edits) {
        if (target != null || p.route() == null) return edits;
        List<Edit> out = new ArrayList<>(edits);
        out.addAll(p.route().edits());
        return out;
    }

    /** The attribute path of {@code attr} of the package, from the file's value when it is built with callPackage. */
    private static List<String> path(Package p, Target target, List<String> attr, String name) throws IOException {
        if (target != null) return attr;
        if (p.route() == null)
            throw new IOException(name + ": cannot build " + String.join(".", attr)
                    + ": the file's value does not lead to the package");
        List<String> out = new ArrayList<>(p.route().path());
        out.addAll(attr);
        return out;
    }

    /** The content of {@code lock}, at {@code at}, for the update; null when there is nothing to take it from. */
    private String lock(
            Path file, String text, Package p, Target target, Newer n, List<String> hashes, Lock lock, Path at)
            throws IOException, InterruptedException {
        switch (lock.type()) {
            case CARGO -> {
                Source s = p.sources().getFirst();
                if (s.built() != null || !s.unpack()) return null;
                Path src =
                        Path.of(prefetch.path(Releases.archive(s, n.version(), n.commit()), true, hashes.getFirst()));
                Path found = src.resolve("Cargo.lock");
                return Files.isRegularFile(found) ? Files.readString(found, StandardCharsets.UTF_8) : null;
            }
            case NUGET -> {
                List<String> script =
                        build.realise(file, text, target, path(p, target, List.of("fetch-deps"), "nugetDeps"), false);
                if (script.isEmpty()) throw new IOException("fetch-deps built nothing");
                Path out =
                        Files.createTempFile("affogato-deps-", at.getFileName().toString());
                try {
                    Processes.Result r = Processes.run(List.of(script.getFirst(), out.toString()), null, null, true);
                    if (r.status() != 0) throw new IOException("fetch-deps failed: " + r.last());
                    return Files.readString(out, StandardCharsets.UTF_8);
                } finally {
                    Files.deleteIfExists(out);
                }
            }
            case GRADLE -> {
                List<String> script = build.realise(
                        file, text, target, path(p, target, List.of("mitmCache", "updateScript"), "mitmCache"), false);
                if (script.isEmpty()) throw new IOException("mitmCache.updateScript built nothing");
                String before = Files.isRegularFile(at) ? Files.readString(at, StandardCharsets.UTF_8) : null;
                Path dir = Files.createTempDirectory("affogato-gradle-");
                try {
                    Processes.Result r = Processes.run(List.of(script.getFirst()), dir, null, true);
                    String after = Files.isRegularFile(at) ? Files.readString(at, StandardCharsets.UTF_8) : null;
                    if (!Objects.equals(before, after)) {
                        if (before == null) Files.delete(at);
                        else Files.writeString(at, before, StandardCharsets.UTF_8);
                    } else {
                        try (Stream<Path> files = Files.walk(dir)) {
                            Path written = files.filter(f -> f.getFileName().equals(at.getFileName()))
                                    .findFirst()
                                    .orElse(null);
                            after = written == null ? null : Files.readString(written, StandardCharsets.UTF_8);
                        }
                    }
                    if (r.status() != 0) throw new IOException("mitmCache.updateScript failed: " + r.last());
                    return after;
                } finally {
                    Build.delete(dir);
                }
            }
        }
        return null;
    }

    /**
     * Builds the package at {@code index} of {@code text}, the content of {@code file}, or its {@code passthru.tests}
     * when {@code tests}: as the package of {@code target}, else with nixpkgs' callPackage of the file.
     */
    public void verify(Path file, String text, int index, Target target, boolean tests)
            throws IOException, InterruptedException {
        Package p = Packages.find(text).get(index);
        List<String> attr = path(p, target, tests ? List.of("tests") : List.of(), p.name());
        build.realise(file, Edit.apply(text, withRoute(p, target, List.of())), target, attr, tests);
    }

    /**
     * Runs the {@code passthru.updateScript} of the package at {@code index} of {@code text}, the content of {@code
     * file}, which changes the files itself; built as the package of {@code target}, else with callPackage.
     */
    public void runUpdateScript(Path file, String text, int index, Target target)
            throws IOException, InterruptedException {
        Package p = Packages.find(text).get(index);
        List<String> attr = path(p, target, List.of(), "updateScript");
        String routed = Edit.apply(text, withRoute(p, target, List.of()));
        List<String> command = build.script(file, routed, target, attr, SCRIPT);
        if (command.isEmpty()) throw new IOException("the update script is empty");
        Path abs = file.toAbsolutePath().normalize();
        Path checkout = Build.checkout(abs);
        Path dir = checkout != null ? checkout : target != null ? target.flake() : abs.getParent();
        String name = p.name() != null ? p.name() : Workspace.name(abs);
        Map<String, String> env = new HashMap<>();
        env.put("UPDATE_NIX_NAME", name + (p.version() == null ? "" : "-" + p.version()));
        env.put("UPDATE_NIX_PNAME", name);
        if (p.version() != null) env.put("UPDATE_NIX_OLD_VERSION", p.version());
        env.put("UPDATE_NIX_ATTR_PATH", target != null ? target.attr() : attrPath(abs, name));
        Processes.Result r = Processes.run(command, dir, env, true);
        if (r.status() != 0) throw new IOException("the update script failed: " + r.last());
    }

    /** The attribute of a package in nixpkgs: its directory's name under by-name, else its name. */
    private static String attrPath(Path file, String name) {
        Path dir = file.getParent();
        return dir != null
                        && dir.getParent() != null
                        && dir.getParent().getParent() != null
                        && dir.getParent().getParent().getFileName().toString().equals("by-name")
                ? dir.getFileName().toString()
                : name;
    }

    /** The update of the package at {@code index} of {@code text} to its latest version, or null when it has it. */
    public Update latest(Path file, String text, int index) throws IOException, InterruptedException {
        return latest(file, text, index, Progress.NONE);
    }

    /** As {@link #latest(Path, String, int)}, reporting each step to {@code progress}. */
    public Update latest(Path file, String text, int index, Progress progress)
            throws IOException, InterruptedException {
        Package p = Packages.find(text).get(index);
        if (p.skipped() != null) throw new IOException(p.skipped());
        Steps steps = new Steps(progress, 2 + p.sources().size() + builds(p, true));
        Source first = p.sources().getFirst();
        steps.next(
                p.commit() != null
                        ? "Looking for the head of " + first.owner() + "/" + first.repo()
                        : "Looking for the latest version of "
                                + (first.owner() != null
                                        ? first.owner() + "/" + first.repo()
                                        : first.template().replace('\0', '*')));
        Newer n = newer(p);
        if (n == null) return null;
        steps.next("Looking for the flake that has the package");
        Target target = target(file, text, p);
        return update(file, text, p, target, n, hashes(p, n, steps), true, steps);
    }

    /**
     * The edits that refresh the hashes of the package at {@code index} of {@code text} at the version it has, or at
     * the commit it is pinned to, with the hashes of its sources already fetched: none when they are right already.
     */
    public Update refresh(
            Path file,
            String text,
            int index,
            Target target,
            List<String> hashes,
            boolean buildDependencies,
            Progress progress)
            throws IOException, InterruptedException {
        Package p = Packages.find(text).get(index);
        return refresh(text, p, update(file, text, index, target, current(p), hashes, buildDependencies, progress));
    }

    /** The refresh of a package built as the flake around it has it. */
    public Update refresh(Path file, String text, int index) throws IOException, InterruptedException {
        return refresh(file, text, index, Progress.NONE);
    }

    /** As {@link #refresh(Path, String, int)}, reporting each step to {@code progress}. */
    public Update refresh(Path file, String text, int index, Progress progress)
            throws IOException, InterruptedException {
        Package p = Packages.find(text).get(index);
        if (p.sources().isEmpty()) throw new IOException(p.skipped());
        Steps steps = new Steps(progress, 1 + p.sources().size() + builds(p, true));
        steps.next("Looking for the flake that has the package");
        Target target = target(file, text, p);
        Newer n = current(p);
        return refresh(text, p, update(file, text, p, target, n, hashes(p, n, steps), true, steps));
    }

    /** {@code u} without the edits that change nothing. */
    private static Update refresh(String text, Package p, Update u) {
        List<Edit> changes = u.edits().stream()
                .filter(e -> !text.substring(e.start(), e.end()).equals(e.replacement()))
                .toList();
        return new Update(p.version(), changes, u.notUpdated(), u.files());
    }

    /** The builds and fetches of {@code p} its update makes besides fetching its sources. */
    private static int builds(Package p, boolean buildDependencies) {
        if (!buildDependencies) return 0;
        return (int) (p.dependencies().stream().filter(d -> d.start() >= 0).count()
                + p.sources().stream().filter(s -> s.built() != null).count()
                + p.locks().size());
    }

    /** Counts the steps reported to {@code progress}, of {@code total}. */
    private static final class Steps {
        private final Progress progress;
        private final int total;
        private int done;

        Steps(Progress progress, int total) {
            this.progress = progress;
            this.total = total;
        }

        void next(String step) {
            progress.report(step, total == 0 ? 0 : Math.min(1, (double) done++ / total));
        }
    }

    @Override
    public Runnable update(
            String file, String text, int index, Progress progress, Consumer<Update> done, Consumer<String> failed) {
        return background(() -> latest(Path.of(file), text, index, progress), done, failed);
    }

    @Override
    public Runnable refresh(
            String file, String text, int index, Progress progress, Consumer<Update> done, Consumer<String> failed) {
        return background(() -> refresh(Path.of(file), text, index, progress), done, failed);
    }

    @Override
    public Runnable newer(String file, String text, int index, Consumer<String> done, Consumer<String> failed) {
        return background(
                () -> cachedNewer(Packages.find(text).get(index)),
                n -> done.accept(n == null ? null : n.version()),
                failed);
    }

    /** {@link #newer(Package)}, kept a while, so that editors may ask for each package as they show it. */
    public Newer cachedNewer(Package p) throws IOException, InterruptedException {
        if (!p.updatable(false)) throw new IOException(p.skipped());
        Source s = p.sources().getFirst();
        String key = String.join(
                "\n",
                s.kind().name(),
                String.valueOf(s.domain()),
                String.valueOf(s.owner()),
                String.valueOf(s.repo()),
                s.template(),
                String.valueOf(p.version()),
                String.valueOf(p.commit()));
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHED) {
            if (c.error() != null) throw new IOException(c.error());
            return c.newer();
        }
        try {
            Newer n = newer(p);
            cache.put(key, new Cached(now, n, null));
            return n;
        } catch (IOException e) {
            cache.put(key, new Cached(now, null, e.getMessage()));
            throw e;
        }
    }

    @Override
    public Runnable updateWorkspace(
            List<String> roots, Progress progress, Consumer<WorkspaceUpdate> done, Consumer<String> failed) {
        return background(() -> updateWorkspace(roots.stream().map(Path::of).toList(), progress), done, failed);
    }

    /**
     * Updates every package of the {@code .nix} files below {@code roots} that can be, the files at once, and the
     * packages of a file one after the other; without writing them.
     */
    public WorkspaceUpdate updateWorkspace(List<Path> roots, Progress progress)
            throws IOException, InterruptedException {
        progress.report("Looking for packages", 0);
        Workspace.Discovery discovery = Workspace.find(roots, true);
        Map<Path, List<Workspace.Found>> byFile = new LinkedHashMap<>();
        for (Workspace.Found f : discovery.found())
            if (f.pkg().updatable(false))
                byFile.computeIfAbsent(f.file(), k -> new ArrayList<>()).add(f);
        Map<String, Change> files = new ConcurrentHashMap<>();
        List<String> summary = java.util.Collections.synchronizedList(new ArrayList<>());
        int total = byFile.values().stream().mapToInt(List::size).sum();
        java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();
        try (ExecutorService pool =
                Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<Path, List<Workspace.Found>> e : byFile.entrySet()) {
                futures.add(pool.submit(() -> {
                    String before = Files.readString(e.getKey(), StandardCharsets.UTF_8);
                    String text = before;
                    for (Workspace.Found f : e.getValue()) {
                        progress.report(f.name(), (double) checked.getAndIncrement() / Math.max(1, total));
                        try {
                            Package p = Packages.find(text).get(f.index());
                            Newer n = newer(p);
                            if (n == null) continue;
                            Target target = f.target() != null ? f.target() : target(f.file(), text, p);
                            Update u = update(
                                    f.file(), text, p, target, n, hashes(p, n), true, new Steps(Progress.NONE, 0));
                            text = Edit.apply(text, u.edits());
                            files.putAll(u.files());
                            summary.add(f.name() + ": " + p.version() + " -> " + n.version()
                                    + (u.notUpdated().isEmpty()
                                            ? ""
                                            : " (" + String.join(", ", u.notUpdated()) + " not updated)"));
                        } catch (IOException | RuntimeException x) {
                            summary.add(f.name() + ": error: " + x.getMessage());
                        }
                    }
                    if (!text.equals(before))
                        files.put(e.getKey().toAbsolutePath().toString(), new Change(before, text));
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException x) {
                    summary.add("error: " + x.getCause().getMessage());
                }
            }
        }
        return new WorkspaceUpdate(new java.util.TreeMap<>(files), List.copyOf(summary));
    }

    private interface Work<T> {
        T run() throws IOException, InterruptedException;
    }

    /** Runs {@code work} on a thread of its own; the returned runnable interrupts it, which kills what it runs. */
    private static <T> Runnable background(Work<T> work, Consumer<T> done, Consumer<String> failed) {
        Thread thread = Thread.ofVirtual().name("affogato-update").start(() -> {
            T u;
            try {
                u = work.run();
            } catch (IOException | RuntimeException e) {
                failed.accept(String.valueOf(e.getMessage()));
                return;
            } catch (InterruptedException e) {
                failed.accept("cancelled");
                return;
            }
            done.accept(u);
        });
        return thread::interrupt;
    }

    /** The package of the nearest flake around {@code file} that {@code p} is, or null. */
    public static Target target(Path file, String text, Package p) throws InterruptedException {
        file = file.toAbsolutePath().normalize();
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (!Files.isRegularFile(dir.resolve("flake.nix"))) continue;
            List<Position> positions;
            try {
                positions = positions(dir);
            } catch (EvalException | RuntimeException e) {
                return null;
            }
            for (Position pos : positions) {
                if (!pos.file().equals(file)) continue;
                int offset = offset(text, pos.line(), pos.column());
                if (p.start() <= offset && offset < p.end()) return new Target(dir, pos.attr());
            }
            return null;
        }
        return null;
    }

    /** Where the packages of the flake at {@code dir} set their version, evaluated by affogato. */
    public static List<Position> positions(Path dir) throws EvalException, InterruptedException {
        String nixPath = System.getenv("NIX_PATH");
        var options =
                new Interpreter.Options(nixPath == null ? "" : nixPath, "", "", Interpreter.Output.JSON, "", List.of());
        String abs = dir.toAbsolutePath().normalize().toString();
        String json = Interpreter.evaluate(
                options,
                null,
                POSITIONS.formatted(
                        abs.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${")),
                EVALUATION_TIMEOUT);
        List<Position> out = new ArrayList<>();
        if (!(Json.parse(json) instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m
                    && m.get("name") instanceof String name
                    && m.get("file") instanceof String f
                    && m.get("line") instanceof Long line
                    && m.get("column") instanceof Long column) {
                out.add(new Position(name, Path.of(f).normalize(), line.intValue(), column.intValue()));
            }
        }
        return out;
    }

    /** The offset of {@code line} and {@code column}, from 1, in {@code text}, or -1. */
    public static int offset(String text, int line, int column) {
        int at = 0;
        for (int l = 1; l < line; l++) {
            at = text.indexOf('\n', at) + 1;
            if (at == 0) return -1;
        }
        return at + column - 1;
    }
}
