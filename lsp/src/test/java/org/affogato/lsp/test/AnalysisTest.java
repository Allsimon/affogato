package org.affogato.lsp.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.affogato.lsp.Analysis;
import org.affogato.lsp.Analysis.Completion;
import org.affogato.lsp.Analysis.Kind;
import org.affogato.lsp.Analysis.Rewrite;
import org.affogato.lsp.Analysis.Span;
import org.affogato.parser.SyntaxTree;
import org.junit.jupiter.api.Test;

class AnalysisTest {
    private static final Analysis.Workspace NONE = new Analysis.Workspace() {
        @Override
        public SyntaxTree tree(String path) {
            return null;
        }

        @Override
        public Map<String, SyntaxTree> files() {
            return Map.of();
        }
    };

    private static Analysis analysis(String text) {
        return new Analysis("/p/default.nix", text, SyntaxTree.parse(text), null, NONE);
    }

    @Test
    void completesLocalNamesBuiltinsAndKeywords() {
        String text = "let abc = 1; in a";
        Analysis.Completions completions = analysis(text).completion(text.length(), false);

        assertThat(completions.evaluated()).isFalse();
        assertThat(completions.items())
                .contains(
                        Completion.of("abc", Kind.VARIABLE),
                        new Completion("true", Kind.CONSTANT, "builtin", null, null, false),
                        Completion.of("let", Kind.KEYWORD))
                .noneMatch(c -> c.kind() == Kind.SNIPPET);
    }

    @Test
    void completesAttributesOfLocalSets() {
        String text = "let s = { x = 1; y = 2; }; in s.";

        assertThat(analysis(text).completion(text.length(), false).items())
                .extracting(Completion::label)
                .containsExactly("x", "y");
    }

    @Test
    void definesSelectedAttributesInTheirSet() {
        String text = "let s = { x = 1; }; in s.x";

        assertThat(analysis(text).definition(text.length() - 1)).isEqualTo(new Span(null, 10, 11));
    }

    @Test
    void unquotesAttributeNames() {
        String text = "{ \"a\" = 1; }";

        assertThat(analysis(text).attributeQuotes(3)).isEqualTo(new Rewrite("Unquote the attribute name", 2, 5, "a"));
        assertThat(analysis(text).stringQuotes(3)).isNull();
    }

    @Test
    void formatsTheBindingInRange() {
        String text = "{\n  a   =   1;\n  b=2;\n}\n";

        assertThat(Analysis.formatRange(text, 5, 6, 100, 2, false)).isEqualTo(new Rewrite("Format", 2, 14, "  a = 1;"));
    }

    @Test
    void linksUrls() {
        String text = "{ url = \"https://example.org\"; }";

        assertThat(analysis(text).links()).containsExactly(new Analysis.Link(9, 28, "https://example.org", false));
    }
}
