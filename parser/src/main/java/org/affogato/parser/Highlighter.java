package org.affogato.parser;

import static org.affogato.parser.TokenType.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Classifies the source by lexing alone, so that it survives syntax errors; a lexing error ends it. */
public final class Highlighter {
    public enum Kind {
        KEYWORD,
        STRING,
        ESCAPE,
        INTERP,
        COMMENT,
        CONSTANT,
        BUILTIN,
        NUMBER,
        PATH,
        OPERATOR,
        ATTR,
        PARAM;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Span(int start, int end, Kind kind) {}

    private static final Set<TokenType> KEYWORDS = Set.of(IF, THEN, ELSE, ASSERT, WITH, LET, IN, REC, INHERIT, OR_KW);
    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");
    private static final Set<String> BUILTINS = Set.of(
            "abort",
            "baseNameOf",
            "break",
            "builtins",
            "derivation",
            "dirOf",
            "fetchGit",
            "fetchMercurial",
            "fetchTarball",
            "fetchTree",
            "fromTOML",
            "import",
            "isNull",
            "map",
            "placeholder",
            "removeAttrs",
            "scopedImport",
            "throw",
            "toString");

    private final String src;
    private final Lexer lexer;
    private final List<Span> spans = new ArrayList<>();
    /** The tokens outside string literals, a string standing as its STR_OPEN, for the classification by neighbours. */
    private final List<Token> tokens = new ArrayList<>();

    private final List<Kind> kinds = new ArrayList<>();

    private Highlighter(String src) {
        this.src = src;
        this.lexer = new Lexer(src);
    }

    /** The classified spans of {@code src}, sorted and disjoint; text in none of them is plain. */
    public static List<Span> highlight(String src) {
        Highlighter h = new Highlighter(src);
        try {
            h.code(false);
        } catch (ParseException e) {
            // The spans so far stand.
        }
        h.classify();
        return h.merged();
    }

    /** Lexes code up to the end, or in an interpolation up to its closing brace. */
    private void code(boolean interpolation) {
        Deque<Boolean> braces = new ArrayDeque<>();
        while (true) {
            int before = lexer.pos();
            Token t = lexer.next();
            comments(before, t.start());
            switch (t.type()) {
                case EOF -> {
                    return;
                }
                case STR_OPEN -> {
                    add(t, Kind.STRING);
                    string(t);
                }
                case IND_STR_OPEN -> {
                    add(t, Kind.STRING);
                    indString(t);
                }
                case LBRACE -> {
                    braces.push(false);
                    add(t, null);
                }
                case DOLLAR_CURLY -> {
                    braces.push(true);
                    add(t, Kind.INTERP);
                }
                case RBRACE -> {
                    if (braces.isEmpty() && interpolation) {
                        add(t, Kind.INTERP);
                        return;
                    }
                    add(t, !braces.isEmpty() && braces.pop() ? Kind.INTERP : null);
                }
                case PATH, HPATH -> {
                    add(t, null);
                    if (t.interp()) path();
                }
                default -> add(t, null);
            }
        }
    }

    private void string(Token open) {
        spans.add(new Span(open.start(), open.end(), Kind.STRING));
        while (true) {
            int start = lexer.pos();
            Lexer.Part part = lexer.stringPart();
            int end = lexer.pos();
            switch (part.kind()) {
                case LIT -> {
                    for (int i = start; i < end; ) {
                        int next = src.charAt(i) == '\\' ? Math.min(i + 2, end) : i + 1;
                        spans.add(new Span(i, next, next - i == 2 ? Kind.ESCAPE : Kind.STRING));
                        i = next;
                    }
                }
                case INTERP -> {
                    spans.add(new Span(end - 2, end, Kind.INTERP));
                    code(true);
                }
                case END -> {
                    spans.add(new Span(end - 1, end, Kind.STRING));
                    return;
                }
            }
        }
    }

    private void indString(Token open) {
        spans.add(new Span(open.start(), open.end(), Kind.STRING));
        while (true) {
            int start = lexer.pos();
            Lexer.Part part = lexer.indStringPart();
            int end = lexer.pos();
            switch (part.kind()) {
                case LIT ->
                    spans.add(
                            new Span(start, end, part.hasIndentation() || end - start < 3 ? Kind.STRING : Kind.ESCAPE));
                case INTERP -> {
                    spans.add(new Span(end - 2, end, Kind.INTERP));
                    code(true);
                }
                case END -> {
                    spans.add(new Span(end - 2, end, Kind.STRING));
                    return;
                }
            }
        }
    }

    /** The rest of a path after its head, which an interpolation follows. */
    private void path() {
        while (true) {
            int start = lexer.pos();
            Lexer.Part part = lexer.pathPart();
            int end = lexer.pos();
            switch (part.kind()) {
                case LIT -> spans.add(new Span(start, end, Kind.PATH));
                case INTERP -> {
                    spans.add(new Span(end - 2, end, Kind.INTERP));
                    code(true);
                }
                case END -> {
                    return;
                }
            }
        }
    }

    private void comments(int from, int to) {
        int i = from;
        while (i < to) {
            if (src.charAt(i) == '#') {
                int end = i;
                while (end < to && src.charAt(end) != '\n' && src.charAt(end) != '\r') end++;
                spans.add(new Span(i, end, Kind.COMMENT));
                i = end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2) + 2;
                spans.add(new Span(i, end, Kind.COMMENT));
                i = end;
            } else {
                i++;
            }
        }
    }

    private void add(Token t, Kind kind) {
        tokens.add(t);
        kinds.add(kind);
    }

    private TokenType type(int i) {
        return i >= 0 && i < tokens.size() ? tokens.get(i).type() : EOF;
    }

    private void classify() {
        for (int i = 0; i < tokens.size(); i++) {
            switch (type(i)) {
                case ASSIGN -> attrPath(i);
                case INHERIT -> inherit(i);
                case COLON, AT -> {
                    if (type(i - 1) == ID) kinds.set(i - 1, Kind.PARAM);
                    if (type(i - 1) == RBRACE) formals(i - 1);
                    if (type(i) == AT && type(i + 1) == ID) kinds.set(i + 1, Kind.PARAM);
                }
                default -> {}
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (kinds.get(i) == null) kinds.set(i, kind(t, type(i - 1) == DOT));
            if (kinds.get(i) != null && t.type() != STR_OPEN && t.type() != IND_STR_OPEN)
                spans.add(new Span(t.start(), t.end(), kinds.get(i)));
        }
    }

    private static Kind kind(Token t, boolean selected) {
        return switch (t.type()) {
            case ID -> {
                if (selected) yield null;
                if (CONSTANTS.contains(t.text())) yield Kind.CONSTANT;
                yield BUILTINS.contains(t.text()) ? Kind.BUILTIN : null;
            }
            case INT, FLOAT -> Kind.NUMBER;
            case PATH, HPATH, SPATH, URI -> Kind.PATH;
            default -> KEYWORDS.contains(t.type()) ? Kind.KEYWORD : Kind.OPERATOR;
        };
    }

    /** Marks the names of {@code a.b.c =}, ending before {@code assign}. */
    private void attrPath(int assign) {
        for (int i = assign - 1; type(i) == ID || type(i) == STR_OPEN; i -= 2) {
            if (type(i) == ID) kinds.set(i, Kind.ATTR);
            if (type(i - 1) != DOT) return;
        }
    }

    private void inherit(int inherit) {
        int i = inherit + 1;
        if (type(i) == LPAREN) {
            for (int depth = 0; i < tokens.size(); i++) {
                if (type(i) == LPAREN) depth++;
                if (type(i) == RPAREN && --depth == 0) break;
            }
            i++;
        }
        for (; type(i) == ID || type(i) == STR_OPEN; i++) if (type(i) == ID) kinds.set(i, Kind.ATTR);
    }

    /** Marks the names of the formals whose closing brace is {@code close}. */
    private void formals(int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            TokenType t = type(i);
            if (t == RBRACE) depth++;
            if (t == LBRACE || t == DOLLAR_CURLY) depth--;
            if (depth == 0) return;
            if (depth == 1 && t == ID && (type(i - 1) == LBRACE || type(i - 1) == COMMA)) kinds.set(i, Kind.PARAM);
        }
    }

    private List<Span> merged() {
        spans.sort(Comparator.comparingInt(Span::start));
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            if (s.start() == s.end()) continue;
            Span last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.end() == s.start() && last.kind() == s.kind())
                out.set(out.size() - 1, new Span(last.start(), s.end(), s.kind()));
            else out.add(s);
        }
        return out;
    }
}
