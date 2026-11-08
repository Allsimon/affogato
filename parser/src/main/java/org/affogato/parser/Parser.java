package org.affogato.parser;

import static org.affogato.parser.TokenType.*;

import java.util.ArrayList;
import java.util.List;
import org.affogato.parser.Ast.Pos;
import org.affogato.parser.Syntax.*;

/** Recursive-descent port of Nix's grammar (src/libexpr/parser.y). */
public final class Parser {
    private final Lexer lexer;
    private Token cur;
    private Token prev;

    private Parser(String src) {
        this.lexer = new Lexer(src);
        this.cur = lexer.next();
    }

    public static Ast.Expr parse(String src) {
        return Lower.lower(parseSyntax(src));
    }

    public static Node parseSyntax(String src) {
        Parser p = new Parser(src);
        Node e = p.parseExpr();
        if (p.cur.type() != EOF) throw p.unexpected();
        return e;
    }

    private Token advance() {
        prev = cur;
        cur = lexer.next();
        return prev;
    }

    private boolean at(TokenType t) {
        return cur.type() == t;
    }

    private Token accept(TokenType t) {
        return cur.type() == t ? advance() : null;
    }

    private Token expect(TokenType t) {
        if (cur.type() != t) throw unexpected();
        return advance();
    }

    private ParseException unexpected() {
        String what = cur.type() == EOF ? "end of file" : "'" + cur.text() + "'";
        return new ParseException("syntax error, unexpected " + what, cur.start());
    }

    /** {@link List#copyOf} without its array copy for the one and two element lists most nodes have. */
    private static <T> List<T> small(List<T> l) {
        return switch (l.size()) {
            case 1 -> List.of(l.getFirst());
            case 2 -> List.of(l.get(0), l.get(1));
            default -> List.copyOf(l);
        };
    }

    private Pos from(int start) {
        return new Pos(start, prev != null ? prev.end() : start);
    }

    /** Looks ahead {@code n} tokens past {@code cur} without consuming anything. */
    private TokenType peek(int n) {
        int saved = lexer.pos;
        try {
            Token t = cur;
            for (int i = 0; i < n; i++) t = lexer.next();
            return t.type();
        } catch (ParseException e) {
            return EOF;
        } finally {
            lexer.pos = saved;
        }
    }

    private Node parseExpr() {
        int start = cur.start();
        switch (cur.type()) {
            case ID -> {
                char next = lexer.peekChar();
                if (next == ':') {
                    Token arg = advance();
                    Token colon = advance();
                    Node body = parseExpr();
                    return new Lambda(from(start), arg, null, null, colon, body);
                }
                if (next == '@') {
                    Token arg = advance();
                    Token at = advance();
                    return parseFormalsLambda(start, arg, at);
                }
            }
            case LBRACE -> {
                if (isFormals()) return parseFormalsLambda(start, null, null);
            }
            case ASSERT -> {
                Token kw = advance();
                Node cond = parseExpr();
                Token semi = expect(SEMI);
                Node body = parseExpr();
                return new Assert(from(start), kw, cond, semi, body);
            }
            case WITH -> {
                Token kw = advance();
                Node scope = parseExpr();
                Token semi = expect(SEMI);
                Node body = parseExpr();
                return new With(from(start), kw, scope, semi, body);
            }
            case LET -> {
                if (lexer.peekChar() != '{') {
                    Token kw = advance();
                    List<Binding> binds = parseBinds(IN);
                    Token in = expect(IN);
                    Node body = parseExpr();
                    return new Let(from(start), kw, binds, in, body);
                }
            }
            default -> {}
        }
        return parseIf();
    }

    private boolean isFormals() {
        TokenType t1 = peek(1);
        if (t1 == ELLIPSIS) return true;
        if (t1 == RBRACE) {
            TokenType t2 = peek(2);
            return t2 == COLON || t2 == AT;
        }
        if (t1 == ID) {
            TokenType t2 = peek(2);
            if (t2 == COMMA || t2 == QUESTION) return true;
            if (t2 == RBRACE) {
                TokenType t3 = peek(3);
                return t3 == COLON || t3 == AT;
            }
        }
        return false;
    }

    /** Parses the formals from the opening brace, then the optional {@code @name}, the colon and the body. */
    private Node parseFormalsLambda(int start, Token arg, Token at) {
        Token open = expect(LBRACE);
        List<Formal> formals = new ArrayList<>();
        Token ellipsis = null;
        while (!at(RBRACE)) {
            if ((ellipsis = accept(ELLIPSIS)) != null) break;
            Token name = expect(ID);
            Token question = accept(QUESTION);
            Node fallback = question != null ? parseExpr() : null;
            Pos pos = new Pos(name.start(), prev.end());
            Token comma = accept(COMMA);
            formals.add(new Formal(pos, name, question, fallback, comma));
            if (comma == null) break;
        }
        Token close = expect(RBRACE);
        if (arg == null && (at = accept(AT)) != null) arg = expect(ID);
        Token colon = expect(COLON);
        Node body = parseExpr();
        return new Lambda(from(start), arg, at, new Formals(open, small(formals), ellipsis, close), colon, body);
    }

    private Node parseIf() {
        if (at(IF)) {
            int start = cur.start();
            Token if_ = advance();
            Node cond = parseExpr();
            Token then_ = expect(THEN);
            Node then = parseExpr();
            Token else_ = expect(ELSE);
            Node otherwise = parseExpr();
            return new If(from(start), if_, cond, then_, then, else_, otherwise);
        }
        return parseBinary(1);
    }

    private static final int PREC_NOT = 9;
    private static final int PREC_NEGATE = 14;

    private static int infixPrec(TokenType t) {
        return switch (t) {
            case PIPE_FROM -> 1;
            case PIPE_INTO -> 2;
            case IMPL -> 3;
            case OR -> 4;
            case AND -> 5;
            case EQ, NEQ -> 6;
            case LT, GT, LEQ, GEQ -> 7;
            case UPDATE -> 8;
            case PLUS, MINUS -> 10;
            case STAR, SLASH -> 11;
            case CONCAT -> 12;
            case QUESTION -> 13;
            default -> -1;
        };
    }

    private Node parseBinary(int minPrec) {
        int start = cur.start();
        Node left = parseUnary();
        while (true) {
            TokenType t = cur.type();
            int prec = infixPrec(t);
            if (prec < minPrec || prec < 0) return left;
            Token op = advance();
            if (t == QUESTION) {
                Pos pos = from(start);
                left = new HasAttr(pos, left, op, parseAttrPath());
                continue;
            }
            boolean rightAssoc = t == PIPE_FROM || t == IMPL || t == UPDATE || t == CONCAT;
            Node right = parseBinary(rightAssoc ? prec : prec + 1);
            left = new BinOp(from(start), left, op, right);
            if ((prec == 6 || prec == 7) && infixPrec(cur.type()) == prec) throw unexpected();
        }
    }

    private Node parseUnary() {
        int start = cur.start();
        Token op;
        if ((op = accept(NOT)) != null) {
            Node operand = parseBinary(PREC_NOT + 1);
            return new Not(from(start), op, operand);
        }
        if ((op = accept(MINUS)) != null) {
            Node operand = parseBinary(PREC_NEGATE + 1);
            return new Negate(from(start), op, operand);
        }
        return parseApp();
    }

    private boolean startsSimple() {
        return switch (cur.type()) {
            case ID, INT, FLOAT, PATH, HPATH, SPATH, URI, STR_OPEN, IND_STR_OPEN, LPAREN, LBRACK, LBRACE, REC -> true;
            case LET -> peek(1) == LBRACE;
            default -> false;
        };
    }

    private Node parseApp() {
        int start = cur.start();
        Node fn = parseSelect();
        if (!startsSimple()) return fn;
        Node arg = parseSelect();
        if (!startsSimple()) return new Call(from(start), fn, List.of(arg));
        List<Node> args = new ArrayList<>();
        args.add(arg);
        while (startsSimple()) args.add(parseSelect());
        return new Call(from(start), fn, small(args));
    }

    private Node parseSelect() {
        int start = cur.start();
        Node e = parseSimple();
        Token dot = accept(DOT);
        if (dot != null) {
            Sel first = new Sel(dot, parseAttr());
            List<Sel> path;
            if (at(DOT)) {
                path = new ArrayList<>();
                path.add(first);
                while ((dot = accept(DOT)) != null) path.add(new Sel(dot, parseAttr()));
            } else {
                path = List.of(first);
            }
            Token or = accept(OR_KW);
            Node fallback = or != null ? parseSelect() : null;
            return new Select(from(start), e, small(path), or, fallback);
        }
        if (at(OR_KW)) {
            Token or = advance();
            return new Call(from(start), e, List.of(new Ident(new Pos(or.start(), or.end()), or)));
        }
        return e;
    }

    private Node parseSimple() {
        int start = cur.start();
        Token t = cur;
        switch (t.type()) {
            case ID -> {
                advance();
                return new Ident(from(start), t);
            }
            case INT -> {
                advance();
                return new IntLit(from(start), t);
            }
            case FLOAT -> {
                advance();
                return new FloatLit(from(start), t);
            }
            case STR_OPEN -> {
                return parseString(start);
            }
            case IND_STR_OPEN -> {
                return parseIndString(start);
            }
            case PATH, HPATH -> {
                return parsePath(start);
            }
            case SPATH -> {
                advance();
                return new SearchPath(from(start), t);
            }
            case URI -> {
                advance();
                return new Uri(from(start), t);
            }
            case LPAREN -> {
                advance();
                Node e = parseExpr();
                Token close = expect(RPAREN);
                return new Paren(from(start), t, e, close);
            }
            case LET, REC, LBRACE -> {
                Token rec = t.type() == LBRACE ? null : advance();
                Token open = expect(LBRACE);
                List<Binding> binds = parseBinds(RBRACE);
                Token close = expect(RBRACE);
                return new AttrSet(from(start), rec, open, binds, close);
            }
            case LBRACK -> {
                advance();
                List<Node> items = new ArrayList<>();
                while (!at(RBRACK)) items.add(parseSelect());
                Token close = advance();
                return new ListExpr(from(start), t, small(items), close);
            }
            default -> throw unexpected();
        }
    }

    private Node parseString(int start) {
        if (!at(STR_OPEN)) throw unexpected();
        Token open = cur;
        prev = cur;
        List<Part> parts = new ArrayList<>();
        while (true) {
            int partStart = lexer.pos;
            Lexer.Part p = lexer.stringPart();
            if (p.kind() == Lexer.PartKind.END) break;
            parts.add(
                    p.kind() == Lexer.PartKind.LIT
                            ? new Lit(partStart, lexer.pos, p.text(), p.hasIndentation())
                            : parseInterpolation());
        }
        Token close = new Token(STR_OPEN, "\"", lexer.pos - 1, lexer.pos, false);
        cur = lexer.next();
        prev = new Token(STR_OPEN, "\"", start, cur.start(), false);
        return new Str(from(start), open, small(parts), close);
    }

    /** Called right after {@code ${}; leaves the lexer right after the closing brace. */
    private Interp parseInterpolation() {
        Token open = new Token(DOLLAR_CURLY, "${", lexer.pos - 2, lexer.pos, false);
        cur = lexer.next();
        Node e = parseExpr();
        if (!at(RBRACE)) throw unexpected();
        return new Interp(open, e, cur);
    }

    private Node parseIndString(int start) {
        Token open = cur;
        prev = cur;
        List<Part> parts = new ArrayList<>();
        while (true) {
            int partStart = lexer.pos;
            Lexer.Part p = lexer.indStringPart();
            if (p.kind() == Lexer.PartKind.END) break;
            parts.add(
                    p.kind() == Lexer.PartKind.LIT
                            ? new Lit(partStart, lexer.pos, p.text(), p.hasIndentation())
                            : parseInterpolation());
        }
        Token close = new Token(IND_STR_OPEN, "''", lexer.pos - 2, lexer.pos, false);
        cur = lexer.next();
        prev = new Token(IND_STR_OPEN, "''", start, cur.start(), false);
        return new Str(from(start), open, small(parts), close);
    }

    private Node parsePath(int start) {
        Token t = cur;
        prev = t;
        if (!t.interp()) {
            cur = lexer.next();
            return new Path(from(start), t, List.of());
        }
        List<Part> parts = new ArrayList<>();
        String last = t.text();
        while (true) {
            int partStart = lexer.pos;
            Lexer.Part p = lexer.pathPart();
            if (p.kind() == Lexer.PartKind.END) break;
            if (p.kind() == Lexer.PartKind.LIT) {
                parts.add(new Lit(partStart, lexer.pos, p.text(), false));
                last = p.text();
            } else {
                parts.add(parseInterpolation());
                last = "";
            }
        }
        if (last.endsWith("/")) throw new ParseException("path has a trailing slash", lexer.pos);
        cur = lexer.next();
        prev = new Token(PATH, "", start, lexer.pos, false);
        return new Path(new Pos(start, prev.end()), t, small(parts));
    }

    private AttrNode parseAttr() {
        int start = cur.start();
        switch (cur.type()) {
            case ID, OR_KW -> {
                Token t = advance();
                return new Ident(from(start), t);
            }
            case STR_OPEN -> {
                return (Str) parseString(start);
            }
            case DOLLAR_CURLY -> {
                Token open = advance();
                Node e = parseExpr();
                Token close = expect(RBRACE);
                return new Dyn(from(start), open, e, close);
            }
            default -> throw unexpected();
        }
    }

    private List<Sel> parseAttrPath() {
        Sel first = new Sel(null, parseAttr());
        if (!at(DOT)) return List.of(first);
        List<Sel> path = new ArrayList<>();
        path.add(first);
        Token dot;
        while ((dot = accept(DOT)) != null) path.add(new Sel(dot, parseAttr()));
        return small(path);
    }

    private List<Binding> parseBinds(TokenType end) {
        List<Binding> binds = new ArrayList<>();
        while (!at(end)) {
            int start = cur.start();
            Token inherit = accept(INHERIT);
            if (inherit != null) {
                Paren from = null;
                if (at(LPAREN)) {
                    Token open = advance();
                    Node e = parseExpr();
                    Token close = expect(RPAREN);
                    from = new Paren(from(open.start()), open, e, close);
                }
                List<AttrNode> names = new ArrayList<>();
                while (!at(SEMI)) {
                    AttrNode name = parseAttr();
                    if (!isStatic(name)) {
                        throw new ParseException(
                                "dynamic attributes not allowed in inherit",
                                name.pos().start());
                    }
                    names.add(name);
                }
                binds.add(new Inherit(inherit, from, small(names), advance()));
            } else {
                List<Sel> path = parseAttrPath();
                Token assign = expect(ASSIGN);
                Node value = parseExpr();
                Token semi = expect(SEMI);
                binds.add(new Assign(from(start), path, assign, value, semi));
            }
        }
        return small(binds);
    }

    /** Whether an attribute name lowers to a constant {@link Ast.StrLit}. */
    static boolean isStatic(AttrNode name) {
        return switch (name) {
            case Ident i -> true;
            case Str s -> isConstant(s);
            case Dyn d -> isConstant(d.expr());
        };
    }

    private static boolean isConstant(Node e) {
        return switch (e) {
            case Str s -> s.parts().stream().allMatch(p -> p instanceof Lit);
            case Uri u -> true;
            case Paren p -> isConstant(p.expr());
            default -> false;
        };
    }
}
