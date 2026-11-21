package org.affogato.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ParallelTest {
    private static String eval(String expr, int workers, long timeout) throws Exception {
        var options = new Interpreter.Options(
                "", "x86_64-linux", "/nix/store", Interpreter.Output.JSON, "", List.of(), workers);
        return Interpreter.evaluate(options, null, expr, timeout);
    }

    private static String outcome(String expr, int workers) throws Exception {
        try {
            return eval(expr, workers, 0);
        } catch (EvalException e) {
            return "error: " + e.getMessage();
        }
    }

    private static final String SLOW = "builtins.foldl' (a: b: a + b) 0 (builtins.genList (x: x) 200000)";

    @RepeatedTest(20)
    void reportsTheFirstErrorInOrder() throws Exception {
        String expr = "[ (" + SLOW + " + throw \"first\") 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 (throw \"second\") ]";
        assertThat(outcome(expr, 4)).isEqualTo("error: first");
    }

    @RepeatedTest(20)
    void reportsInfiniteRecursionAcrossHelpers() throws Exception {
        String expr = "let xs = builtins.genList (i: if i < 2 then builtins.elemAt xs (1 - i) else i) 64; in xs";
        assertThat(outcome(expr, 4)).isEqualTo(outcome(expr, 1)).contains("infinite recursion");
    }

    @Test
    void keepsWhatTryEvalCaught() throws Exception {
        String expr = "builtins.genList (i: (builtins.tryEval (if i == 3 then throw \"no\" else i)).value) 64";
        assertThat(outcome(expr, 4)).isEqualTo(outcome(expr, 1));
    }

    @Test
    void sharesWorkBetweenCells() throws Exception {
        String expr = "let s = " + SLOW + "; in builtins.genList (i: s + i) 64";
        assertThat(outcome(expr, 4)).isEqualTo(outcome(expr, 1));
    }

    @Test
    void stopsHelpersOnTimeout() {
        String inner = "builtins.foldl' (a: b: a + b) 0 (builtins.genList (x: x) 10000)";
        String expr =
                "builtins.genList (i: builtins.foldl' (a: b: a + " + inner + ") i (builtins.genList (x: x) 10000)) 64";
        assertThatThrownBy(() -> eval(expr, 4, 200))
                .isInstanceOf(EvalException.class)
                .hasMessage("evaluation timed out after 200 ms");
    }
}
