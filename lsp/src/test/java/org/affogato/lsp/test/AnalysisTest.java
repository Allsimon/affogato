package org.affogato.lsp.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.affogato.lsp.Analysis;
import org.affogato.lsp.Analysis.Completion;
import org.affogato.lsp.Analysis.Kind;
import org.affogato.lsp.Analysis.Rewrite;
import org.affogato.lsp.Analysis.Span;
import org.affogato.lsp.Evaluator;
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

    /** Options whose description is not evaluated yet while {@code pending}. */
    private static final class Options implements Evaluator {
        boolean pending = true;

        @Override
        public List<String> names(List<String> path) {
            return List.of();
        }

        @Override
        public List<Attribute> describeAll(List<List<String>> paths) {
            List<Attribute> none = new ArrayList<>();
            for (List<String> path : paths)
                none.add(new Attribute(null, null, null, null, null, null, null, null, null, null, 0, 0));
            return none;
        }

        @Override
        public void whenDescribed(List<List<String>> paths, Runnable then) {}

        @Override
        public List<String> optionNames(List<String> path) {
            return path.equals(List.of("services", "nginx"))
                    ? List.of("enable", "package", "proxy", "virtualHosts")
                    : List.of();
        }

        @Override
        public Attribute describeOption(List<String> path) {
            if (pending) return null;
            return switch (String.join(".", path)) {
                case "services.nginx.enable" -> option("/nginx.nix", 12, 5);
                case "services.nginx.package" -> option("/nginx.nix", 9, 7);
                case "services.nginx.proxy" -> option("/proxy.nix", 3, 3);
                default -> new Attribute(null, null, null, null, null, null, null, null, null, null, 0, 0);
            };
        }

        private static Attribute option(String file, int line, int column) {
            return new Attribute("option", "boolean", null, null, null, null, null, null, null, file, line, column);
        }

        @Override
        public void configureOptions(Map<String, String> expressions) {}

        @Override
        public Evaluator project(String key, Map<String, String> options) {
            return this;
        }

        @Override
        public void configure(String expression) {}

        @Override
        public void reset() {}
    }

    @Test
    void declaresAnOptionOnceEvaluated() {
        String text = "{ config, ... }: { services.nginx.enable = true; }";
        int offset = text.indexOf("enable");
        Options options = new Options();
        Analysis analysis = new Analysis(null, text, SyntaxTree.parse(text), options, NONE);

        assertThat(analysis.evaluating(offset)).isTrue();
        assertThat(analysis.declaration(offset)).isNull();
        options.pending = false;
        assertThat(analysis.evaluating(offset)).isFalse();
        assertThat(analysis.declaration(offset)).isEqualTo(new Analysis.Location("/nginx.nix", 11, 4));
        assertThat(analysis.evaluating(text.indexOf("{ config"))).isFalse();
    }

    @Test
    void declaresAGroupWhereMostOfItsOptionsAre() {
        String text = "{ config, ... }: { services.nginx.enable = true; }";
        int offset = text.indexOf("nginx");
        Options options = new Options();
        Analysis analysis = new Analysis(null, text, SyntaxTree.parse(text), options, NONE);

        assertThat(analysis.evaluating(offset)).isTrue();
        options.pending = false;
        assertThat(analysis.evaluating(offset)).isFalse();
        assertThat(analysis.declaration(offset)).isEqualTo(new Analysis.Location("/nginx.nix", 8, 6));
        assertThat(analysis.declaration(text.indexOf("services"))).isNull();
    }

    @Test
    void declaresAGroupAtItsBindingInTheFileOfItsOptions() {
        String nginx = "# 1\n# 2\n# 3\n# 4\n# 5\n# 6\n# 7\n{ options.services.nginx = {\n      package = 1;\n}; }\n";
        Analysis.Workspace files = new Analysis.Workspace() {
            @Override
            public SyntaxTree tree(String path) {
                return path.equals("/nginx.nix") ? SyntaxTree.parse(nginx) : null;
            }

            @Override
            public Map<String, SyntaxTree> files() {
                return Map.of();
            }
        };
        String text = "{ config, ... }: { services.nginx.enable = true; }";
        Options options = new Options();
        options.pending = false;
        Analysis analysis = new Analysis(null, text, SyntaxTree.parse(text), options, files);

        assertThat(analysis.declaration(text.indexOf("nginx"))).isEqualTo(new Analysis.Location("/nginx.nix", 7, 19));
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
