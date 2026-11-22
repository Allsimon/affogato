package org.affogato.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds an attribute of a package from a copy of its file, so that the file is never left with what was built: the
 * hash of a fixed-output derivation, from the mismatch Nix reports when it is built with a fake hash, or the store
 * path of what it builds.
 */
final class Build {
    /** {@code lib.fakeHash}. */
    static final String FAKE_HASH = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static final Pattern GOT = Pattern.compile("got:\\s+(sha256-[A-Za-z0-9+/]{43}=)");

    private final String nixBuild;
    private final String nix;
    private final Path nixpkgs;

    Build(String nixBuild, String nix, Path nixpkgs) {
        this.nixBuild = nixBuild;
        this.nix = nix;
        this.nixpkgs = nixpkgs;
    }

    private interface Work<T> {
        T run(String expr, Path copy) throws IOException, InterruptedException;
    }

    /**
     * The hash of the attribute at {@code path} of the package of {@code file}, were its content {@code text}: of the
     * flake's package {@code target}, else of the file's value called with callPackage.
     */
    String hash(Path file, String text, Updater.Target target, List<String> path)
            throws IOException, InterruptedException {
        return copy(file, text, target, (expr, copy) -> {
            if (target != null) {
                List<String> attrs = new ArrayList<>(List.of(target.attr()));
                attrs.addAll(path);
                String ref = "path:" + copy + "#" + attrPath(attrs);
                return hash(
                        List.of(nix, "--extra-experimental-features", "nix-command flakes", "build", "--no-link", ref),
                        attrPath(attrs));
            }
            return hash(List.of(nixBuild, "--no-out-link", "-E", expr, "-A", attrPath(path)), attrPath(path));
        });
    }

    /**
     * The store paths the attribute at {@code path} of the package builds, as {@link #hash} builds it; none when it is
     * missing and {@code optional}.
     */
    List<String> realise(Path file, String text, Updater.Target target, List<String> path, boolean optional)
            throws IOException, InterruptedException {
        return copy(file, text, target, (expr, copy) -> {
            String selected =
                    "(" + expr + ")" + (path.isEmpty() ? "" : "." + attrPath(path)) + (optional ? " or { }" : "");
            Processes.Result r =
                    Processes.run(command(nixBuild, target, "--no-out-link", "-E", selected), null, null, false);
            if (r.status() != 0) throw new IOException("cannot build " + describe(path) + ": " + r.last());
            return r.out()
                    .strip()
                    .lines()
                    .map(String::strip)
                    .filter(l -> l.startsWith("/"))
                    .toList();
        });
    }

    /**
     * The command of the update script {@code script}, a function of the package's expression to the list of its
     * command, with the derivations in it built.
     */
    List<String> script(Path file, String text, Updater.Target target, List<String> path, String script)
            throws IOException, InterruptedException {
        return copy(file, text, target, (expr, copy) -> {
            String list = "(" + script.formatted("(" + expr + ")" + (path.isEmpty() ? "" : "." + attrPath(path))) + ")";
            Processes.Result built = Processes.run(
                    command(
                            nixBuild,
                            target,
                            "--no-out-link",
                            "-E",
                            "builtins.filter (x: builtins.isAttrs x && x ? outPath) " + list),
                    null,
                    null,
                    false);
            if (built.status() != 0) throw new IOException("cannot build the update script: " + built.last());
            Processes.Result r = Processes.run(
                    command(instantiate(), target, "--eval", "--strict", "--json", "-E", "map toString " + list),
                    null,
                    null,
                    false);
            if (r.status() != 0) throw new IOException("cannot evaluate the update script: " + r.last());
            List<String> out = new ArrayList<>();
            if (org.affogato.lsp.Json.parse(r.out().strip()) instanceof List<?> l)
                for (Object o : l) if (o instanceof String a) out.add(a);
            return out;
        });
    }

    /** nix-instantiate, next to nix-build when that is a path. */
    private String instantiate() {
        int slash = nixBuild.lastIndexOf('/');
        return slash < 0 ? "nix-instantiate" : nixBuild.substring(0, slash + 1) + "nix-instantiate";
    }

    private static List<String> command(String program, Updater.Target target, String... args) {
        List<String> command = new ArrayList<>(List.of(program));
        if (target != null) command.addAll(List.of("--option", "extra-experimental-features", "nix-command flakes"));
        command.addAll(List.of(args));
        return command;
    }

    private static String describe(List<String> path) {
        return path.isEmpty() ? "the package" : attrPath(path);
    }

    private <T> T copy(Path file, String text, Updater.Target target, Work<T> work)
            throws IOException, InterruptedException {
        file = file.toAbsolutePath().normalize();
        if (target != null) {
            Path root = target.flake().toAbsolutePath().normalize();
            Path copy = Files.createTempDirectory("affogato-update-");
            try {
                copyTree(root, copy);
                Files.writeString(copy.resolve(root.relativize(file).toString()), text, StandardCharsets.UTF_8);
                String expr = "(builtins.getFlake " + string("path:" + copy) + ").packages.${builtins.currentSystem}."
                        + quoteAttr(target.attr());
                return work.run(expr, copy);
            } finally {
                delete(copy);
            }
        }
        Path copy = Files.createTempFile(file.getParent(), ".affogato-update-", ".nix");
        try {
            Files.writeString(copy, text, StandardCharsets.UTF_8);
            return work.run("(import " + nixpkgs(file) + " { }).callPackage " + path(copy) + " { }", copy);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    /** The nixpkgs that builds {@code file}, as an expression. */
    String nixpkgs(Path file) {
        Path root = nixpkgs != null ? nixpkgs : checkout(file);
        return root == null ? "<nixpkgs>" : path(root);
    }

    /** The nixpkgs checkout {@code file} is in, or null. */
    static Path checkout(Path file) {
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pkgs/top-level/all-packages.nix"))) return dir;
        }
        return null;
    }

    private static String hash(List<String> command, String built) throws IOException, InterruptedException {
        Processes.Result r = Processes.run(command, null, null, true);
        Matcher m = GOT.matcher(r.out());
        if (m.find()) return m.group(1);
        if (r.status() == 0) throw new IOException(built + " built with the fake hash: it does not depend on it");
        throw new IOException("cannot build: " + r.last());
    }

    /** Copies {@code from} into {@code to}, links as links, without its hidden directories such as .git. */
    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && dir.getFileName().toString().startsWith("."))
                    return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(
                        file,
                        to.resolve(from.relativize(file).toString()),
                        LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.forEach(paths::add);
        }
        paths.sort(Comparator.reverseOrder());
        for (Path p : paths) {
            p.toFile().setWritable(true);
            Files.deleteIfExists(p);
        }
    }

    /** {@code p} as a Nix expression. */
    static String path(Path p) {
        return "(/. + " + string(p.toAbsolutePath().normalize().toString()) + ")";
    }

    static String string(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${") + "\"";
    }

    static String attrPath(List<String> names) {
        return String.join(".", names.stream().map(Build::quoteAttr).toList());
    }

    private static String quoteAttr(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_'-]*") ? name : "\"" + name.replace("\"", "\\\"") + "\"";
    }
}
