package org.affogato.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs NixOS/nix's tests/functional/lang suite the way lang.sh does, Tvix's eval tests in tvix/, and affogato's own
 * tests in affogato/.
 */
class LangTest {
    private static final Path TESTS = testsRoot();
    private static final Path LANG = TESTS.resolve("lang");
    private static final Path TVIX = TESTS.resolve("tvix");
    private static final Path OWN = TESTS.resolve("affogato");
    /** Tests known to fail, with the reason; keep this list short and shrinking. */
    private static final Set<String> SKIP = skipList();

    /**
     * Extracts the test data from the test jar into a scratch directory and restores the symlinks listed in
     * lang-symlinks.txt, which the jar holds as copies, or not at all when they dangle.
     */
    private static Path testsRoot() {
        try {
            Path root = Files.createTempDirectory("affogato-tests");
            URI jar = LangTest.class.getResource("/lang-skip.txt").toURI();
            try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of());
                    Stream<Path> files = Files.walk(fs.getPath("/"))) {
                for (Path p : files.toList()) {
                    Path dest = root.resolve(p.toString().substring(1));
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(p, dest);
                    }
                }
            }
            for (String line : Files.readAllLines(root.resolve("lang-symlinks.txt"))) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] link = line.split(" ");
                Path at = root.resolve(link[0]);
                deleteRecursively(at);
                Files.createSymbolicLink(at, Path.of(link[1]));
            }
            return root;
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }

    private static Set<String> skipList() {
        try {
            return Set.copyOf(Files.readAllLines(TESTS.resolve("lang-skip.txt")).stream()
                    .map(l -> l.replaceAll("#.*", "").trim())
                    .filter(l -> !l.isEmpty())
                    .toList());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static String latin1(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
    }

    private static List<Path> tests(String prefix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(LANG, TVIX, OWN)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().startsWith(prefix)
                                && p.toString().endsWith(".nix"))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    private static Path sibling(Path test, String suffix) {
        return test.resolveSibling(name(test) + suffix);
    }

    private static String name(Path p) {
        String f = p.getFileName().toString();
        return f.substring(0, f.length() - 4);
    }

    private static String eval(Path file, List<String> flags) throws Exception {
        List<String> searchPath = new ArrayList<>();
        List<Interpreter.Arg> args = new ArrayList<>();
        String attr = "";
        for (int i = 0; i < flags.size(); i++) {
            switch (flags.get(i)) {
                case "-I" -> searchPath.add(absolutise(flags.get(++i)));
                case "-A" -> attr = flags.get(++i);
                case "--arg" ->
                    args.add(new Interpreter.Arg(flags.get(++i), flags.get(++i).replace("lang/", LANG + "/"), false));
                case "--argstr" -> args.add(new Interpreter.Arg(flags.get(++i), flags.get(++i), true));
                default -> {}
            }
        }
        searchPath.add(LANG + "/dir3");
        searchPath.add(LANG + "/dir4");
        var options =
                new Interpreter.Options(String.join(":", searchPath), "", "", Interpreter.Output.STRICT, attr, args);
        return Interpreter.evaluate(options, file.toString(), latin1(file), 0).replace(TESTS.toString(), "/pwd");
    }

    private static String absolutise(String entry) {
        int eq = entry.indexOf('=');
        return eq < 0
                ? TESTS.resolve(entry).toString()
                : entry.substring(0, eq + 1) + TESTS.resolve(entry.substring(eq + 1));
    }

    private static List<String> flags(Path test) throws IOException {
        Path f = sibling(test, ".flags");
        if (!Files.exists(f)) return List.of();
        return List.of(Files.readString(f).replaceAll("#.*", "").trim().split("\\s+"));
    }

    private static DynamicTest test(Path p, org.junit.jupiter.api.function.Executable body) {
        String n = (p.getParent().equals(TVIX) ? "tvix/" : "") + name(p);
        return DynamicTest.dynamicTest(n, () -> {
            if (SKIP.contains(n)) return;
            body.execute();
        });
    }

    @TestFactory
    Stream<DynamicTest> evalOkay() throws IOException {
        return tests("eval-okay-").stream()
                .filter(p -> !Files.exists(sibling(p, ".exp.xml")) && !Files.exists(sibling(p, ".exp-disabled")))
                .map(p -> test(p, () -> {
                    String expected = latin1(sibling(p, ".exp")).stripTrailing();
                    assertThat(eval(p, flags(p))).isEqualTo(expected);
                }));
    }

    @TestFactory
    Stream<DynamicTest> evalFail() throws IOException {
        return tests("eval-fail-").stream()
                .map(p -> test(
                        p, () -> assertThatThrownBy(() -> eval(p, flags(p))).isInstanceOf(EvalException.class)));
    }

    private static void parse(Path p) throws IOException {
        org.affogato.parser.Parser.parse(latin1(p));
    }

    @TestFactory
    Stream<DynamicTest> parseOkay() throws IOException {
        return tests("parse-okay-").stream().map(p -> test(p, () -> parse(p)));
    }

    @TestFactory
    Stream<DynamicTest> parseFail() throws IOException {
        return tests("parse-fail-").stream()
                .map(p -> test(
                        p, () -> assertThatThrownBy(() -> eval(p, List.of())).isInstanceOf(EvalException.class)));
    }
}
