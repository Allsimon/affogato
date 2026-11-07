package org.affogato.parser.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TreeParserTest {
    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#valid")
    void aValidSourceHasNoError(String src) {
        assertThat(Tree.errors(src)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#invalid")
    void anInvalidSourceHasAnError(String src) {
        assertThat(Tree.errors(src)).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("org.affogato.parser.test.Samples#all")
    void everyPrefixParses(String sample) {
        Samples.prefixes(sample).forEach(Tree::parse);
    }

    @Test
    void lambda() {
        assertThat(Tree.parse("{ a ? 1, ... }@args: a")).isEqualTo("""
            ROOT
              LAMBDA
                FORMALS
                  LBRACE {
                  FORMAL
                    ID a
                    QUESTION ?
                    INT
                      INT 1
                  COMMA ,
                  ELLIPSIS ...
                  RBRACE }
                AT @
                PARAM
                  ID args
                COLON :
                VAR
                  ID a
            """);
    }

    @Test
    void precedence() {
        assertThat(Tree.parse("a + b * c")).isEqualTo("""
            ROOT
              BINARY
                VAR
                  ID a
                PLUS +
                BINARY
                  VAR
                    ID b
                  STAR *
                  VAR
                    ID c
            """);
    }

    @Test
    void leftAssociative() {
        assertThat(Tree.parse("a - b - c")).isEqualTo("""
            ROOT
              BINARY
                BINARY
                  VAR
                    ID a
                  MINUS -
                  VAR
                    ID b
                MINUS -
                VAR
                  ID c
            """);
    }

    @Test
    void bindingWithString() {
        assertThat(Tree.parse("{ a.b = \"x${y}\"; }")).isEqualTo("""
            ROOT
              ATTR_SET
                LBRACE {
                BINDING
                  ATTR_PATH
                    ATTR_NAME
                      ID a
                    DOT .
                    ATTR_NAME
                      ID b
                  ASSIGN =
                  STRING
                    STR_OPEN "
                    STR_TEXT x
                    INTERPOLATION
                      DOLLAR_CURLY ${
                      VAR
                        ID y
                      RBRACE }
                    STR_CLOSE "
                  SEMI ;
                RBRACE }
            """);
    }

    @Test
    void recoversAfterAMissingValue() {
        assertThat(Tree.parse("{ a = ; b = 1; }")).isEqualTo("""
            ROOT
              ATTR_SET
                LBRACE {
                BINDING
                  ATTR_PATH
                    ATTR_NAME
                      ID a
                  ASSIGN =
                  ERROR expected expression
                  SEMI ;
                BINDING
                  ATTR_PATH
                    ATTR_NAME
                      ID b
                  ASSIGN =
                  INT
                    INT 1
                  SEMI ;
                RBRACE }
            """);
    }

    @Test
    void recoversAfterAMissingSemicolon() {
        assertThat(Tree.errors("{ a = 1; b = 2 }")).containsExactly("ERROR expected ';'");
    }
}
