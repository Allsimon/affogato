package org.affogato.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/** Keeps the linter's hard-coded names in step with the evaluator's. */
class LintGlobalsTest {
    /** Builtins Nix has with the flakes feature, which affogato does not implement. */
    private static final Set<String> FLAKES = Set.of("flakeRefToString", "parseFlakeRef");

    private static String eval(Context ctx, String expr) {
        return ctx.eval("nix", expr).asString();
    }

    @Test
    void linterKnowsTheBuiltinsAndGlobals() {
        try (Context ctx = Context.newBuilder("nix")
                .allowAllAccess(true)
                .option("nix.Output", "raw")
                .build()) {
            List<String> builtins =
                    Arrays.asList(eval(ctx, "builtins.concatStringsSep \" \" (builtins.attrNames builtins)")
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
                    eval(ctx, "let x = " + name + "; in \"\"");
                    defined = true;
                } catch (PolyglotException e) {
                    assertThat(e.getMessage()).contains("undefined variable");
                    defined = false;
                }
                boolean flagged =
                        !Linter.lint(name, Set.of(Rule.UNDEFINED_VARIABLE)).isEmpty();
                assertThat(flagged).as(name).isEqualTo(!defined);
            }
        }
    }
}
