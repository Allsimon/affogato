package org.affogato.lint;

import java.util.ArrayList;
import java.util.List;
import org.affogato.parser.Syntax.*;
import org.affogato.parser.TokenType;

/** Source spans, children and precedence of syntax nodes. */
final class Nodes {
    private Nodes() {}

    /** Precedence of a simple term: an atom, which a selection can target. */
    static final int ATOM = 101;

    static final int SELECT = 100;
    static final int APPLY = 50;

    /** Where {@link Node#pos()} cannot be used: it keeps the old parser's quirks, such as a string ending at the next token. */
    static int start(Node n) {
        return switch (n) {
            case Ident i -> i.tok().start();
            case IntLit i -> i.tok().start();
            case FloatLit f -> f.tok().start();
            case Uri u -> u.tok().start();
            case SearchPath s -> s.tok().start();
            case Str s -> s.open().start();
            case Path p -> p.head().start();
            case Paren p -> p.open().start();
            case AttrSet s -> (s.rec() != null ? s.rec() : s.open()).start();
            case ListExpr l -> l.open().start();
            case Select s -> start(s.target());
            case HasAttr h -> start(h.target());
            case Call c -> start(c.fn());
            case Lambda l -> {
                if (l.formals() == null) yield l.arg().start();
                int open = l.formals().open().start();
                yield l.arg() != null ? Math.min(l.arg().start(), open) : open;
            }
            case Let l -> l.let().start();
            case With w -> w.with().start();
            case Assert a -> a.assert_().start();
            case If i -> i.if_().start();
            case BinOp b -> start(b.left());
            case Negate m -> m.minus().start();
            case Not x -> x.not().start();
        };
    }

    static int end(Node n) {
        return switch (n) {
            case Ident i -> i.tok().end();
            case IntLit i -> i.tok().end();
            case FloatLit f -> f.tok().end();
            case Uri u -> u.tok().end();
            case SearchPath s -> s.tok().end();
            case Str s -> s.close().end();
            case Path p ->
                p.rest().isEmpty()
                        ? p.head().end()
                        : switch (p.rest().getLast()) {
                            case Lit l -> l.end();
                            case Interp i -> i.close().end();
                        };
            case Paren p -> p.close().end();
            case AttrSet s -> s.close().end();
            case ListExpr l -> l.close().end();
            case Select s ->
                s.fallback() != null
                        ? end(s.fallback())
                        : attrEnd(s.path().getLast().attr());
            case HasAttr h -> attrEnd(h.path().getLast().attr());
            case Call c -> end(c.args().getLast());
            case Lambda l -> end(l.body());
            case Let l -> end(l.body());
            case With w -> end(w.body());
            case Assert a -> end(a.body());
            case If i -> end(i.otherwise());
            case BinOp b -> end(b.right());
            case Negate m -> end(m.operand());
            case Not x -> end(x.operand());
        };
    }

    static int start(Ident i) {
        return i.tok().start();
    }

    static int end(Ident i) {
        return i.tok().end();
    }

    static int attrStart(AttrNode a) {
        return switch (a) {
            case Ident i -> i.tok().start();
            case Str s -> s.open().start();
            case Dyn d -> d.open().start();
        };
    }

    static int attrEnd(AttrNode a) {
        return switch (a) {
            case Ident i -> i.tok().end();
            case Str s -> s.close().end();
            case Dyn d -> d.close().end();
        };
    }

    static int start(Binding b) {
        return switch (b) {
            case Assign a -> attrStart(a.path().getFirst().attr());
            case Inherit i -> i.inherit().start();
        };
    }

    static int end(Binding b) {
        return switch (b) {
            case Assign a -> a.semi().end();
            case Inherit i -> i.semi().end();
        };
    }

    static String text(String src, Node n) {
        return src.substring(start(n), end(n));
    }

    /** The name an attribute stands for when it is known without evaluation, else null. */
    static String staticName(AttrNode a) {
        return switch (a) {
            case Ident i -> i.tok().text();
            case Str s -> constant(s);
            case Dyn d -> constant(d.expr());
        };
    }

    private static String constant(Node n) {
        return switch (n) {
            case Str s
            when s.open().type() == TokenType.STR_OPEN -> {
                StringBuilder sb = new StringBuilder();
                for (Part p : s.parts()) {
                    if (!(p instanceof Lit l)) yield null;
                    sb.append(l.text());
                }
                yield sb.toString();
            }
            case Uri u -> u.tok().text();
            case Paren p -> constant(p.expr());
            default -> null;
        };
    }

    /** The expressions directly below {@code n}, including those in attribute names and bindings. */
    static List<Node> children(Node n) {
        List<Node> out = new ArrayList<>();
        switch (n) {
            case Ident i -> {}
            case IntLit i -> {}
            case FloatLit f -> {}
            case Uri u -> {}
            case SearchPath s -> {}
            case Str s -> parts(s.parts(), out);
            case Path p -> parts(p.rest(), out);
            case Paren p -> out.add(p.expr());
            case AttrSet s -> binds(s.binds(), out);
            case ListExpr l -> out.addAll(l.items());
            case Select s -> {
                out.add(s.target());
                path(s.path(), out);
                if (s.fallback() != null) out.add(s.fallback());
            }
            case HasAttr h -> {
                out.add(h.target());
                path(h.path(), out);
            }
            case Call c -> {
                out.add(c.fn());
                out.addAll(c.args());
            }
            case Lambda l -> {
                if (l.formals() != null) {
                    for (Formal f : l.formals().list()) if (f.fallback() != null) out.add(f.fallback());
                }
                out.add(l.body());
            }
            case Let l -> {
                binds(l.binds(), out);
                out.add(l.body());
            }
            case With w -> {
                out.add(w.scope());
                out.add(w.body());
            }
            case Assert a -> {
                out.add(a.cond());
                out.add(a.body());
            }
            case If i -> {
                out.add(i.cond());
                out.add(i.then());
                out.add(i.otherwise());
            }
            case BinOp b -> {
                out.add(b.left());
                out.add(b.right());
            }
            case Negate m -> out.add(m.operand());
            case Not x -> out.add(x.operand());
        }
        return out;
    }

    static void parts(List<Part> parts, List<Node> out) {
        for (Part p : parts) if (p instanceof Interp i) out.add(i.expr());
    }

    static void attr(AttrNode a, List<Node> out) {
        switch (a) {
            case Ident i -> {}
            case Str s -> parts(s.parts(), out);
            case Dyn d -> out.add(d.expr());
        }
    }

    static void path(List<Sel> path, List<Node> out) {
        for (Sel s : path) attr(s.attr(), out);
    }

    private static void binds(List<Binding> binds, List<Node> out) {
        for (Binding b : binds) {
            switch (b) {
                case Assign a -> {
                    path(a.path(), out);
                    out.add(a.value());
                }
                case Inherit i -> {
                    if (i.from() != null) out.add(i.from().expr());
                    for (AttrNode a : i.names()) attr(a, out);
                }
            }
        }
    }

    static int prec(Node n) {
        return switch (n) {
            case Select s -> SELECT;
            case Call c -> APPLY;
            case HasAttr h -> infixPrec(TokenType.QUESTION);
            case BinOp b -> infixPrec(b.op().type());
            case Negate m -> 14;
            case Not x -> 9;
            case Lambda l -> 0;
            case Let l -> 0;
            case With w -> 0;
            case Assert a -> 0;
            case If i -> 0;
            default -> ATOM;
        };
    }

    /** Mirrors {@code Parser.infixPrec}. */
    static int infixPrec(TokenType t) {
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
            default -> throw new IllegalArgumentException(t.toString());
        };
    }

    private static boolean rightAssoc(TokenType t) {
        return t == TokenType.PIPE_FROM || t == TokenType.IMPL || t == TokenType.UPDATE || t == TokenType.CONCAT;
    }

    private static boolean nonAssoc(TokenType t) {
        int p = infixPrec(t);
        return p == 6 || p == 7;
    }

    /** The lowest precedence an expression can have in the place of {@code child} without parentheses. */
    static int required(Node parent, Node child) {
        return switch (parent) {
            case null -> 0;
            case ListExpr l -> SELECT;
            case Call c -> SELECT;
            case Select s -> child == s.target() ? ATOM : child == s.fallback() ? SELECT : 0;
            case HasAttr h -> child == h.target() ? APPLY : 0;
            case Negate m -> APPLY;
            case Not x -> 10;
            case BinOp b -> {
                TokenType t = b.op().type();
                int p = infixPrec(t);
                boolean left = child == b.left();
                if (nonAssoc(t)) yield p + 1;
                yield left == rightAssoc(t) ? p + 1 : p;
            }
            default -> 0;
        };
    }

    /** {@code text}, parenthesized unless an expression of precedence {@code prec} fits where {@code child} is. */
    static String fit(Node parent, Node child, String text, int prec) {
        return prec >= required(parent, child) ? text : "(" + text + ")";
    }
}
