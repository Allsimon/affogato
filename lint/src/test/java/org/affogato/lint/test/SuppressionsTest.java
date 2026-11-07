package org.affogato.lint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.affogato.lint.Diagnostic;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.affogato.lint.Suppressions;
import org.junit.jupiter.api.Test;

class SuppressionsTest {
    private static List<String> rules(String src) {
        return Linter.lint(src).stream().map(d -> d.rule().id()).toList();
    }

    @Test
    void theLineAboveSuppressesTheNextLine() {
        assertThat(rules("x: [\n  # affogato: ignore bool-comparison\n  (x == true)\n  (x == false)\n]\n"))
                .containsExactly("bool-comparison");
    }

    @Test
    void aTrailingCommentSuppressesItsLine() {
        assertThat(rules("x: (x == true) # affogato: ignore bool-comparison, unused-let-binding\n"))
                .isEmpty();
    }

    @Test
    void aFileCommentSuppressesEverywhere() {
        assertThat(rules("/* affogato: ignore-file bool-comparison */\nx: [ (x == true) (x == false) ]\n"))
                .isEmpty();
    }

    @Test
    void otherRulesAndStringsStayInForce() {
        assertThat(rules("x: [ \"# affogato: ignore-file bool-comparison\" (x == true) ] # affogato: ignore x\n"))
                .containsExactly("bool-comparison");
    }

    @Test
    void aSyntaxErrorCannotBeSuppressed() {
        assertThat(rules("# affogato: ignore-file syntax-error\n{")).containsExactly("syntax-error");
    }

    @Test
    void theCommentsItWritesSuppress() {
        assertThat(rules("x:\n" + Suppressions.ignore(Rule.BOOL_COMPARISON) + "\nx == true\n"))
                .isEmpty();
        assertThat(rules(Suppressions.ignoreFile(Rule.BOOL_COMPARISON) + "\nx: x == true\n"))
                .isEmpty();
    }

    @Test
    void fixLeavesSuppressedFindings() {
        String src = "x: x == true # affogato: ignore bool-comparison\n";
        assertThat(Linter.fix(src)).isEqualTo(src);
        assertThat(Linter.lint(src)).extracting(Diagnostic::rule).isEmpty();
    }
}
