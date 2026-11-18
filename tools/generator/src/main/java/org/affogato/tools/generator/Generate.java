package org.affogato.tools.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Stream;

/**
 * Writes the files the repository derives from its code and test data, run from the project root:
 *
 * <pre>java build/jenesis/Execute.java -Djenesis.execute.module=tools/generator/src/main/java [--check] [name...]</pre>
 *
 * With {@code --check}, writes nothing and exits with 1 when a file is out of date.
 */
public final class Generate {
    /** What a generator writes: files relative to the root, and the folder it owns whole, if any. */
    record Output(SequencedMap<Path, String> files, Path folder) {
        Output(SequencedMap<Path, String> files) {
            this(files, null);
        }
    }

    @FunctionalInterface
    interface Generator {
        Output generate(Path root) throws IOException, InterruptedException;
    }

    private static final SequencedMap<String, Generator> GENERATORS = new LinkedHashMap<>();

    static {
        GENERATORS.put("lang-tests", LangTests::generate);
        GENERATORS.put("versions", Versions::generate);
        GENERATORS.put("cli-docs", CliDocs::generate);
        GENERATORS.put("lint-rules", LintRules::generate);
        GENERATORS.put("playground-examples", PlaygroundExamples::generate);
        GENERATORS.put("builtin-docs", BuiltinDocs::generate);
    }

    private Generate() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        boolean check = false;
        List<String> names = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--check")) check = true;
            else if (GENERATORS.containsKey(arg)) names.add(arg);
            else
                throw new IllegalArgumentException(
                        "Unknown argument " + arg + ", expected --check or one of " + GENERATORS.sequencedKeySet());
        }
        if (names.isEmpty()) names.addAll(GENERATORS.sequencedKeySet());
        Path root = Path.of("");
        List<Path> stale = new ArrayList<>();
        for (String name : names) stale.addAll(apply(root, GENERATORS.get(name).generate(root), check));
        for (Path path : stale) System.out.println((check ? "out of date: " : "written: ") + path);
        if (check && !stale.isEmpty()) System.exit(1);
    }

    private static List<Path> apply(Path root, Output output, boolean check) throws IOException {
        List<Path> stale = new ArrayList<>();
        if (output.folder() != null && Files.isDirectory(root.resolve(output.folder()))) {
            try (Stream<Path> files = Files.list(root.resolve(output.folder()))) {
                for (Path file : files.toList()) {
                    if (output.files().containsKey(root.relativize(file))) continue;
                    stale.add(file);
                    if (!check) Files.delete(file);
                }
            }
        }
        for (Map.Entry<Path, String> entry : output.files().entrySet()) {
            Path file = root.resolve(entry.getKey());
            String current = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            if (entry.getValue().equals(current)) continue;
            stale.add(file);
            if (!check) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        return stale;
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
