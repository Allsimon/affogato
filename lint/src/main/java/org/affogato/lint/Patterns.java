package org.affogato.lint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.affogato.lint.Scope.Def;
import org.affogato.parser.Syntax.*;
import org.affogato.parser.TokenType;

/** Code that has a simpler or more idiomatic form, as statix finds it. */
final class Patterns {
    private final String src;
    private final Scope scope;
    private final List<Diagnostic> out;

    private Patterns(String src, Scope scope, List<Diagnostic> out) {
        this.src = src;
        this.scope = scope;
        this.out = out;
    }

    static void check(String src, Scope scope, List<Diagnostic> out) {
        Patterns p = new Patterns(src, scope, out);
        for (Node n : scope.nodes) p.node(n);
    }

    private void node(Node n) {
        switch (n) {
            case BinOp b -> {
                boolComparison(b);
                emptyListConcat(b);
            }
            case Not x -> boolSimplification(x);
            case Let l -> {
                if (l.binds().isEmpty()) {
                    report(
                            Rule.EMPTY_LET_IN,
                            l,
                            "'let' with no bindings",
                            List.of(Text.delete(src, start(l), start(l.body()))));
                }
                collapsibleLetIn(l);
                binds(l.binds(), true);
            }
            case AttrSet s -> {
                if (s.rec() != null && s.rec().type() == TokenType.LET) {
                    report(
                            Rule.LEGACY_LET_SYNTAX,
                            s,
                            "legacy 'let { }' syntax; use 'let ... in'",
                            List.of(
                                    new Edit(s.rec().start(), s.rec().end(), "rec"),
                                    new Edit(s.close().end(), s.close().end(), ".body")));
                }
                binds(s.binds(), s.rec() != null);
            }
            case Lambda l -> {
                pattern(l);
                etaReduction(l);
            }
            case Paren p -> uselessParens(p);
            case Uri u ->
                report(
                        Rule.UNQUOTED_URI,
                        u,
                        "unquoted URI; URI literals are deprecated",
                        List.of(new Edit(start(u), end(u), "\"" + u.tok().text() + "\"")));
            case Call c -> isNull(c);
            case Select s -> {
                if (isBuiltin(s, "toPath")) {
                    report(Rule.DEPRECATED_TO_PATH, s, "'builtins.toPath' is deprecated", List.of());
                }
                fasterBuiltin(s);
            }
            case Ident i -> {
                if (Scope.name(i).equals("__toPath") && scope.isGlobal(i)) {
                    report(Rule.DEPRECATED_TO_PATH, i, "'__toPath' is deprecated", List.of());
                }
            }
            case If i -> uselessHasAttr(i);
            default -> {}
        }
    }

    private void boolComparison(BinOp b) {
        TokenType op = b.op().type();
        if (op != TokenType.EQ && op != TokenType.NEQ) return;
        Boolean value = bool(b.right()) != null ? bool(b.right()) : bool(b.left());
        if (value == null) return;
        // No fix: `v == true` is also a type test, where `v` alone fails on other types.
        report(
                Rule.BOOL_COMPARISON,
                b,
                "comparison with '" + value + "'; if the other side is always a boolean, use it"
                        + (value == (op == TokenType.EQ) ? " directly" : " negated"),
                List.of());
    }

    private Boolean bool(Node n) {
        if (n instanceof Ident i && scope.isGlobal(i)) {
            return switch (Scope.name(i)) {
                case "true" -> true;
                case "false" -> false;
                default -> null;
            };
        }
        return null;
    }

    private void emptyListConcat(BinOp b) {
        if (b.op().type() != TokenType.CONCAT) return;
        Node other;
        if (b.left() instanceof ListExpr l && l.items().isEmpty()) other = b.right();
        else if (b.right() instanceof ListExpr l && l.items().isEmpty()) other = b.left();
        else return;
        report(
                Rule.EMPTY_LIST_CONCAT,
                b,
                "concatenation with an empty list",
                List.of(new Edit(start(b), end(b), Nodes.text(src, other))));
    }

    private void boolSimplification(Not x) {
        if (!(x.operand() instanceof Paren p) || !(p.expr() instanceof BinOp b)) return;
        String op = switch (b.op().type()) {
            case EQ -> "!=";
            case NEQ -> "==";
            default -> null;
        };
        if (op == null) return;
        String text = Nodes.text(src, b.left()) + " " + op + " " + Nodes.text(src, b.right());
        report(
                Rule.BOOL_SIMPLIFICATION,
                x,
                "negated comparison; use '" + op + "'",
                List.of(new Edit(start(x), end(x), Nodes.fit(scope.parents.get(x), x, text, 6))));
    }

    private void collapsibleLetIn(Let outer) {
        if (!(outer.body() instanceof Let inner)) return;
        Set<String> innerNames = names(inner);
        boolean merge = names(outer).stream().noneMatch(innerNames::contains);
        if (merge && !outer.binds().isEmpty()) {
            // Merged, the inner bindings would capture these names in the outer ones.
            int start = Nodes.start(outer.binds().getFirst());
            int end = Nodes.end(outer.binds().getLast());
            for (Node n : scope.nodes) {
                if (n instanceof Ident i
                        && innerNames.contains(Scope.name(i))
                        && Nodes.start(i) >= start
                        && Nodes.end(i) <= end) merge = false;
            }
            for (Binding b : outer.binds()) {
                if (b instanceof Inherit inh && inh.from() == null) {
                    for (AttrNode a : inh.names()) if (innerNames.contains(Nodes.staticName(a))) merge = false;
                }
            }
        }
        List<Edit> fix =
                merge ? List.of(Text.delete(src, outer.in().start(), inner.let().end())) : List.of();
        report(Rule.COLLAPSIBLE_LET_IN, inner, "this 'let' can be merged into the outer one", fix);
    }

    private Set<String> names(Let l) {
        Set<String> names = new HashSet<>();
        for (Def d : scope.owned.getOrDefault(l, List.of())) names.add(d.name);
        return names;
    }

    private void binds(List<Binding> binds, boolean recursive) {
        Map<String, List<Assign>> nested = new LinkedHashMap<>();
        Set<String> whole = new HashSet<>();
        for (Binding b : binds) {
            switch (b) {
                case Inherit i -> {
                    for (AttrNode a : i.names()) whole.add(Nodes.staticName(a));
                    if (i.names().isEmpty()) {
                        report(
                                Rule.EMPTY_INHERIT,
                                i.inherit().start(),
                                i.semi().end(),
                                "'inherit' with no names",
                                List.of(Text.delete(
                                        src, i.inherit().start(), i.semi().end())));
                    }
                }
                case Assign a -> {
                    String name = Nodes.staticName(a.path().getFirst().attr());
                    if (name == null) continue;
                    if (a.path().size() > 1)
                        nested.computeIfAbsent(name, k -> new ArrayList<>()).add(a);
                    else {
                        whole.add(name);
                        manualInherit(a, name, recursive);
                    }
                }
            }
        }
        for (var e : nested.entrySet()) {
            List<Assign> as = e.getValue();
            if (as.size() < 3) continue;
            int shared = shared(as);
            AttrNode first = as.getFirst().path().getFirst().attr();
            report(
                    Rule.REPEATED_KEYS,
                    Nodes.attrStart(first),
                    Nodes.attrEnd(first),
                    "'"
                            + src.substring(
                                    Nodes.attrStart(first),
                                    Nodes.attrEnd(
                                            as.getFirst().path().get(shared - 1).attr()))
                            + "' is set " + as.size() + " times; group its attributes in one attribute set",
                    whole.contains(e.getKey()) ? List.of() : group(as, shared));
        }
    }

    /** How many attributes all paths start with, leaving at least one to each. */
    private static int shared(List<Assign> as) {
        int n = 1;
        while (true) {
            String next = as.getFirst().path().size() > n + 1
                    ? Nodes.staticName(as.getFirst().path().get(n).attr())
                    : null;
            int i = n;
            if (next == null
                    || !as.stream()
                            .allMatch(a -> a.path().size() > i + 1
                                    && next.equals(
                                            Nodes.staticName(a.path().get(i).attr())))) return n;
            n++;
        }
    }

    /**
     * Moves the bindings into one attribute set in place of the first, as Nix desugars them: on one line when they
     * are, else one per line with their comments.
     */
    private List<Edit> group(List<Assign> as, int shared) {
        Assign first = as.getFirst();
        int start = Nodes.attrStart(first.path().getFirst().attr());
        String name =
                src.substring(start, Nodes.attrEnd(first.path().get(shared - 1).attr()));
        if (src.substring(start, as.getLast().semi().end()).indexOf('\n') < 0) {
            StringBuilder sb = new StringBuilder(name).append(" = {");
            for (Assign a : as) sb.append(' ').append(rest(a, shared));
            List<Edit> edits = new ArrayList<>();
            edits.add(new Edit(start, first.semi().end(), sb.append(" };").toString()));
            for (Assign a : as.subList(1, as.size()))
                edits.add(Text.delete(src, Nodes.start(a), a.semi().end()));
            return edits;
        }
        String outer = indent(start);
        for (Assign a : as) if (!Text.isBlank(outer) || !indent(Nodes.start(a)).equals(outer)) return List.of();
        StringBuilder sb = new StringBuilder(name).append(" = {\n");
        List<Edit> edits = new ArrayList<>();
        int previous = start;
        for (Assign a : as) {
            int from = a == first ? Nodes.start(a) : leadingComments(Nodes.start(a));
            int to = trailingComment(a.semi().end());
            if (a != first && blankBefore(from)) sb.append('\n');
            sb.append(outer)
                    .append("  ")
                    .append(indented(from, Nodes.start(a)))
                    .append(indented(Nodes.attrStart(a.path().get(shared).attr()), to))
                    .append('\n');
            edits.add(a == first ? new Edit(start, to, "") : deleteLines(from, to, previous));
            previous = to;
        }
        Edit head = edits.getFirst();
        edits.set(
                0,
                new Edit(head.start(), head.end(), sb.append(outer).append("};").toString()));
        return edits;
    }

    /**
     * Deletes like {@link Text#delete}, with the blank line above when it follows the binding moved before, ending at
     * {@code previous}, or a blank line or the closing brace follows.
     */
    private Edit deleteLines(int from, int to, int previous) {
        Edit d = Text.delete(src, from, to);
        if (!blankBefore(from) || d.start() == 0 || src.charAt(d.start() - 1) != '\n') return d;
        int next = src.indexOf('\n', d.end());
        String after = src.substring(d.end(), next < 0 ? src.length() : next).strip();
        boolean moved = Text.isBlank(src.substring(previous, d.start()));
        if (!moved && !after.isEmpty() && !after.startsWith("}")) return d;
        return new Edit(src.lastIndexOf('\n', d.start() - 2) + 1, d.end(), "");
    }

    private boolean blankBefore(int at) {
        int lineStart = src.lastIndexOf('\n', at - 1) + 1;
        return lineStart > 0 && Text.isBlank(src.substring(src.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1));
    }

    /** The binding without the first {@code shared} attributes of its path. */
    private String rest(Assign a, int shared) {
        return src.substring(
                Nodes.attrStart(a.path().get(shared).attr()), a.semi().end());
    }

    /** What precedes {@code at} on its line. */
    private String indent(int at) {
        return src.substring(src.lastIndexOf('\n', at - 1) + 1, at);
    }

    /** The start of the comment lines right above the line of {@code at}, else {@code at}. */
    private int leadingComments(int at) {
        int from = at;
        int lineStart = src.lastIndexOf('\n', at - 1) + 1;
        while (lineStart > 0) {
            int prev = src.lastIndexOf('\n', lineStart - 2) + 1;
            String line = src.substring(prev, lineStart - 1).strip();
            if (!line.startsWith("#")) break;
            from = prev + src.substring(prev).indexOf('#');
            lineStart = prev;
        }
        return from;
    }

    /** The end of a {@code #} comment that follows {@code at} on its line, else {@code at}. */
    private int trailingComment(int at) {
        int i = at;
        while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) i++;
        if (i == src.length() || src.charAt(i) != '#') return at;
        int end = src.indexOf('\n', i);
        return end < 0 ? src.length() : end;
    }

    /** The source between {@code start} and {@code end}, its lines indented by two more spaces. */
    private String indented(int start, int end) {
        StringBuilder sb = new StringBuilder();
        int last = start;
        for (int i = src.indexOf('\n', start); i >= 0 && i < end; i = src.indexOf('\n', i + 1)) {
            sb.append(src, last, i + 1);
            last = i + 1;
            if (i + 1 < end && src.charAt(i + 1) != '\n' && shiftable(i)) sb.append("  ");
        }
        return sb.append(src, last, end).toString();
    }

    /**
     * Whether the line after the line break at {@code at} can move: not in a string, unless an indented one whose
     * content starts on a line of its own, as Nix strips their common indentation.
     */
    private boolean shiftable(int at) {
        Str in = null;
        for (Node n : scope.nodes) {
            if (n instanceof Str s
                    && s.open().start() < at
                    && at < s.close().start()
                    && (in == null || s.open().start() > in.open().start())) in = s;
        }
        return in == null
                || in.open().type() == TokenType.IND_STR_OPEN
                        && Text.isBlank(src.substring(
                                in.open().start() + 2,
                                src.indexOf('\n', in.open().start())));
    }

    private void manualInherit(Assign a, String name, boolean recursive) {
        if (!(a.path().getFirst().attr() instanceof Ident)) return;
        int start = Nodes.start(a);
        int end = a.semi().end();
        if (a.value() instanceof Ident v && Scope.name(v).equals(name)) {
            // In a `let` or a `rec`, `x = x;` refers to itself.
            if (recursive) return;
            report(
                    Rule.MANUAL_INHERIT,
                    start,
                    end,
                    "'" + name + " = " + name + ";' can be written 'inherit " + name + ";'",
                    List.of(new Edit(start, end, "inherit " + name + ";")));
        } else if (a.value() instanceof Select s
                && s.fallback() == null
                && s.path().getLast().attr() instanceof Ident attr
                && Scope.name(attr).equals(name)) {
            List<Sel> path = s.path();
            String from = path.size() > 1
                    ? src.substring(
                            start(s), Nodes.attrEnd(path.get(path.size() - 2).attr()))
                    : Nodes.text(src, s.target() instanceof Paren p ? p.expr() : s.target());
            report(
                    Rule.MANUAL_INHERIT_FROM,
                    start,
                    end,
                    "can be written 'inherit (...) " + name + ";'",
                    List.of(new Edit(start, end, "inherit (" + from + ") " + name + ";")));
        }
    }

    private void pattern(Lambda l) {
        Formals fs = l.formals();
        if (fs == null || !fs.list().isEmpty()) return;
        if (fs.ellipsis() == null && l.arg() == null) {
            report(
                    Rule.EMPTY_PATTERN,
                    fs.open().start(),
                    fs.close().end(),
                    "empty pattern; use '_' if the argument is not needed",
                    List.of());
        } else if (fs.ellipsis() != null && l.arg() != null) {
            report(
                    Rule.REDUNDANT_PATTERN_BIND,
                    start(l),
                    l.colon().start(),
                    "'{ ... }@" + l.arg().text() + "' is the same as '"
                            + l.arg().text() + "'",
                    List.of(new Edit(start(l), l.colon().start(), l.arg().text())));
        }
    }

    private void etaReduction(Lambda l) {
        if (l.formals() != null || !(l.body() instanceof Call c) || c.args().size() != 1) return;
        // Partial applications stay as they are, for readability.
        if (!(c.fn() instanceof Ident || c.fn() instanceof Select s && s.fallback() == null)) return;
        if (!(c.args().getFirst() instanceof Ident x)) return;
        Def def = scope.resolved.get(x);
        if (def == null || def.owner != l || def.uses.size() != 1) return;
        if (definedBy(c.fn(), l)) return;
        String text = Nodes.text(src, c.fn());
        int prec = Nodes.prec(c.fn());
        report(
                Rule.ETA_REDUCTION,
                l,
                "'" + l.arg().text() + ": f " + l.arg().text() + "' can be written 'f'",
                List.of(new Edit(start(l), end(l), Nodes.fit(scope.parents.get(l), l, text, prec))));
    }

    /** Whether {@code fn} names the binding that {@code l} is the value of: {@code f = x: f x;} must stay lazy. */
    private boolean definedBy(Node fn, Lambda l) {
        Node root = fn instanceof Select s ? s.target() : fn;
        if (!(root instanceof Ident i) || !(scope.resolved.get(i) instanceof Def d)) return false;
        for (Object site : d.sites) {
            if (site instanceof Assign a && start(a.value()) <= start(l) && end(l) <= end(a.value())) return true;
        }
        return false;
    }

    private void uselessParens(Paren p) {
        Node parent = scope.parents.get(p);
        Node e = p.expr();
        // Of nested parentheses, the inner ones are reported: the next round sees the outer ones.
        if (e instanceof Paren || e instanceof Ident i && i.tok().type() == TokenType.OR_KW) return;
        int prec = Nodes.prec(e);
        // `f (a.b or c)` would be valid without them, but harder to read.
        boolean atom = prec >= Nodes.SELECT
                        && prec >= Nodes.required(parent, p)
                        && !(e instanceof Select s && s.fallback() != null)
                || parent instanceof Select ps && ps.target() == p && e instanceof Select s && s.fallback() == null;
        if (!atom && !wholeExpression(parent, p)) return;
        report(
                Rule.USELESS_PARENS,
                p,
                "useless parentheses",
                List.of(
                        Text.removeOpen(src, p.open().start(), Nodes.start(e)),
                        Text.removeClose(src, Nodes.end(e), p.close().end())));
    }

    /** Whether {@code child} fills a place where any expression can stand without parentheses. */
    private static boolean wholeExpression(Node parent, Node child) {
        return switch (parent) {
            case null -> true;
            case Paren p -> true;
            case Let l -> true;
            case AttrSet s -> true;
            case Str s -> true;
            case Path p -> true;
            case Select s -> child != s.target() && child != s.fallback();
            case HasAttr h -> child != h.target();
            default -> false;
        };
    }

    private void isNull(Call c) {
        boolean isNull = c.fn() instanceof Ident i && Scope.name(i).equals("isNull") && scope.isGlobal(i)
                || c.fn() instanceof Select s && isBuiltin(s, "isNull");
        if (!isNull) return;
        List<Edit> fix = List.of();
        if (c.args().size() == 1) {
            String text = Nodes.text(src, c.args().getFirst()) + " == null";
            fix = List.of(new Edit(start(c), end(c), Nodes.fit(scope.parents.get(c), c, text, 6)));
        }
        report(Rule.DEPRECATED_IS_NULL, c, "'isNull' is deprecated; use '== null'", fix);
    }

    private boolean isBuiltin(Select s, String name) {
        return s.fallback() == null
                && s.target() instanceof Ident b
                && Scope.name(b).equals("builtins")
                && scope.isGlobal(b)
                && s.path().size() == 1
                && name.equals(Nodes.staticName(s.path().getFirst().attr()));
    }

    private void fasterBuiltin(Select s) {
        if (!(s.target() instanceof Ident lib)
                || !Scope.name(lib).equals("lib")
                || s.path().size() > 2) return;
        String name = Nodes.staticName(s.path().getLast().attr());
        if (!"groupBy".equals(name) && !"zipAttrsWith".equals(name)) return;
        if (s.path().size() == 2) {
            String set = Nodes.staticName(s.path().getFirst().attr());
            if (!"lists".equals(set) && !"attrsets".equals(set)) return;
        }
        int end = Nodes.attrEnd(s.path().getLast().attr());
        List<Edit> fix = scope.lookup(lib, "builtins") == null
                ? List.of(new Edit(start(s), end, "builtins." + name))
                : List.of();
        report(
                Rule.FASTER_BUILTIN,
                start(s),
                end,
                "'" + src.substring(start(s), end) + "' can be 'builtins." + name + "'",
                fix);
    }

    private void uselessHasAttr(If i) {
        if (!(i.cond() instanceof HasAttr h) || !(i.then() instanceof Select s) || s.fallback() != null) return;
        String tested = Nodes.text(src, h.target()) + "."
                + src.substring(
                        Nodes.attrStart(h.path().getFirst().attr()),
                        Nodes.attrEnd(h.path().getLast().attr()));
        if (!tested.equals(Nodes.text(src, s))) return;
        Node otherwise = i.otherwise();
        String fallback = Nodes.prec(otherwise) >= Nodes.SELECT
                ? Nodes.text(src, otherwise)
                : "(" + Nodes.text(src, otherwise) + ")";
        String text = Nodes.text(src, s) + " or " + fallback;
        report(
                Rule.USELESS_HAS_ATTR,
                i,
                "can be written '" + tested + " or ...'",
                List.of(new Edit(start(i), end(i), Nodes.fit(scope.parents.get(i), i, text, Nodes.SELECT))));
    }

    private static int start(Node n) {
        return Nodes.start(n);
    }

    private static int end(Node n) {
        return Nodes.end(n);
    }

    private void report(Rule rule, Node n, String message, List<Edit> fix) {
        report(rule, start(n), end(n), message, fix);
    }

    private void report(Rule rule, int start, int end, String message, List<Edit> fix) {
        out.add(new Diagnostic(rule, start, end, message, fix));
    }
}
