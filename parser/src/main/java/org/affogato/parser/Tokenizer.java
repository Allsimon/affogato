package org.affogato.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.affogato.parser.Lexeme.Kind;

/**
 * Splits the source into lexemes that cover it whole, whitespace and comments included, for an editor's parser. It
 * never fails: what {@link Lexer} rejects becomes a {@link Kind#BAD_CHARACTER}, and an unterminated string or comment
 * runs to the end.
 */
public final class Tokenizer {
    private enum Mode {
        CODE,
        STRING,
        IND_STRING,
        PATH
    }

    /** {@code braces} counts the braces open in a CODE frame; {@code interpolation} marks one ended by {@code }}. */
    private static final class Frame {
        final Mode mode;
        final boolean interpolation;
        int braces;

        Frame(Mode mode, boolean interpolation) {
            this.mode = mode;
            this.interpolation = interpolation;
        }
    }

    private final String src;
    private final Lexer lexer;
    private final List<Lexeme> out = new ArrayList<>();
    private final Deque<Frame> frames = new ArrayDeque<>();

    private Tokenizer(String src) {
        this.src = src;
        this.lexer = new Lexer(src);
        lexer.lenient = true;
    }

    public static List<Lexeme> tokenize(String src) {
        Tokenizer t = new Tokenizer(src);
        t.run();
        return t.out;
    }

    private void run() {
        frames.push(new Frame(Mode.CODE, false));
        while (!frames.isEmpty()) {
            Frame frame = frames.peek();
            int before = lexer.pos;
            try {
                switch (frame.mode) {
                    case CODE -> code(frame, before);
                    case STRING -> string();
                    case IND_STRING -> indString();
                    case PATH -> path();
                }
            } catch (ParseException e) {
                if (frame.mode == Mode.CODE) {
                    trivia(before, e.offset);
                    boolean comment = src.startsWith("/*", e.offset);
                    lexer.pos = comment ? src.length() : e.offset + 1;
                    add(Kind.BAD_CHARACTER, e.offset, comment ? e.offset + 2 : lexer.pos);
                    if (comment && lexer.pos > e.offset + 2)
                        out.add(new Lexeme(Kind.COMMENT, e.offset + 2, lexer.pos, false));
                } else {
                    add(Kind.STR_TEXT, before, src.length());
                    return;
                }
            }
        }
    }

    private void code(Frame frame, int before) {
        Token t = lexer.next();
        trivia(before, t.start());
        if (t.type() == TokenType.EOF) {
            frames.clear();
            return;
        }
        if ((t.type() == TokenType.PATH || t.type() == TokenType.HPATH)
                && !t.interp()
                && t.text().endsWith("/")) {
            add(Kind.of(t.type()), t.start(), t.end() - 1);
            out.add(new Lexeme(Kind.BAD_CHARACTER, t.end() - 1, t.end(), false));
            return;
        }
        add(Kind.of(t.type()), t.start(), t.end());
        switch (t.type()) {
            case STR_OPEN -> frames.push(new Frame(Mode.STRING, false));
            case IND_STR_OPEN -> frames.push(new Frame(Mode.IND_STRING, false));
            case LBRACE, DOLLAR_CURLY -> frame.braces++;
            case RBRACE -> {
                if (frame.braces > 0) frame.braces--;
                else if (frame.interpolation) frames.pop();
            }
            case PATH, HPATH -> {
                if (t.interp()) frames.push(new Frame(Mode.PATH, false));
            }
            default -> {}
        }
    }

    private void string() {
        int start = lexer.pos;
        Lexer.Part part = lexer.stringPart();
        int end = lexer.pos;
        switch (part.kind()) {
            case LIT -> {
                int text = start;
                for (int i = start; i < end; i++) {
                    if (src.charAt(i) != '\\') continue;
                    add(Kind.STR_TEXT, text, i);
                    add(Kind.STR_ESCAPE, i, i + 2);
                    text = ++i + 1;
                }
                add(Kind.STR_TEXT, text, end);
            }
            case INTERP -> interpolation(end);
            case END -> {
                add(Kind.STR_CLOSE, end - 1, end);
                frames.pop();
            }
        }
    }

    private void indString() {
        int start = lexer.pos;
        Lexer.Part part = lexer.indStringPart();
        int end = lexer.pos;
        switch (part.kind()) {
            case LIT -> add(part.hasIndentation() || end - start < 3 ? Kind.STR_TEXT : Kind.STR_ESCAPE, start, end);
            case INTERP -> interpolation(end);
            case END -> {
                add(Kind.IND_STR_CLOSE, end - 2, end);
                frames.pop();
            }
        }
    }

    private void path() {
        int start = lexer.pos;
        Lexer.Part part = lexer.pathPart();
        int end = lexer.pos;
        switch (part.kind()) {
            case LIT -> add(Kind.PATH_TEXT, start, end);
            case INTERP -> interpolation(end);
            case END -> frames.pop();
        }
    }

    private void interpolation(int end) {
        add(Kind.DOLLAR_CURLY, end - 2, end);
        frames.push(new Frame(Mode.CODE, true));
    }

    /** Whitespace and complete comments, which is all {@link Lexer} skips between two tokens. */
    private void trivia(int from, int to) {
        int i = from;
        while (i < to) {
            int start = i;
            char c = src.charAt(i);
            if (c == '#') {
                while (i < to && src.charAt(i) != '\n' && src.charAt(i) != '\r') i++;
                add(Kind.COMMENT, start, i);
            } else if (c == '/' && src.startsWith("/*", i)) {
                i = src.indexOf("*/", i + 2) + 2;
                add(Kind.COMMENT, start, i);
            } else {
                while (i < to && " \t\r\n".indexOf(src.charAt(i)) >= 0) i++;
                if (i == start) i++;
                add(i == start + 1 && " \t\r\n".indexOf(c) < 0 ? Kind.BAD_CHARACTER : Kind.WHITESPACE, start, i);
            }
        }
    }

    private void add(Kind kind, int start, int end) {
        if (end > start) out.add(new Lexeme(kind, start, end, frames.size() == 1));
    }
}
