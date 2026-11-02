package org.graalnix.lint.test;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.graalnix.lint.Diagnostic;
import org.graalnix.lint.Linter;
import org.graalnix.lint.Rule;
import org.graalnix.parser.ParseException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the cases in cases/: for each X.nix, X.expected holds the diagnostics of rule X, with "[fix]" after those that
 * have one, and X.fixed.nix the output of {@link Linter#fix} with that rule alone.
 */
class LintTest {
    static final Path CASES = casesRoot();

    private static Path casesRoot() {
        try {
            URI jar = LintTest.class.getResource("/cases/extra-rec.nix").toURI();
            FileSystem fs = FileSystems.newFileSystem(jar, Map.of());
            return fs.getPath("/cases");
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String render(String src, String name, Set<Rule> rules) {
        return Linter.lint(src, rules).stream()
                .map(d -> d.render(src, name) + (d.fix().isEmpty() ? "" : " [fix]") + "\n")
                .collect(Collectors.joining());
    }

    @TestFactory
    Stream<DynamicTest> cases() throws IOException {
        List<Path> inputs;
        try (Stream<Path> s = Files.list(CASES)) {
            inputs = s.filter(
                            p -> p.toString().endsWith(".nix") && !p.toString().endsWith(".fixed.nix"))
                    .sorted()
                    .toList();
        }
        return inputs.stream()
                .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
                    String name = p.getFileName().toString();
                    String base = name.substring(0, name.length() - ".nix".length());
                    Set<Rule> rule = Set.of(Rule.byId(base));
                    String src = read(p);
                    assertThat(render(src, name, rule)).isEqualTo(read(CASES.resolve(base + ".expected")));
                    String fixed = Linter.fix(src, rule);
                    assertThat(fixed).isEqualTo(read(CASES.resolve(base + ".fixed.nix")));
                    assertThat(Linter.lint(fixed, rule))
                            .as("fixable diagnostics left")
                            .allMatch(d -> d.fix().isEmpty());
                }));
    }

    @Test
    void everyRuleHasACase() {
        Set<String> ids = Arrays.stream(Rule.values())
                .filter(r -> r != Rule.SYNTAX_ERROR)
                .map(Rule::id)
                .collect(Collectors.toSet());
        for (String id : ids) assertThat(CASES.resolve(id + ".nix")).exists();
    }

    @Test
    void syntaxErrorIsTheOnlyDiagnostic() {
        String src = "let x = 1; in\n{ a = ";
        assertThat(Linter.lint(src)).singleElement().satisfies(d -> {
            assertThat(d.rule()).isEqualTo(Rule.SYNTAX_ERROR);
            assertThat(d.render(src, "f.nix"))
                    .isEqualTo("f.nix:2:7: error[syntax-error]: syntax error, unexpected end of file");
        });
        assertThat(Linter.lint("{ a = 1; a = 2; }", Set.of(Rule.EXTRA_REC)))
                .extracting(Diagnostic::message)
                .containsExactly("attribute 'a' already defined");
        assertThatThrownBy(() -> Linter.fix(src)).isInstanceOf(ParseException.class);
    }

    @Test
    void lintsOnlyTheRulesAsked() {
        String src = "let a = (1); in with b; 2";
        assertThat(Linter.lint(src, Set.of(Rule.USELESS_PARENS, Rule.EXTRA_WITH)))
                .extracting(Diagnostic::rule)
                .containsExactly(Rule.USELESS_PARENS, Rule.EXTRA_WITH);
        assertThat(Linter.fix(src, Set.of(Rule.USELESS_PARENS))).isEqualTo("let a = 1; in with b; 2");
    }
}
