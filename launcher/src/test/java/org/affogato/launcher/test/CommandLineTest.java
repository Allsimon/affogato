package org.affogato.launcher.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.regex.Pattern;
import org.affogato.launcher.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineTest {
    private static final String FORMATTED = "{\n  a = 1;\n  b = 2;\n}\n";
    private static final FileTime EPOCH = FileTime.fromMillis(0);

    @TempDir
    Path dir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        return Main.commandLine()
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }

    private Path file(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(p, EPOCH);
        return p;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void formatRewritesOnlyChangedFiles() throws IOException {
        Path ugly = file("ugly.nix", "{a=1;b=2;}");
        Path pretty = file("pretty.nix", FORMATTED);

        assertThat(run("format", ugly.toString(), pretty.toString())).isZero();

        assertThat(read(ugly)).isEqualTo(FORMATTED);
        assertThat(Files.getLastModifiedTime(pretty)).isEqualTo(EPOCH);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString())
                .matches("formatted " + Pattern.quote(ugly.toString()) + "\n1 file formatted, 1 unchanged in \\d+ms\n");
    }

    @Test
    void checkWritesNothingAndFailsOnAFileThatWouldChange() throws IOException {
        Path ugly = file("ugly.nix", "{a=1;b=2;}");
        Path pretty = file("pretty.nix", FORMATTED);

        assertThat(run("format", "--check", pretty.toString())).isZero();
        assertThat(run("format", "--check", ugly.toString(), pretty.toString())).isEqualTo(1);

        assertThat(read(ugly)).isEqualTo("{a=1;b=2;}");
        assertThat(Files.getLastModifiedTime(ugly)).isEqualTo(EPOCH);
        assertThat(err.toString())
                .contains("would be formatted " + ugly + "\n")
                .containsPattern("\n1 file would be formatted, 1 unchanged in \\d+ms\n");
    }

    @Test
    void quietPrintsOnlyErrors() throws IOException {
        Path bad = file("bad.nix", "{ a = ; }");
        Path ugly = file("ugly.nix", "{a=1;b=2;}");

        assertThat(run("fix", "--quiet", bad.toString(), ugly.toString())).isEqualTo(1);

        assertThat(read(ugly)).isEqualTo(FORMATTED);
        assertThat(err.toString()).startsWith(bad + ":1:7: error: ").hasLineCount(1);
    }

    @Test
    void formatOfStandardInputPrintsOnlyTheResult() {
        InputStream stdin = System.in;
        System.setIn(new ByteArrayInputStream("{a=1;b=2;}".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(run("format")).isZero();
        } finally {
            System.setIn(stdin);
        }

        assertThat(out.toString()).isEqualTo(FORMATTED);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void formatSearchesDirectoriesForNixFiles() throws IOException {
        Path nested = file("sub/dir/a.nix", "{a=1;b=2;}");
        Path other = file("sub/README", "{a=1;b=2;}");

        assertThat(run("format", dir.toString())).isZero();

        assertThat(read(nested)).isEqualTo(FORMATTED);
        assertThat(read(other)).isEqualTo("{a=1;b=2;}");
    }

    @Test
    void formatWidthAndIndent() throws IOException {
        Path p = file("a.nix", "{a=1;b=[2 3];}");

        assertThat(run("format", "--indent", "4", "--width", "10", p.toString()))
                .isZero();

        assertThat(read(p)).isEqualTo("{\n    a = 1;\n    b = [\n        2\n        3\n    ];\n}\n");
    }

    @Test
    void formatFailsOnSyntaxErrorAndGoesOn() throws IOException {
        Path bad = file("bad.nix", "{\n  a = ;\n}\n");
        Path ugly = file("ugly.nix", "{a=1;b=2;}");

        assertThat(run("format", bad.toString(), ugly.toString())).isEqualTo(1);

        assertThat(err.toString()).startsWith(bad + ":2:7: error: ");
        assertThat(Files.getLastModifiedTime(bad)).isEqualTo(EPOCH);
        assertThat(read(ugly)).isEqualTo(FORMATTED);
        assertThat(err.toString()).containsPattern("\n1 file formatted, 0 unchanged, 1 failed in \\d+ms\n$");
    }

    @Test
    void formatFailsOnMissingFile() {
        assertThat(run("format", dir.resolve("missing.nix").toString())).isEqualTo(1);

        assertThat(err.toString()).contains("missing.nix: no such file");
    }

    @Test
    void formatRefusesInvalidUtf8() throws IOException {
        Path p = dir.resolve("latin1.nix");
        Files.write(p, new byte[] {'"', (byte) 0xe9, '"', ' '});

        assertThat(run("format", p.toString())).isEqualTo(1);

        assertThat(err.toString()).contains("not valid UTF-8");
        assertThat(Files.readAllBytes(p)).containsExactly('"', (byte) 0xe9, '"', ' ');
    }

    @Test
    void checkReportsWithoutWriting() throws IOException {
        String src = "let x = 1; in {a=2;}";
        Path p = file("a.nix", src);

        assertThat(run("check", p.toString())).isEqualTo(1);

        assertThat(out.toString()).isEqualTo(p + ":1:5: warning[unused-let-binding]: unused let binding 'x'\n");
        assertThat(read(p)).isEqualTo(src);
        assertThat(Files.getLastModifiedTime(p)).isEqualTo(EPOCH);
    }

    @Test
    void checkPassesOnHints() throws IOException {
        Path p = file("a.nix", "{ f }: map (x: f x) [ ]\n");

        assertThat(run("check", p.toString())).isZero();

        assertThat(out.toString()).contains("hint[eta-reduction]");
    }

    @Test
    void checkPassesOnCleanFiles() throws IOException {
        assertThat(run("check", file("a.nix", FORMATTED).toString())).isZero();

        assertThat(out.toString()).isEmpty();
    }

    @Test
    void checkFailsOnSyntaxError() throws IOException {
        Path p = file("bad.nix", "{ a = ; }");

        assertThat(run("check", p.toString())).isEqualTo(1);

        assertThat(out.toString()).startsWith(p + ":1:7: error[syntax-error]: ");
    }

    @Test
    void fixAppliesFixesThenFormats() throws IOException {
        Path p = file("a.nix", "let x = 1; in {a=(1);b=2;}");

        assertThat(run("fix", p.toString())).isZero();

        assertThat(read(p)).isEqualTo(FORMATTED);
        assertThat(err.toString())
                .matches("fixed " + Pattern.quote(p.toString()) + "\n1 file fixed, 0 unchanged in \\d+ms\n");
    }

    @Test
    void fixIsIdempotent() throws IOException {
        Path p = file("a.nix", "let x = 1; in {a=(1);b=2;}");
        assertThat(run("fix", p.toString())).isZero();
        Files.setLastModifiedTime(p, EPOCH);

        assertThat(run("fix", p.toString())).isZero();

        assertThat(Files.getLastModifiedTime(p)).isEqualTo(EPOCH);
        assertThat(err.toString()).containsPattern("\n0 files fixed, 1 unchanged in \\d+ms\n$");
    }

    @Test
    void fixFailsOnSyntaxError() throws IOException {
        Path p = file("bad.nix", "{ a = ; }");

        assertThat(run("fix", p.toString())).isEqualTo(1);

        assertThat(err.toString()).startsWith(p + ":1:7: error: ");
        assertThat(Files.getLastModifiedTime(p)).isEqualTo(EPOCH);
    }

    @Test
    void atSignIsAFileName() throws IOException {
        Path p = file("@a.nix", "{a=1;b=2;}");

        assertThat(run("format", p.toString())).isZero();

        assertThat(read(p)).isEqualTo(FORMATTED);
    }

    @Test
    void evalTakesEngineOptions() {
        assertThat(run("eval", "--engine.WarnInterpreterOnly=false", "-E", "1 + 1"))
                .isZero();
    }

    @Test
    void usageErrors() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("nope")).isEqualTo(2);
        assertThat(run("eval")).isEqualTo(2);
        assertThat(run("eval", "-E", "1", "a.nix")).isEqualTo(2);
        assertThat(run("eval", "-E", "1", "--arg", "x")).isEqualTo(2);
        assertThat(run("eval", "--no-such-option", "-E", "1")).isEqualTo(2);
        assertThat(run("format", "--width", "wide")).isEqualTo(2);
    }
}
