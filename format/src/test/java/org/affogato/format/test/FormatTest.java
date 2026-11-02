package org.affogato.format.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.affogato.format.Formatter;
import org.affogato.parser.ParseException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/** Runs nixfmt's test/test.sh cases from the nixfmt/ data in the test jar. */
class FormatTest {
    private static final int WIDTH = 80;
    private static final Path ROOT = root();
    private static final Path TESTS = ROOT.resolve("nixfmt");
    private static final Set<String> SKIP = skipList();

    private static Path root() {
        try {
            URI jar = FormatTest.class.getResource("/format-skip.txt").toURI();
            FileSystem fs = FileSystems.newFileSystem(jar, Map.of());
            return fs.getPath("/");
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> skipList() {
        try {
            return Set.copyOf(Files.readAllLines(ROOT.resolve("format-skip.txt")).stream()
                    .map(l -> l.replaceAll("#.*", "").trim())
                    .filter(l -> !l.isEmpty())
                    .toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    private static DynamicTest test(String name, Executable body) {
        return DynamicTest.dynamicTest(name, () -> {
            if (!SKIP.contains(name)) body.execute();
        });
    }

    /** What test.sh compares for files that must not change: the output with one final newline, as echo prints it. */
    private static String echoed(String out) {
        return out.replaceAll("\n+$", "") + "\n";
    }

    private static void assertStable(Path file, int indent) throws IOException {
        String src = read(file);
        assertThat(echoed(Formatter.format(src, WIDTH, indent, true))).isEqualTo(src);
    }

    @TestFactory
    Stream<DynamicTest> correct() throws IOException {
        Stream<DynamicTest> correct = list(TESTS.resolve("correct")).stream()
                .map(p -> test("correct/" + p.getFileName(), () -> assertStable(p, 2)));
        Path indent4 = TESTS.resolve("correct-indent-4.nix");
        return Stream.concat(correct, Stream.of(test("correct-indent-4.nix", () -> assertStable(indent4, 4))));
    }

    @TestFactory
    Stream<DynamicTest> diff() throws IOException {
        return list(TESTS.resolve("diff")).stream().flatMap(dir -> {
            String name = "diff/" + dir.getFileName();
            return Stream.of(
                    test(name, () -> assertFormats(dir, "out.nix", false)),
                    test(name + " --strict", () -> assertFormats(dir, "out-pure.nix", true)));
        });
    }

    private static void assertFormats(Path dir, String expected, boolean strict) throws IOException {
        String out = Formatter.format(read(dir.resolve("in.nix")), WIDTH, 2, strict);
        assertThat(out).isEqualTo(read(dir.resolve(expected)));
        assertThat(Formatter.format(out, WIDTH, 2, strict)).as("idempotence").isEqualTo(out);
    }

    @TestFactory
    Stream<DynamicTest> invalid() throws IOException {
        return list(TESTS.resolve("invalid")).stream()
                .map(p -> test(
                        "invalid/" + p.getFileName(),
                        () -> assertThatThrownBy(() -> Formatter.format(read(p)))
                                .isInstanceOf(ParseException.class)));
    }
}
