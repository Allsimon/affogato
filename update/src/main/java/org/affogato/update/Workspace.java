package org.affogato.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.affogato.EvalException;
import org.affogato.lint.Packages;
import org.affogato.lint.Packages.Package;
import org.affogato.parser.ParseException;

/** The packages of the {@code .nix} files of a tree, named after the attributes of its flake when it has one. */
public final class Workspace {
    private Workspace() {}

    /** The fetchers {@link Packages} finds, to read only the files that call one. */
    private static final Pattern FETCHER = Pattern.compile(
            "fetchFrom(GitHub|GitLab|Gitea|Codeberg|Sourcehut|Bitbucket)|fetchPypi|fetchCrate|fetchurl|fetchzip");

    /** The package at {@code index} of {@code file}; {@code target} is the flake's package it is, else null. */
    public record Found(Path file, int index, String name, Package pkg, Updater.Target target) {}

    /**
     * What stopped a file from being searched, or a flake from naming its packages: at {@code offset} of {@code text}
     * when not -1; {@code text} is null when the file could not be read.
     */
    public record Problem(Path file, String text, int offset, String message, boolean warning) {}

    public record Discovery(List<Found> found, List<Problem> problems) {}

    public interface Reader {
        String read(Path file) throws IOException;
    }

    /** {@link #find(List, boolean, Reader)} reading the files as UTF-8. */
    public static Discovery find(List<Path> roots, boolean eval) throws IOException, InterruptedException {
        return find(roots, eval, f -> Files.readString(f, StandardCharsets.UTF_8));
    }

    /**
     * The packages of the files {@code roots} are, or that are below them out of hidden directories such as .git,
     * named after the attributes of the flake.nix of a root, evaluated when {@code eval}.
     */
    public static Discovery find(List<Path> roots, boolean eval, Reader reader)
            throws IOException, InterruptedException {
        List<Found> found = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Map<Path, String> sources = new HashMap<>();
        for (Path p : files(roots)) {
            String src;
            try {
                src = reader.read(p);
            } catch (IOException e) {
                problems.add(new Problem(p, null, -1, e.getMessage(), false));
                continue;
            }
            if (!FETCHER.matcher(src).find()) continue;
            sources.put(p.toAbsolutePath().normalize(), src);
            List<Package> pkgs;
            try {
                pkgs = Packages.find(src);
            } catch (ParseException e) {
                problems.add(new Problem(p, src, e.offset, e.getMessage(), false));
                continue;
            } catch (RuntimeException e) {
                problems.add(new Problem(p, src, -1, "internal error: " + e, false));
                continue;
            }
            for (int i = 0; i < pkgs.size(); i++) {
                Package pkg = pkgs.get(i);
                found.add(new Found(p, i, pkg.name() != null ? pkg.name() : name(p), pkg, null));
            }
        }
        if (eval) {
            for (Path root : roots) {
                if (Files.isRegularFile(root.resolve("flake.nix"))) attributeNames(root, sources, found, problems);
            }
        }
        return new Discovery(found, problems);
    }

    /** Names the packages after the flake's attributes whose version they set. */
    private static void attributeNames(Path root, Map<Path, String> sources, List<Found> found, List<Problem> problems)
            throws InterruptedException {
        List<Updater.Position> positions;
        try {
            positions = Updater.positions(root);
        } catch (EvalException | IllegalStateException e) {
            problems.add(new Problem(
                    root.resolve("flake.nix"), null, -1, "cannot evaluate the packages: " + e.getMessage(), true));
            return;
        }
        for (Updater.Position pos : positions) {
            String src = sources.get(pos.file());
            if (src == null) continue;
            int offset = Updater.offset(src, pos.line(), pos.column());
            for (int i = 0; i < found.size(); i++) {
                Found f = found.get(i);
                if (f.file().toAbsolutePath().normalize().equals(pos.file())
                        && f.pkg().start() <= offset
                        && offset < f.pkg().end()) {
                    found.set(
                            i,
                            new Found(f.file(), f.index(), pos.attr(), f.pkg(), new Updater.Target(root, pos.attr())));
                }
            }
        }
    }

    /** The .nix files {@code roots} are or have below them, out of hidden directories such as .git. */
    public static List<Path> files(List<Path> roots) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                out.add(root);
                continue;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name =
                            dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(root) && name.startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".nix")) out.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** The name a file gives its package: its directory's for default.nix and package.nix. */
    public static String name(Path p) {
        String file = p.getFileName().toString();
        Path dir = p.toAbsolutePath().normalize().getParent();
        if ((file.equals("default.nix") || file.equals("package.nix")) && dir != null && dir.getFileName() != null)
            return dir.getFileName().toString();
        return file.endsWith(".nix") ? file.substring(0, file.length() - ".nix".length()) : file;
    }

    /** Whether {@code f} is one of {@code names}: by its name, the name its file gives it, or its pname. */
    public static boolean named(Found f, List<String> names) {
        return names.isEmpty()
                || names.contains(f.name())
                || names.contains(name(f.file()))
                || f.pkg().name() != null && names.contains(f.pkg().name());
    }
}
