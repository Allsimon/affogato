package org.affogato.parser;

import java.util.List;
import java.util.stream.Stream;
import org.affogato.parser.Syntax.*;

/** Rewrites of a binding: {@code a.b = x;} into {@code a = { b = x; };}, and back. */
public final class Bindings {
    /** Replace {@code [start, end)} of the source with {@code text}. */
    public record Rewrite(int start, int end, String text) {}

    private Bindings() {}

    /** Moves the first attribute of the binding at {@code offset} out of its path, or null when it has one only. */
    public static Rewrite expand(String src, int offset) {
        Assign a = assignAt(src, offset);
        if (a == null || a.path().size() < 2) return null;
        String text = text(src, a.path().subList(0, 1)) + " = { "
                + text(src, a.path().subList(1, a.path().size())) + " = "
                + source(src, a.value()) + "; };";
        return new Rewrite(a.pos().start(), a.semi().end(), text);
    }

    /**
     * Merges the binding at {@code offset} with the only binding of the set it binds, or null when the set has another
     * one, is recursive, or holds a comment the rewrite would drop.
     */
    public static Rewrite collapse(String src, int offset) {
        Assign a = assignAt(src, offset);
        if (a == null
                || !(a.value() instanceof AttrSet set)
                || set.rec() != null
                || set.binds().size() != 1
                || !(set.binds().getFirst() instanceof Assign inner)
                || !src.substring(set.open().end(), inner.pos().start()).isBlank()
                || !src.substring(inner.semi().end(), set.close().start()).isBlank()) return null;
        String text = text(src, a.path()) + "." + text(src, inner.path()) + " = " + source(src, inner.value()) + ";";
        return new Rewrite(a.pos().start(), a.semi().end(), text);
    }

    private static String text(String src, List<Sel> path) {
        return src.substring(
                path.getFirst().attr().pos().start(), end(path.getLast().attr()));
    }

    /** A node's source; a string's position runs to the next token, past the whitespace after it. */
    private static String source(String src, Node node) {
        return src.substring(node.pos().start(), node.pos().end()).stripTrailing();
    }

    private static int end(AttrNode attr) {
        return switch (attr) {
            case Str s -> s.close().end();
            case Dyn d -> d.close().end();
            case Ident i -> i.pos().end();
        };
    }

    /** The innermost binding whose attribute path holds {@code offset}. */
    private static Assign assignAt(String src, int offset) {
        Node root;
        try {
            root = Parser.parseSyntax(src);
        } catch (ParseException e) {
            return null;
        }
        Assign found = null;
        for (Assign a : (Iterable<Assign>) assigns(root)::iterator) {
            int start = a.path().getFirst().attr().pos().start();
            int end = end(a.path().getLast().attr());
            if (start <= offset && offset <= end) found = a;
        }
        return found;
    }

    /** Every binding below {@code node}, outer ones first. */
    private static Stream<Assign> assigns(Node node) {
        return switch (node) {
            case AttrSet s -> binds(s.binds());
            case Let l -> Stream.concat(binds(l.binds()), assigns(l.body()));
            case Str s -> s.parts().stream().flatMap(Bindings::assigns);
            case Path p -> p.rest().stream().flatMap(Bindings::assigns);
            case Paren p -> assigns(p.expr());
            case ListExpr l -> l.items().stream().flatMap(Bindings::assigns);
            case Select s ->
                Stream.concat(assigns(s.target()), s.fallback() == null ? Stream.empty() : assigns(s.fallback()));
            case HasAttr h -> assigns(h.target());
            case Call c -> Stream.concat(assigns(c.fn()), c.args().stream().flatMap(Bindings::assigns));
            case Lambda l ->
                Stream.concat(
                        l.formals() == null
                                ? Stream.empty()
                                : l.formals().list().stream()
                                        .filter(f -> f.fallback() != null)
                                        .flatMap(f -> assigns(f.fallback())),
                        assigns(l.body()));
            case With w -> Stream.concat(assigns(w.scope()), assigns(w.body()));
            case Assert a -> Stream.concat(assigns(a.cond()), assigns(a.body()));
            case If i -> Stream.of(i.cond(), i.then(), i.otherwise()).flatMap(Bindings::assigns);
            case BinOp b -> Stream.concat(assigns(b.left()), assigns(b.right()));
            case Negate n -> assigns(n.operand());
            case Not n -> assigns(n.operand());
            case Ident i -> Stream.empty();
            case IntLit i -> Stream.empty();
            case FloatLit f -> Stream.empty();
            case Uri u -> Stream.empty();
            case SearchPath s -> Stream.empty();
        };
    }

    private static Stream<Assign> assigns(Part part) {
        return part instanceof Syntax.Interp i ? assigns(i.expr()) : Stream.empty();
    }

    private static Stream<Assign> binds(List<Binding> binds) {
        return binds.stream().flatMap(b -> switch (b) {
            case Assign a -> Stream.concat(Stream.of(a), assigns(a.value()));
            case Inherit i -> i.from() == null ? Stream.empty() : assigns(i.from());
        });
    }
}
