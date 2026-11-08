package org.affogato.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.affogato.parser.Ast.*;
import org.affogato.parser.Syntax.Binding;
import org.affogato.parser.Syntax.Lit;
import org.affogato.parser.Syntax.Node;
import org.affogato.parser.Syntax.Part;
import org.affogato.parser.Syntax.Sel;

/** Turns the concrete syntax tree into the {@link Ast}, merging attribute paths and checking for duplicates. */
final class Lower {
    private Lower() {}

    static Expr lower(Node n) {
        return switch (n) {
            case Syntax.Ident i ->
                i.tok().text().equals("__curPos")
                        ? new CurPos(i.pos())
                        : new Var(i.pos(), i.tok().text());
            case Syntax.IntLit i -> {
                try {
                    yield new IntLit(i.pos(), Long.parseLong(i.tok().text()));
                } catch (NumberFormatException e) {
                    throw new ParseException(
                            "invalid integer '" + i.tok().text() + "'", i.pos().start());
                }
            }
            case Syntax.FloatLit f ->
                new FloatLit(f.pos(), Double.parseDouble(f.tok().text()));
            case Syntax.Uri u -> new StrLit(u.pos(), u.tok().text());
            case Syntax.SearchPath s -> {
                String t = s.tok().text();
                yield new SearchPath(s.pos(), t.substring(1, t.length() - 1));
            }
            case Syntax.Str s -> s.open().type() == TokenType.STR_OPEN ? string(s) : indString(s);
            case Syntax.Path p -> path(p);
            case Syntax.Paren p -> lower(p.expr());
            case Syntax.AttrSet s -> {
                if (s.rec() != null && s.rec().type() == TokenType.LET) {
                    Attrs binds = new Attrs(new Pos(s.pos().start(), s.pos().start()), true);
                    binds(binds, s.binds());
                    yield new Select(s.pos(), binds, List.of(new AttrName("body", null)), null);
                }
                Attrs attrs = new Attrs(s.pos(), s.rec() != null);
                binds(attrs, s.binds());
                yield attrs;
            }
            case Syntax.ListExpr l -> new ListExpr(l.pos(), lowerAll(l.items()));
            case Syntax.Select s ->
                new Select(
                        s.pos(), lower(s.target()), path(s.path()), s.fallback() == null ? null : lower(s.fallback()));
            case Syntax.HasAttr h -> new HasAttr(h.pos(), lower(h.target()), path(h.path()));
            case Syntax.Call c -> new Call(c.pos(), lower(c.fn()), lowerAll(c.args()));
            case Syntax.Lambda l -> lambda(l);
            case Syntax.Let l -> {
                Attrs binds = new Attrs(new Pos(l.pos().start(), l.pos().start()), true);
                binds(binds, l.binds());
                for (DynAttr d : binds.dynamic) {
                    throw new ParseException(
                            "dynamic attributes not allowed in let", d.pos().start());
                }
                yield new Let(l.pos(), binds, lower(l.body()));
            }
            case Syntax.With w -> new With(w.pos(), lower(w.scope()), lower(w.body()));
            case Syntax.Assert a -> new Assert(a.pos(), lower(a.cond()), lower(a.body()));
            case Syntax.If i -> new If(i.pos(), lower(i.cond()), lower(i.then()), lower(i.otherwise()));
            case Syntax.BinOp b -> {
                TokenType t = b.op().type();
                if (t == TokenType.PIPE_INTO || t == TokenType.PIPE_FROM) {
                    throw new ParseException(
                            "experimental Nix feature 'pipe-operators' is disabled; add '--extra-experimental-features"
                                    + " pipe-operators' to enable it",
                            b.op().start());
                }
                yield new BinOp(b.pos(), op(t), lower(b.left()), lower(b.right()));
            }
            case Syntax.Negate m -> new BinOp(m.pos(), Op.SUB, new IntLit(m.pos(), 0), lower(m.operand()));
            case Syntax.Not x -> new Not(x.pos(), lower(x.operand()));
        };
    }

    private static List<Expr> lowerAll(List<Node> nodes) {
        List<Expr> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) out.add(lower(n));
        return out;
    }

    private static Op op(TokenType t) {
        return switch (t) {
            case IMPL -> Op.IMPL;
            case OR -> Op.OR;
            case AND -> Op.AND;
            case EQ -> Op.EQ;
            case NEQ -> Op.NEQ;
            case LT -> Op.LT;
            case GT -> Op.GT;
            case LEQ -> Op.LE;
            case GEQ -> Op.GE;
            case UPDATE -> Op.UPDATE;
            case PLUS -> Op.ADD;
            case MINUS -> Op.SUB;
            case STAR -> Op.MUL;
            case SLASH -> Op.DIV;
            case CONCAT -> Op.CONCAT;
            default -> throw new IllegalStateException(t.toString());
        };
    }

    private static Expr lambda(Syntax.Lambda l) {
        String arg = l.arg() == null ? null : l.arg().text();
        if (l.formals() == null) return new Lambda(l.pos(), arg, null, lower(l.body()));
        List<Formal> formals = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Syntax.Formal f : l.formals().list()) {
            String name = f.name().text();
            Expr fallback = f.fallback() == null ? null : lower(f.fallback());
            if (!names.add(name)) {
                throw new ParseException(
                        "duplicate formal function argument '" + name + "'",
                        f.name().start());
            }
            formals.add(new Formal(f.pos(), name, fallback));
        }
        if (arg != null && names.contains(arg)) {
            throw new ParseException(
                    "duplicate formal function argument '" + arg + "'", l.pos().start());
        }
        Formals fs = new Formals(formals, l.formals().ellipsis() != null);
        return new Lambda(l.pos(), arg, fs, lower(l.body()));
    }

    private static Expr string(Syntax.Str s) {
        List<Part> parts = s.parts();
        if (parts.isEmpty()) return new StrLit(s.pos(), "");
        if (parts.size() == 1 && parts.getFirst() instanceof Lit l) return new StrLit(s.pos(), l.text());
        List<Expr> out = new ArrayList<>(parts.size());
        for (Part p : parts) {
            out.add(
                    switch (p) {
                        case Lit l -> new StrLit(new Pos(l.start(), l.end()), l.text());
                        case Syntax.Interp i -> lower(i.expr());
                    });
        }
        return new Interp(s.pos(), false, out);
    }

    private static Expr path(Syntax.Path p) {
        Token head = p.head();
        if (!head.interp()) return new PathLit(p.pos(), head.text());
        List<Expr> parts = new ArrayList<>();
        parts.add(new PathLit(new Pos(head.start(), head.end()), head.text()));
        for (Part part : p.rest()) {
            parts.add(
                    switch (part) {
                        case Lit l -> new StrLit(new Pos(l.start(), l.end()), l.text());
                        case Syntax.Interp i -> lower(i.expr());
                    });
        }
        return new Interp(p.pos(), true, parts);
    }

    /** Port of {@code ParserState::stripIndentation}. */
    private static Expr indString(Syntax.Str str) {
        Pos pos = str.pos();
        List<Part> parts = str.parts();
        if (parts.isEmpty()) return new StrLit(pos, "");
        boolean atStartOfLine = true;
        int minIndent = 1000000;
        int curIndent = 0;
        for (Part o : parts) {
            if (!(o instanceof Lit p) || !p.hasIndentation()) {
                if (atStartOfLine) {
                    atStartOfLine = false;
                    if (curIndent < minIndent) minIndent = curIndent;
                }
                continue;
            }
            String s = p.text();
            int j = 0;
            while (j < s.length()) {
                if (atStartOfLine) {
                    char c = s.charAt(j++);
                    if (c == ' ') {
                        curIndent++;
                    } else if (c == '\n') {
                        curIndent = 0;
                    } else {
                        atStartOfLine = false;
                        if (curIndent < minIndent) minIndent = curIndent;
                    }
                } else {
                    int nl = s.indexOf('\n', j);
                    if (nl < 0) break;
                    atStartOfLine = true;
                    curIndent = 0;
                    j = nl + 1;
                }
            }
        }

        List<Expr> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean pending = false;
        /** The pending text when it is one interpolated constant, which keeps its own position. */
        StrLit alone = null;
        atStartOfLine = true;
        int curDropped = 0;
        int n = parts.size();
        for (Part o : parts) {
            n--;
            if (!(o instanceof Lit p)) {
                atStartOfLine = false;
                curDropped = 0;
                Expr e = lower(((Syntax.Interp) o).expr());
                if (e instanceof StrLit lit) {
                    alone = pending ? null : lit;
                    sb.append(lit.value());
                    pending = true;
                    continue;
                }
                if (pending) {
                    out.add(alone != null ? alone : new StrLit(pos, sb.toString()));
                    sb.setLength(0);
                    pending = false;
                }
                out.add(e);
                continue;
            }
            pending = true;
            alone = null;
            String s = p.text();
            int from = sb.length();
            int j = 0;
            while (j < s.length()) {
                if (atStartOfLine) {
                    char c = s.charAt(j++);
                    if (c == ' ') {
                        if (curDropped++ >= minIndent) sb.append(c);
                    } else {
                        if (c != '\n') atStartOfLine = false;
                        curDropped = 0;
                        sb.append(c);
                    }
                } else {
                    int nl = s.indexOf('\n', j);
                    int end = nl < 0 ? s.length() : nl + 1;
                    sb.append(s, j, end);
                    j = end;
                    if (nl >= 0) atStartOfLine = true;
                }
            }
            if (n == 0) {
                int nl = sb.lastIndexOf("\n");
                if (nl >= from && onlySpaces(sb, nl + 1)) sb.setLength(nl + 1);
            }
        }
        if (pending) out.add(alone != null ? alone : new StrLit(pos, sb.toString()));
        if (out.size() == 1 && out.getFirst() instanceof StrLit lit)
            return lit.pos() == pos ? lit : new StrLit(pos, lit.value());
        return new Interp(pos, false, out);
    }

    private static boolean onlySpaces(CharSequence s, int from) {
        for (int i = from; i < s.length(); i++) if (s.charAt(i) != ' ') return false;
        return true;
    }

    private static AttrName name(Syntax.AttrNode a) {
        return switch (a) {
            case Syntax.Ident i -> new AttrName(i.tok().text(), null);
            case Syntax.Str s -> constant(lower(s));
            case Syntax.Dyn d -> constant(lower(d.expr()));
        };
    }

    private static AttrName constant(Expr e) {
        return e instanceof StrLit l ? new AttrName(l.value(), null) : new AttrName(null, e);
    }

    private static List<AttrName> path(List<Sel> path) {
        List<AttrName> out = new ArrayList<>(path.size());
        for (Sel s : path) out.add(name(s.attr()));
        return out;
    }

    private static void binds(Attrs attrs, List<Binding> binds) {
        for (Binding b : binds) {
            switch (b) {
                case Syntax.Inherit inh -> {
                    InheritSource source = inh.from() == null
                            ? null
                            : new InheritSource(lower(inh.from().expr()));
                    for (Syntax.AttrNode a : inh.names()) {
                        String name = name(a).name();
                        Pos pos = a.pos();
                        if (attrs.attrs.containsKey(name)) throw dupAttr(name, pos.start());
                        attrs.attrs.put(
                                name,
                                source == null
                                        ? new AttrDef(new Var(pos, name), pos, AttrKind.INHERITED, null)
                                        : new AttrDef(null, pos, AttrKind.INHERITED_FROM, source));
                    }
                }
                case Syntax.Assign as -> addAttr(attrs, path(as.path()), lower(as.value()), as.pos());
            }
        }
    }

    private static ParseException dupAttr(String path, int offset) {
        return new ParseException("attribute '" + path + "' already defined", offset);
    }

    private static String showPath(List<AttrName> path) {
        return String.join(".", path.stream().map(AttrName::toString).toList());
    }

    /** Port of {@code ParserState::addAttr}, which merges nested attribute paths. */
    private static void addAttr(Attrs attrs, List<AttrName> path, Expr value, Pos pos) {
        for (int i = 0; i < path.size() - 1; i++) {
            AttrName name = path.get(i);
            Attrs nested;
            if (name.name() != null) {
                AttrDef existing = attrs.attrs.get(name.name());
                if (existing != null) {
                    if (existing.kind() != AttrKind.INHERITED && existing.value() instanceof Attrs a) {
                        nested = a;
                    } else {
                        throw dupAttr(showPath(path.subList(0, i + 1)), pos.start());
                    }
                } else {
                    nested = new Attrs(pos, false);
                    attrs.attrs.put(name.name(), new AttrDef(nested, pos, AttrKind.PLAIN, null));
                }
            } else {
                nested = new Attrs(pos, false);
                attrs.dynamic.add(new DynAttr(name.dynamic(), nested, pos));
            }
            attrs = nested;
        }
        AttrName last = path.getLast();
        if (last.name() == null) {
            attrs.dynamic.add(new DynAttr(last.dynamic(), value, pos));
            return;
        }
        AttrDef existing = attrs.attrs.get(last.name());
        if (existing == null) {
            attrs.attrs.put(last.name(), new AttrDef(value, pos, AttrKind.PLAIN, null));
            return;
        }
        if (existing.kind() != AttrKind.INHERITED
                && existing.value() instanceof Attrs target
                && value instanceof Attrs ae) {
            for (var e : ae.attrs.entrySet()) {
                if (target.attrs.containsKey(e.getKey()))
                    throw dupAttr(e.getKey(), e.getValue().pos().start());
                target.attrs.put(e.getKey(), e.getValue());
            }
            target.dynamic.addAll(ae.dynamic);
        } else {
            throw dupAttr(showPath(path), pos.start());
        }
    }
}
