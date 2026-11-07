package org.affogato.parser.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.affogato.parser.NodeKind;
import org.affogato.parser.Scopes;
import org.affogato.parser.SyntaxTree;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code <ref>} marks the variable, {@code <def>} the definition it resolves to. */
class ScopesTest {
    private static SyntaxTree.Node resolve(String source) {
        String src = source.replace("<def>", "").replace("<ref>", "");
        int ref = source.replace("<def>", "").indexOf("<ref>");
        SyntaxTree tree = SyntaxTree.parse(src);
        var view = tree.view();
        SyntaxTree.Node name = tree.at(ref).parent();
        SyntaxTree.Node from = name.kind == NodeKind.ATTR_NAME ? Scopes.inheritedFrom(view, name) : name;
        return Scopes.resolve(view, tree.text(tree.at(ref)), from);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "let <def>a = 1; in <ref>a",
                "<def>x: <ref>x",
                "<def>args @ { a }: <ref>args",
                "{ <def>a, b ? <ref>a }: b",
                "{ a, <def>b ? 1 }: <ref>b",
                "rec { <def>a.b = 1; c = <ref>a; }",
                "let a = 1; in <def>a: <ref>a",
                "let <def>a = 1; in { inherit <ref>a; }",
                "let inherit (pkgs) <def>hello; in <ref>hello",
                "<def>a: let inherit <ref>a; in a",
                "let <def>a = 1; in { b = <ref>a +",
            })
    void resolves(String source) {
        SyntaxTree.Node definition = resolve(source);
        assertThat(definition).isNotNull();
        assertThat(definition.start()).isEqualTo(source.replace("<ref>", "").indexOf("<def>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ a = 1; c = <ref>a; }", "with pkgs; <ref>hello", "<ref>undefined"})
    void staysUnresolved(String source) {
        assertThat(resolve(source)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "let foo = 1; inherit (x) far; in { bar }: f|",
                "let foo = 2; in let foo = 1; far = 3; bar = 4; in f|"
            })
    void listsTheNamesInScope(String source) {
        String src = source.replace("|", "");
        SyntaxTree tree = SyntaxTree.parse(src);
        var view = tree.view();
        var names = Scopes.visible(view, tree.at(source.indexOf('|')).parent()).stream()
                .map(n -> Scopes.name(view, n))
                .toList();
        assertThat(names).contains("foo", "far", "bar").doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#all")
    void everyPrefixBuildsATreeOverItsSource(String sample) {
        Samples.prefixes(sample).forEach(src -> {
            SyntaxTree tree = SyntaxTree.parse(src);
            assertThat(tree.root().end()).isEqualTo(src.length());
            check(tree.root());
        });
    }

    private static void check(SyntaxTree.Node node) {
        int at = node.start();
        for (SyntaxTree.Node child : node.children()) {
            assertThat(child.parent()).isSameAs(node);
            assertThat(child.start()).isGreaterThanOrEqualTo(at);
            assertThat(child.end()).isLessThanOrEqualTo(node.end());
            at = child.end();
            check(child);
        }
    }
}
