package org.affogato.parser;

import java.util.HashMap;
import java.util.Map;

/** Mirrors the longest-match rules of Nix's flex lexer (src/libexpr/lexer.l). */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "if",
            TokenType.IF,
            "then",
            TokenType.THEN,
            "else",
            TokenType.ELSE,
            "assert",
            TokenType.ASSERT,
            "with",
            TokenType.WITH,
            "let",
            TokenType.LET,
            "in",
            TokenType.IN,
            "rec",
            TokenType.REC,
            "inherit",
            TokenType.INHERIT,
            "or",
            TokenType.OR_KW);

    public enum PartKind {
        LIT,
        INTERP,
        END
    }

    public record Part(PartKind kind, String text, boolean hasIndentation) {}

    private final String src;
    private final int len;
    private final Map<String, String> shared = new HashMap<>();
    int pos;

    public Lexer(String src) {
        this.src = src;
        this.len = src.length();
    }

    public int pos() {
        return pos;
    }

    private char at(int i) {
        return i < len ? src.charAt(i) : '\0';
    }

    /** One instance per distinct text, so that repeated names and strings share it. */
    private String share(String s) {
        String old = shared.putIfAbsent(s, s);
        return old != null ? old : s;
    }

    private boolean startsWith(String s, int i) {
        return src.startsWith(s, i);
    }

    static boolean isPathChar(char c) {
        return c >= 'a' && c <= 'z'
                || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9'
                || c == '.'
                || c == '_'
                || c == '-'
                || c == '+';
    }

    private static boolean isIdStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    private static boolean isIdChar(char c) {
        return isIdStart(c) || c >= '0' && c <= '9' || c == '\'' || c == '-';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipTrivia() {
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else if (c == '#') {
                while (pos < len && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') pos++;
            } else if (c == '/' && at(pos + 1) == '*') {
                int end = src.indexOf("*/", pos + 2);
                if (end < 0) throw new ParseException("unterminated comment", pos);
                pos = end + 2;
            } else {
                return;
            }
        }
    }

    private int matchId(int p) {
        if (!isIdStart(at(p))) return 0;
        int j = p + 1;
        while (isIdChar(at(j))) j++;
        return j - p;
    }

    private int matchInt(int p) {
        int j = p;
        while (isDigit(at(j))) j++;
        return j - p;
    }

    /** {@code (([1-9][0-9]*\.[0-9]*)|(0?\.[0-9]+))([Ee][+-]?[0-9]+)?}, as Nix's lexer. */
    private int matchFloat(int p) {
        int i = p;
        if (at(i) >= '1' && at(i) <= '9') {
            while (isDigit(at(i))) i++;
            if (at(i++) != '.') return 0;
            while (isDigit(at(i))) i++;
        } else {
            if (at(i) == '0') i++;
            if (at(i++) != '.' || !isDigit(at(i))) return 0;
            while (isDigit(at(i))) i++;
        }
        if (at(i) == 'e' || at(i) == 'E') {
            int j = i + 1;
            if (at(j) == '+' || at(j) == '-') j++;
            if (isDigit(at(j))) {
                while (isDigit(at(j))) j++;
                i = j;
            }
        }
        return i - p;
    }

    /** {@code [a-zA-Z][a-zA-Z0-9+\-.]*:[a-zA-Z0-9%/?:@&=+$,\-_.!~*']+}, as Nix's lexer. */
    private int matchUri(int p) {
        char c = at(p);
        if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')) return 0;
        int i = p + 1;
        while (isDigit(at(i)) || isLetter(at(i)) || at(i) == '+' || at(i) == '-' || at(i) == '.') i++;
        if (at(i++) != ':') return 0;
        int start = i;
        while (at(i) != '\0' && (isDigit(at(i)) || isLetter(at(i)) || "%/?:@&=+$,-_.!~*'".indexOf(at(i)) >= 0)) i++;
        return i > start ? i - p : 0;
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private int matchSegments(int j) {
        while (at(j) == '/' && isPathChar(at(j + 1))) {
            j++;
            while (isPathChar(at(j))) j++;
        }
        return j;
    }

    /** Length of a PATH or PATH_SEG-before-interpolation match, or 0. */
    private int matchPath(int p) {
        int j = p;
        while (isPathChar(at(j))) j++;
        int k = matchSegments(j);
        if (k > j) return at(k) == '/' ? k + 1 - p : k - p;
        if (at(j) == '/' && startsWith("${", j + 1)) return j + 1 - p;
        return 0;
    }

    private int matchHomePath(int p) {
        if (at(p) != '~' || at(p + 1) != '/') return 0;
        int k = matchSegments(p + 1);
        if (k > p + 1) return at(k) == '/' ? k + 1 - p : k - p;
        return startsWith("${", p + 2) ? 2 : 0;
    }

    private int matchSearchPath(int p) {
        if (at(p) != '<' || !isPathChar(at(p + 1))) return 0;
        int j = p + 1;
        while (isPathChar(at(j))) j++;
        j = matchSegments(j);
        return at(j) == '>' ? j + 1 - p : 0;
    }

    public Token next() {
        skipTrivia();
        int start = pos;
        if (pos >= len) return new Token(TokenType.EOF, "", start, start, false);
        char c = src.charAt(pos);

        TokenType best = null;
        int bestLen = 0;
        int n;
        if ((n = matchId(pos)) > bestLen) {
            best = TokenType.ID;
            bestLen = n;
        }
        if (isDigit(c) && (n = matchInt(pos)) > bestLen) {
            best = TokenType.INT;
            bestLen = n;
        }
        if ((isDigit(c) || c == '.') && (n = matchFloat(pos)) > bestLen) {
            best = TokenType.FLOAT;
            bestLen = n;
        }
        if ((isPathChar(c) || c == '/') && (n = matchPath(pos)) > bestLen) {
            best = TokenType.PATH;
            bestLen = n;
        }
        if (c == '~' && (n = matchHomePath(pos)) > bestLen) {
            best = TokenType.HPATH;
            bestLen = n;
        }
        if (c == '<' && (n = matchSearchPath(pos)) > bestLen) {
            best = TokenType.SPATH;
            bestLen = n;
        }
        if (isIdStart(c) && (n = matchUri(pos)) > bestLen) {
            best = TokenType.URI;
            bestLen = n;
        }
        if (best != null) {
            pos += bestLen;
            String text = share(src.substring(start, pos));
            if (best == TokenType.ID) {
                TokenType kw = KEYWORDS.get(text);
                if (kw != null) best = kw;
            }
            boolean interp = false;
            if (best == TokenType.PATH || best == TokenType.HPATH) {
                interp = startsWith("${", pos);
                if (!interp && text.endsWith("/"))
                    throw new ParseException("path '" + text + "' has a trailing slash", start);
            }
            return new Token(best, text, start, pos, interp);
        }

        if (startsWith("''", pos)) {
            pos += 2;
            int j = pos;
            while (at(j) == ' ') j++;
            if (at(j) == '\n') pos = j + 1;
            return pos == start + 2
                    ? new Token(TokenType.IND_STR_OPEN, "''", start, pos, false)
                    : tok(TokenType.IND_STR_OPEN, start);
        }
        if (c == '"') {
            pos++;
            return new Token(TokenType.STR_OPEN, "\"", start, pos, false);
        }
        for (Object[] op : OPERATORS) {
            String s = (String) op[0];
            if (startsWith(s, pos)) {
                pos += s.length();
                return new Token((TokenType) op[1], s, start, pos, false);
            }
        }
        throw new ParseException("syntax error, unexpected '" + c + "'", start);
    }

    private static final Object[][] OPERATORS = {
        {"...", TokenType.ELLIPSIS},
        {"${", TokenType.DOLLAR_CURLY},
        {"==", TokenType.EQ},
        {"!=", TokenType.NEQ},
        {"<=", TokenType.LEQ},
        {">=", TokenType.GEQ},
        {"&&", TokenType.AND},
        {"||", TokenType.OR},
        {"->", TokenType.IMPL},
        {"//", TokenType.UPDATE},
        {"++", TokenType.CONCAT},
        {"|>", TokenType.PIPE_INTO},
        {"<|", TokenType.PIPE_FROM},
        {"{", TokenType.LBRACE},
        {"}", TokenType.RBRACE},
        {"[", TokenType.LBRACK},
        {"]", TokenType.RBRACK},
        {"(", TokenType.LPAREN},
        {")", TokenType.RPAREN},
        {";", TokenType.SEMI},
        {":", TokenType.COLON},
        {",", TokenType.COMMA},
        {".", TokenType.DOT},
        {"@", TokenType.AT},
        {"?", TokenType.QUESTION},
        {"=", TokenType.ASSIGN},
        {"+", TokenType.PLUS},
        {"-", TokenType.MINUS},
        {"*", TokenType.STAR},
        {"/", TokenType.SLASH},
        {"<", TokenType.LT},
        {">", TokenType.GT},
        {"!", TokenType.NOT},
    };

    private Token tok(TokenType type, int start) {
        return new Token(type, src.substring(start, pos), start, pos, false);
    }

    /** Reads the next piece of a {@code "..."} string, right after its opening quote or an interpolation. */
    public Part stringPart() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= len) throw new ParseException("unterminated string", pos);
            char c = src.charAt(pos);
            if (c == '"' || c == '$' && at(pos + 1) == '{') {
                if (!sb.isEmpty()) return new Part(PartKind.LIT, share(sb.toString()), true);
                pos += c == '"' ? 1 : 2;
                return new Part(c == '"' ? PartKind.END : PartKind.INTERP, null, false);
            }
            if (c == '$' && at(pos + 1) == '$') {
                sb.append("$$");
                pos += 2;
            } else if (c == '\\') {
                if (pos + 1 >= len) throw new ParseException("unterminated string", pos);
                char e = src.charAt(pos + 1);
                pos += 2;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\r' -> {
                        sb.append('\n');
                        if (at(pos) == '\n') pos++;
                    }
                    default -> sb.append(e);
                }
            } else if (c == '\r') {
                sb.append('\n');
                pos++;
                if (at(pos) == '\n') pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
    }

    /** Reads the next piece of a {@code ''...''} string. */
    public Part indStringPart() {
        int start = pos;
        while (pos < len) {
            char c = src.charAt(pos);
            char d = at(pos + 1);
            if (c == '$' && d != '{' && d != '\'' && pos + 1 < len) {
                pos += 2;
            } else if (c == '\'' && d != '\'' && d != '$' && pos + 1 < len) {
                pos += 2;
            } else if (c != '$' && c != '\'') {
                pos++;
            } else {
                break;
            }
        }
        if (pos > start) return new Part(PartKind.LIT, src.substring(start, pos), true);
        if (pos >= len) throw new ParseException("unterminated indented string", pos);
        if (startsWith("'''", pos)) {
            pos += 3;
            return new Part(PartKind.LIT, "''", false);
        }
        if (startsWith("''$", pos)) {
            pos += 3;
            return new Part(PartKind.LIT, "$", false);
        }
        if (startsWith("''\\", pos)) {
            if (pos + 3 >= len) throw new ParseException("unterminated indented string", pos);
            char e = src.charAt(pos + 3);
            pos += 4;
            String s = switch (e) {
                case 'n' -> "\n";
                case 'r' -> "\r";
                case 't' -> "\t";
                default -> String.valueOf(e);
            };
            return new Part(PartKind.LIT, s, false);
        }
        if (startsWith("${", pos)) {
            pos += 2;
            return new Part(PartKind.INTERP, null, false);
        }
        if (startsWith("''", pos)) {
            pos += 2;
            return new Part(PartKind.END, null, false);
        }
        pos++;
        return new Part(PartKind.LIT, src.substring(pos - 1, pos), false);
    }

    /** Reads the next piece of an interpolated path, right after an interpolation. */
    public Part pathPart() {
        if (startsWith("${", pos)) {
            pos += 2;
            return new Part(PartKind.INTERP, null, false);
        }
        int start = pos;
        while (isPathChar(at(pos)) || at(pos) == '/') pos++;
        if (pos > start) return new Part(PartKind.LIT, src.substring(start, pos), false);
        return new Part(PartKind.END, null, false);
    }
}
