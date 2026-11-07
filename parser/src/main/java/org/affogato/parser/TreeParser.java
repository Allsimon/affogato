package org.affogato.parser;

import static org.affogato.parser.Lexeme.Kind.*;

import java.util.EnumSet;
import java.util.Set;
import org.affogato.parser.Lexeme.Kind;
import org.affogato.parser.TreeBuilder.Marker;

/**
 * {@link Parser}'s grammar over a {@link TreeBuilder}, for editors: it reports an error and goes on where
 * {@link Parser} throws, so every source yields a tree.
 */
public final class TreeParser {
    /** Tokens that close an enclosing construct: a missing expression before one is reported, not consumed. */
    private static final Set<Kind> FOLLOW =
            EnumSet.of(SEMI, RBRACE, RBRACK, RPAREN, IN, THEN, ELSE, ASSIGN, COMMA, COLON, STR_CLOSE, IND_STR_CLOSE);

    private static final int PREC_NOT = 9;
    private static final int PREC_NEGATE = 14;

    private final TreeBuilder b;
    private int consumed;

    private TreeParser(TreeBuilder b) {
        this.b = b;
    }

    /** Parses a whole file into the builder's current node, which the caller starts and ends. */
    public static void parse(TreeBuilder b) {
        TreeParser p = new TreeParser(b);
        if (b.token() != null) p.expr();
        while (b.token() != null) p.unexpected();
    }

    private boolean at(Kind kind) {
        return b.token() == kind;
    }

    private void advance() {
        b.advance();
        consumed++;
    }

    private boolean expect(Kind kind) {
        if (at(kind)) {
            advance();
            return true;
        }
        b.error("expected " + kind.display());
        return false;
    }

    /** Wraps the current token in an error node. */
    private void unexpected() {
        Marker m = b.mark();
        Kind token = b.token();
        advance();
        m.error("unexpected " + token.display());
    }

    /** Reports what was expected, consuming the current token unless it closes an enclosing construct. */
    private void missing(String what) {
        Kind token = b.token();
        if (token == null || FOLLOW.contains(token)) b.error("expected " + what);
        else {
            Marker m = b.mark();
            advance();
            m.error("expected " + what + ", got " + token.display());
        }
    }

    private void expr() {
        switch (b.token()) {
            case ID -> {
                Kind next = b.lookAhead(1);
                if (next == COLON || next == AT) {
                    Marker m = b.mark();
                    param();
                    if (next == AT) {
                        advance();
                        formals();
                    }
                    lambdaBody(m);
                    return;
                }
            }
            case LBRACE -> {
                if (isFormals()) {
                    Marker m = b.mark();
                    formals();
                    if (at(AT)) {
                        advance();
                        if (at(ID)) param();
                        else b.error("expected identifier");
                    }
                    lambdaBody(m);
                    return;
                }
            }
            case ASSERT, WITH -> {
                Marker m = b.mark();
                Kind kw = b.token();
                advance();
                operand();
                expect(SEMI);
                operand();
                m.done(kw == ASSERT ? NodeKind.ASSERT : NodeKind.WITH);
                return;
            }
            case LET -> {
                if (b.lookAhead(1) != LBRACE) {
                    Marker m = b.mark();
                    advance();
                    binds(IN);
                    expect(IN);
                    operand();
                    m.done(NodeKind.LET);
                    return;
                }
            }
            case IF -> {
                Marker m = b.mark();
                advance();
                operand();
                expect(THEN);
                operand();
                expect(ELSE);
                operand();
                m.done(NodeKind.IF);
                return;
            }
            case null, default -> {}
        }
        binary(1);
    }

    /** An expression the grammar requires, reported when missing. */
    private void operand() {
        if (startsExpr()) expr();
        else missing("expression");
    }

    private boolean startsExpr() {
        Kind t = b.token();
        return t != null
                && (startsSimple() || t == ASSERT || t == WITH || t == LET || t == IF || t == NOT || t == MINUS);
    }

    private void lambdaBody(Marker m) {
        expect(COLON);
        operand();
        m.done(NodeKind.LAMBDA);
    }

    private void param() {
        Marker m = b.mark();
        advance();
        m.done(NodeKind.PARAM);
    }

    private boolean isFormals() {
        Kind t1 = b.lookAhead(1);
        if (t1 == ELLIPSIS) return true;
        if (t1 == RBRACE) {
            Kind t2 = b.lookAhead(2);
            return t2 == COLON || t2 == AT;
        }
        if (t1 == ID) {
            Kind t2 = b.lookAhead(2);
            if (t2 == COMMA || t2 == QUESTION) return true;
            if (t2 == RBRACE) {
                Kind t3 = b.lookAhead(3);
                return t3 == COLON || t3 == AT;
            }
        }
        return false;
    }

    private void formals() {
        Marker m = b.mark();
        expect(LBRACE);
        while (b.token() != null && !at(RBRACE) && !at(COLON)) {
            int before = consumed;
            if (at(ELLIPSIS)) advance();
            else if (at(ID)) {
                Marker f = b.mark();
                advance();
                if (at(QUESTION)) {
                    advance();
                    operand();
                }
                f.done(NodeKind.FORMAL);
            } else missing("identifier");
            if (at(COMMA)) advance();
            else if (consumed == before) break;
            else if (!at(RBRACE)) b.error("expected ','");
        }
        expect(RBRACE);
        m.done(NodeKind.FORMALS);
    }

    private static int infixPrec(Kind t) {
        if (t == null) return -1;
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

    private void binary(int minPrec) {
        Marker m = b.mark();
        unary();
        while (true) {
            Kind t = b.token();
            int prec = infixPrec(t);
            if (prec < minPrec || prec < 0) {
                m.drop();
                return;
            }
            advance();
            if (t == QUESTION) {
                attrPath();
                m.done(NodeKind.HAS_ATTR);
            } else {
                boolean rightAssoc = t == PIPE_FROM || t == IMPL || t == UPDATE || t == CONCAT;
                if (startsExpr()) binary(rightAssoc ? prec : prec + 1);
                else missing("expression");
                m.done(NodeKind.BINARY);
                if ((prec == 6 || prec == 7) && infixPrec(b.token()) == prec)
                    b.error(b.token().display() + " is not associative");
            }
            m = m.precede();
        }
    }

    private void unary() {
        if (at(NOT) || at(MINUS)) {
            Marker m = b.mark();
            boolean not = at(NOT);
            advance();
            if (startsExpr()) binary((not ? PREC_NOT : PREC_NEGATE) + 1);
            else missing("expression");
            m.done(not ? NodeKind.NOT : NodeKind.NEGATE);
        } else app();
    }

    private boolean startsSimple() {
        Kind t = b.token();
        if (t == null) return false;
        return switch (t) {
            case ID, INT, FLOAT, PATH, HPATH, SPATH, URI, STR_OPEN, IND_STR_OPEN, LPAREN, LBRACK, LBRACE, REC -> true;
            case LET -> b.lookAhead(1) == LBRACE;
            default -> false;
        };
    }

    private void app() {
        Marker m = b.mark();
        select();
        if (!startsSimple()) {
            m.drop();
            return;
        }
        while (startsSimple()) select();
        m.done(NodeKind.APPLY);
    }

    private void select() {
        Marker m = b.mark();
        simple();
        if (at(DOT)) {
            advance();
            attrPath();
            if (at(OR_KW)) {
                advance();
                if (startsSimple()) select();
                else missing("expression");
            }
            m.done(NodeKind.SELECT);
        } else if (at(OR_KW)) {
            Marker or = b.mark();
            advance();
            or.done(NodeKind.VAR);
            m.done(NodeKind.APPLY);
        } else m.drop();
    }

    private void simple() {
        Kind t = b.token();
        if (t == null) {
            missing("expression");
            return;
        }
        switch (t) {
            case ID -> leaf(NodeKind.VAR);
            case INT -> leaf(NodeKind.INT);
            case FLOAT -> leaf(NodeKind.FLOAT);
            case URI -> leaf(NodeKind.URI);
            case SPATH -> leaf(NodeKind.SEARCH_PATH);
            case STR_OPEN -> string(NodeKind.STRING, STR_CLOSE);
            case IND_STR_OPEN -> string(NodeKind.IND_STRING, IND_STR_CLOSE);
            case PATH, HPATH -> {
                Marker m = b.mark();
                advance();
                while (at(PATH_TEXT) || at(DOLLAR_CURLY)) {
                    if (at(PATH_TEXT)) advance();
                    else interpolation(NodeKind.INTERPOLATION);
                }
                m.done(NodeKind.PATH);
            }
            case LPAREN -> {
                Marker m = b.mark();
                advance();
                operand();
                expect(RPAREN);
                m.done(NodeKind.PAREN);
            }
            case LET, REC, LBRACE -> {
                Marker m = b.mark();
                if (t != LBRACE) advance();
                expect(LBRACE);
                binds(RBRACE);
                expect(RBRACE);
                m.done(NodeKind.ATTR_SET);
            }
            case LBRACK -> {
                Marker m = b.mark();
                advance();
                while (b.token() != null && !at(RBRACK)) {
                    if (startsSimple()) select();
                    else if (FOLLOW.contains(b.token())) break;
                    else unexpected();
                }
                expect(RBRACK);
                m.done(NodeKind.LIST);
            }
            default -> missing("expression");
        }
    }

    private void leaf(NodeKind kind) {
        Marker m = b.mark();
        advance();
        m.done(kind);
    }

    private void string(NodeKind kind, Kind close) {
        Marker m = b.mark();
        advance();
        while (true) {
            if (at(STR_TEXT) || at(STR_ESCAPE)) advance();
            else if (at(DOLLAR_CURLY)) interpolation(NodeKind.INTERPOLATION);
            else break;
        }
        expect(close);
        m.done(kind);
    }

    /** {@code ${ expr }}, the tokens left before its closing brace reported as errors. */
    private void interpolation(NodeKind kind) {
        Marker m = b.mark();
        advance();
        operand();
        int depth = 0;
        while (b.token() != null && (depth > 0 || !at(RBRACE))) {
            if (at(LBRACE) || at(DOLLAR_CURLY)) depth++;
            else if (at(RBRACE)) depth--;
            unexpected();
        }
        expect(RBRACE);
        m.done(kind);
    }

    private boolean startsAttr() {
        return at(ID) || at(OR_KW) || at(STR_OPEN) || at(DOLLAR_CURLY);
    }

    private void attr() {
        if (at(ID) || at(OR_KW)) leaf(NodeKind.ATTR_NAME);
        else if (at(STR_OPEN)) string(NodeKind.STRING, STR_CLOSE);
        else if (at(DOLLAR_CURLY)) interpolation(NodeKind.DYNAMIC_ATTR);
        else missing("attribute name");
    }

    private void attrPath() {
        Marker m = b.mark();
        attr();
        while (at(DOT)) {
            advance();
            attr();
        }
        m.done(NodeKind.ATTR_PATH);
    }

    /** Bindings up to {@code end}, or a bracket closing an enclosing construct. */
    private void binds(Kind end) {
        while (b.token() != null && !at(end) && !at(RBRACE) && !at(RBRACK) && !at(RPAREN)) {
            if (at(INHERIT)) inherit();
            else if (startsAttr()) binding();
            else unexpected();
        }
    }

    private void binding() {
        Marker m = b.mark();
        attrPath();
        if (expect(ASSIGN)) operand();
        expect(SEMI);
        m.done(NodeKind.BINDING);
    }

    private void inherit() {
        Marker m = b.mark();
        advance();
        if (at(LPAREN)) {
            Marker from = b.mark();
            advance();
            operand();
            expect(RPAREN);
            from.done(NodeKind.PAREN);
        }
        while (startsAttr()) {
            if (at(DOLLAR_CURLY)) {
                Marker dynamic = b.mark();
                interpolation(NodeKind.DYNAMIC_ATTR);
                dynamic.error("dynamic attributes not allowed in inherit");
            } else attr();
        }
        expect(SEMI);
        m.done(NodeKind.INHERIT);
    }
}
