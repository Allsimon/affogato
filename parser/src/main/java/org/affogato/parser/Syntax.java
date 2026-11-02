package org.affogato.parser;

import java.util.List;
import org.affogato.parser.Ast.Pos;

/**
 * The concrete syntax tree: it keeps every token, in source order, so the source text between two tokens is the trivia
 * there. Each node carries the position its {@link Ast} counterpart gets.
 */
public final class Syntax {
    private Syntax() {}

    public sealed interface Node
            permits Ident,
                    IntLit,
                    FloatLit,
                    Uri,
                    SearchPath,
                    Str,
                    Path,
                    Paren,
                    AttrSet,
                    ListExpr,
                    Select,
                    HasAttr,
                    Call,
                    Lambda,
                    Let,
                    With,
                    Assert,
                    If,
                    BinOp,
                    Negate,
                    Not {
        Pos pos();
    }

    /** An attribute name in a path or an {@code inherit}. */
    public sealed interface AttrNode permits Ident, Str, Dyn {
        Pos pos();
    }

    /** {@code tok} is an ID, or OR_KW where {@code or} is used as a variable or attribute name. */
    public record Ident(Pos pos, Token tok) implements Node, AttrNode {}

    public record IntLit(Pos pos, Token tok) implements Node {}

    public record FloatLit(Pos pos, Token tok) implements Node {}

    public record Uri(Pos pos, Token tok) implements Node {}

    public record SearchPath(Pos pos, Token tok) implements Node {}

    public sealed interface Part permits Lit, Interp {}

    /** Source range of a literal piece, and its value as the lexer decoded it. */
    public record Lit(int start, int end, String text, boolean hasIndentation) implements Part {}

    public record Interp(Token open, Node expr, Token close) implements Part {}

    /** {@code open} is STR_OPEN or IND_STR_OPEN; {@code close} is the closing delimiter. */
    public record Str(Pos pos, Token open, List<Part> parts, Token close) implements Node, AttrNode {}

    /** {@code rest} holds the pieces after {@code head} when an interpolation follows it immediately. */
    public record Path(Pos pos, Token head, List<Part> rest) implements Node {}

    public record Paren(Pos pos, Token open, Node expr, Token close) implements Node {}

    /** {@code ${expr}} as an attribute name. */
    public record Dyn(Pos pos, Token open, Node expr, Token close) implements AttrNode {}

    /** {@code dot} is null for the first attribute of a binding or a {@code ?} path. */
    public record Sel(Token dot, AttrNode attr) {}

    public sealed interface Binding permits Assign, Inherit {}

    public record Assign(Pos pos, List<Sel> path, Token assign, Node value, Token semi) implements Binding {}

    public record Inherit(Token inherit, Paren from, List<AttrNode> names, Token semi) implements Binding {}

    /** {@code rec} is REC, LET for the legacy {@code let { }}, or null. */
    public record AttrSet(Pos pos, Token rec, Token open, List<Binding> binds, Token close) implements Node {}

    public record ListExpr(Pos pos, Token open, List<Node> items, Token close) implements Node {}

    public record Select(Pos pos, Node target, List<Sel> path, Token or, Node fallback) implements Node {}

    public record HasAttr(Pos pos, Node target, Token question, List<Sel> path) implements Node {}

    public record Call(Pos pos, Node fn, List<Node> args) implements Node {}

    public record Formal(Pos pos, Token name, Token question, Node fallback, Token comma) {}

    public record Formals(Token open, List<Formal> list, Token ellipsis, Token close) {}

    /** {@code x: body}, {@code { ... }: body}, {@code x @ { ... }: body} or {@code { ... } @ x: body}. */
    public record Lambda(Pos pos, Token arg, Token at, Formals formals, Token colon, Node body) implements Node {}

    public record Let(Pos pos, Token let, List<Binding> binds, Token in, Node body) implements Node {}

    public record With(Pos pos, Token with, Node scope, Token semi, Node body) implements Node {}

    public record Assert(Pos pos, Token assert_, Node cond, Token semi, Node body) implements Node {}

    public record If(Pos pos, Token if_, Node cond, Token then_, Node then, Token else_, Node otherwise)
            implements Node {}

    public record BinOp(Pos pos, Node left, Token op, Node right) implements Node {}

    public record Negate(Pos pos, Token minus, Node operand) implements Node {}

    public record Not(Pos pos, Token not, Node operand) implements Node {}
}
