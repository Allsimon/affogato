package org.affogato.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterpreterTest {
    private static String eval(Interpreter.Output output, String expr, long timeout) throws Exception {
        var options = new Interpreter.Options("", "x86_64-linux", "/nix/store", output, "", List.of());
        return Interpreter.evaluate(options, null, expr, timeout);
    }

    @Test
    void rendersEachOutput() throws Exception {
        assertThat(eval(Interpreter.Output.EVAL, "{ a = 1 + 1; }", 0)).isEqualTo("{ a = <CODE>; }");
        assertThat(eval(Interpreter.Output.JSON, "{ a = [ 1 \"b\" ]; }", 0)).isEqualTo("{\"a\":[1,\"b\"]}");
        assertThat(eval(Interpreter.Output.RAW, "\"a\\nb\"", 0)).isEqualTo("a\nb");
    }

    @Test
    void reReadsAnImportedFileEditedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        String expr = "import " + file;
        Files.writeString(file, "1");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        Files.writeString(file, "2");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("2");
    }

    @Test
    void printsTheEntryTypesOfReadDir(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("d"));
        Files.writeString(dir.resolve("f"), "");
        assertThat(eval(Interpreter.Output.EVAL, "builtins.readDir " + dir, 0))
                .isEqualTo("{ d = \"directory\"; f = \"regular\"; }");
    }

    @Test
    void listToAttrsKeepsTheFirstOfEqualNames() throws Exception {
        assertThat(eval(
                        Interpreter.Output.JSON,
                        "builtins.listToAttrs [ { name = \"b\"; value = 1; } { name = \"a\"; value = 2; }"
                                + " { name = \"b\"; value = 3; } ]",
                        0))
                .isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    void reReadsADirectoryChangedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a"), "");
        Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(0));
        String expr = "builtins.attrNames (builtins.readDir " + dir + ")";
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[\"a\"]");
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[\"a\"]");
        Files.writeString(dir.resolve("b"), "");
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void sharesTranslatedFilesAcrossContextsWithTheirOwnGlobals(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        Files.writeString(file, "[ builtins.storeDir __storeDir (builtins.isFunction derivation) ]");
        for (String store : List.of("/a", "/b", "/a")) {
            var options = new Interpreter.Options("", "x86_64-linux", store, Interpreter.Output.JSON, "", List.of());
            assertThat(Interpreter.evaluate(options, null, "import " + file, 0))
                    .isEqualTo("[\"" + store + "\",\"" + store + "\",true]");
        }
    }

    @Test
    void reHashesAFileChangedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a");
        Files.writeString(file, "1");
        Thread.sleep(1100);
        String expr = "\"${" + file + "}\"";
        String first = eval(Interpreter.Output.RAW, expr, 0);
        assertThat(eval(Interpreter.Output.RAW, expr, 0)).isEqualTo(first);
        Files.writeString(file, "2");
        assertThat(eval(Interpreter.Output.RAW, expr, 0)).isNotEqualTo(first).endsWith("-a");
    }

    @Test
    void intersectAttrsTakesTheLastOfAUnion() throws Exception {
        String y = "builtins.listToAttrs (map (n: { name = n; value = 1; })"
                + " [ \"a\" \"b\" \"c\" \"d\" \"e\" \"f\" \"g\" \"h\" \"i\" \"j\" ]) // { b = 2; k = 3; }";
        assertThat(eval(
                        Interpreter.Output.JSON,
                        "builtins.intersectAttrs { b = 0; c = 0; k = 0; z = 0; } (" + y + ")",
                        0))
                .isEqualTo("{\"b\":2,\"c\":1,\"k\":3}");
    }

    @Test
    void reReadsASettledImportedFileEditedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        String expr = "import " + file;
        Files.writeString(file, "1");
        Thread.sleep(1100);
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        Files.writeString(file, "2");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("2");
    }

    @Test
    void stopsAtTheTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> eval(
                        Interpreter.Output.EVAL,
                        "let l = builtins.genList (x: x) 10000; in"
                                + " builtins.foldl' (a: i: a + builtins.foldl' (b: j: b + j) 0 l) 0 l",
                        100))
                .isInstanceOf(EvalException.class)
                .hasMessage("evaluation timed out after 100 ms");
        assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
    }

    @Test
    void reportsTheLocationOfTheFailingExpression() {
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, "1 + { a = 1; }.b", 0))
                .isInstanceOf(EvalException.class)
                .hasMessage("attribute 'b' missing")
                .extracting(e -> ((EvalException) e).location)
                .isEqualTo("\u00c2\u00abstring\u00c2\u00bb:1:5");
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, "throw \"no\"", 0))
                .extracting(e -> ((EvalException) e).location)
                .isNull();
    }

    @Test
    void turnsAJavaStackOverflowIntoAnError() {
        assertThatThrownBy(() -> eval(
                        Interpreter.Output.STRICT,
                        "builtins.foldl' (acc: _: [ acc ]) [ ] (builtins.genList (x: x) 30000000)",
                        0))
                .isInstanceOf(EvalException.class)
                .hasMessage("stack overflow");
    }

    @Test
    void internalErrorsAreNotEvaluationErrors() {
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("internal error");
    }
}
