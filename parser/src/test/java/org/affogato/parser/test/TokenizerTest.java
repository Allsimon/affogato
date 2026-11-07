package org.affogato.parser.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.affogato.parser.Lexeme;
import org.affogato.parser.Lexeme.Kind;
import org.affogato.parser.Tokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TokenizerTest {
    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#all")
    void coversEveryPrefix(String sample) {
        Samples.prefixes(sample).forEach(src -> {
            int at = 0;
            for (Lexeme l : Tokenizer.tokenize(src)) {
                assertThat(l.start()).as("%s at %s", src, l).isEqualTo(at);
                assertThat(l.end()).as("%s at %s", src, l).isGreaterThan(l.start());
                at = l.end();
            }
            assertThat(at).as(src).isEqualTo(src.length());
        });
    }

    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#all")
    void restartsAtATopLevelLexeme(String sample) {
        Stream<String> sources = sample.length() <= 60 ? Samples.prefixes(sample) : Stream.of(sample);
        sources.forEach(src -> {
            List<Lexeme> all = Tokenizer.tokenize(src);
            for (Lexeme from : all) {
                if (!from.topLevel()) continue;
                List<Lexeme> rest = Tokenizer.tokenize(src.substring(from.start())).stream()
                        .map(l -> new Lexeme(l.kind(), l.start() + from.start(), l.end() + from.start(), l.topLevel()))
                        .toList();
                assertThat(rest).as("%s from %s", src, from).isEqualTo(all.subList(all.indexOf(from), all.size()));
            }
        });
    }

    @Test
    void stringsAndInterpolationsAreNotTopLevel() {
        assertThat(Tokenizer.tokenize("a \"b${c}\" d").stream()
                        .map(l -> l.kind() + (l.topLevel() ? "" : "*"))
                        .toList())
                .containsExactly(
                        "ID",
                        "WHITESPACE",
                        "STR_OPEN",
                        "STR_TEXT*",
                        "DOLLAR_CURLY*",
                        "ID*",
                        "RBRACE*",
                        "STR_CLOSE*",
                        "WHITESPACE",
                        "ID");
    }

    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#valid")
    void aValidSourceHasNoBadCharacter(String src) {
        assertThat(Tokenizer.tokenize(src)).noneMatch(l -> l.kind() == Kind.BAD_CHARACTER);
    }

    private static List<String> kinds(String src) {
        return Tokenizer.tokenize(src).stream()
                .filter(l -> l.kind() != Kind.WHITESPACE)
                .map(l -> l.kind() + " " + src.substring(l.start(), l.end()))
                .toList();
    }

    @Test
    void string() {
        assertThat(kinds("\"a\\n${b}c\""))
                .containsExactly(
                        "STR_OPEN \"",
                        "STR_TEXT a",
                        "STR_ESCAPE \\n",
                        "DOLLAR_CURLY ${",
                        "ID b",
                        "RBRACE }",
                        "STR_TEXT c",
                        "STR_CLOSE \"");
    }

    @Test
    void indentedString() {
        assertThat(kinds("''a''$${ {} }''"))
                .containsExactly(
                        "IND_STR_OPEN ''",
                        "STR_TEXT a",
                        "STR_ESCAPE ''$",
                        "DOLLAR_CURLY ${",
                        "LBRACE {",
                        "RBRACE }",
                        "RBRACE }",
                        "IND_STR_CLOSE ''");
    }

    @Test
    void interpolatedPath() {
        assertThat(kinds("./a/${b}/c"))
                .containsExactly("PATH ./a/", "DOLLAR_CURLY ${", "ID b", "RBRACE }", "PATH_TEXT /c");
    }

    @Test
    void comments() {
        assertThat(kinds("# a\n/* b */x")).containsExactly("COMMENT # a", "COMMENT /* b */", "ID x");
    }

    @Test
    void errors() {
        assertThat(kinds("é /* c")).containsExactly("BAD_CHARACTER é", "BAD_CHARACTER /*", "COMMENT  c");
        assertThat(kinds("\"a ${b")).containsExactly("STR_OPEN \"", "STR_TEXT a ", "DOLLAR_CURLY ${", "ID b");
    }
}
