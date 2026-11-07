package org.affogato.parser;

/**
 * A token of {@link Tokenizer}'s lossless stream; {@code end} is exclusive. {@code topLevel} marks one outside every
 * string, interpolation and path, where tokenizing the source from {@code start} gives the same lexemes.
 */
public record Lexeme(Kind kind, int start, int end, boolean topLevel) {
    public enum Kind {
        ID("identifier"),
        INT("integer"),
        FLOAT("float"),
        PATH("path"),
        HPATH("path"),
        SPATH("search path"),
        URI("URI"),
        STR_OPEN("'\"'"),
        IND_STR_OPEN("\"''\""),
        DOLLAR_CURLY("'${'"),
        IF("'if'"),
        THEN("'then'"),
        ELSE("'else'"),
        ASSERT("'assert'"),
        WITH("'with'"),
        LET("'let'"),
        IN("'in'"),
        REC("'rec'"),
        INHERIT("'inherit'"),
        OR_KW("'or'"),
        ELLIPSIS("'...'"),
        EQ("'=='"),
        NEQ("'!='"),
        LEQ("'<='"),
        GEQ("'>='"),
        AND("'&&'"),
        OR("'||'"),
        IMPL("'->'"),
        UPDATE("'//'"),
        CONCAT("'++'"),
        PIPE_FROM("'<|'"),
        PIPE_INTO("'|>'"),
        LBRACE("'{'"),
        RBRACE("'}'"),
        LBRACK("'['"),
        RBRACK("']'"),
        LPAREN("'('"),
        RPAREN("')'"),
        SEMI("';'"),
        COLON("':'"),
        COMMA("','"),
        DOT("'.'"),
        AT("'@'"),
        QUESTION("'?'"),
        ASSIGN("'='"),
        PLUS("'+'"),
        MINUS("'-'"),
        STAR("'*'"),
        SLASH("'/'"),
        LT("'<'"),
        GT("'>'"),
        NOT("'!'"),
        STR_CLOSE("'\"'"),
        IND_STR_CLOSE("\"''\""),
        /** Literal text of a string, as written. */
        STR_TEXT("string"),
        /** {@code \x} in a {@code "..."} string; {@code '''}, {@code ''$} or {@code ''\x} in an indented one. */
        STR_ESCAPE("escape"),
        /** A piece of a path after an interpolation. */
        PATH_TEXT("path"),
        WHITESPACE("whitespace"),
        COMMENT("comment"),
        BAD_CHARACTER("bad character");

        private final String display;

        Kind(String display) {
            this.display = display;
        }

        /** How an error message names it. */
        public String display() {
            return display;
        }

        static Kind of(TokenType type) {
            return valueOf(type.name());
        }
    }
}
