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
