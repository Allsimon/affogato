package org.affogato.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.junit.jupiter.api.Test;

/** Keeps the linter's hard-coded names in step with the evaluator's. */
class LintGlobalsTest {
    /** Builtins Nix has with the flakes feature, which affogato does not implement. */
    private static final Set<String> FLAKES = Set.of("flakeRefToString", "parseFlakeRef");

    private static String eval(String expr) throws Exception {
        var options = new Interpreter.Options("", "", "", Interpreter.Output.RAW, "", List.of());
        return Interpreter.evaluate(options, null, expr, 0);
    }

    @Test
    void linterKnowsTheBuiltinsAndGlobals() throws Exception {
        List<String> builtins = Arrays.asList(eval("builtins.concatStringsSep \" \" (builtins.attrNames builtins)")
                .split(" "));
        List<String> expected = new ArrayList<>(builtins);
        expected.addAll(FLAKES);
        assertThat(Linter.BUILTINS).containsExactlyInAnyOrderElementsOf(expected);

        List<String> names = new ArrayList<>();
        for (String b : builtins) {
            names.add(b);
            names.add("__" + b);
        }
        for (String name : names) {
            if (name.contains("'")) continue;
            boolean defined;
            try {
                eval("let x = " + name + "; in \"\"");
                defined = true;
            } catch (EvalException e) {
                assertThat(e.getMessage()).contains("undefined variable");
                defined = false;
            }
            boolean flagged =
                    !Linter.lint(name, Set.of(Rule.UNDEFINED_VARIABLE)).isEmpty();
            assertThat(flagged).as(name).isEqualTo(!defined);
        }
    }
}
