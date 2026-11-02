package org.affogato.format;

import static org.affogato.format.Tree.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.affogato.parser.Syntax;
import org.affogato.parser.Syntax.Node;
import org.affogato.parser.TokenType;

/**
 * Builds nixfmt's tree from affogato's concrete syntax tree, attaching the comments between tokens as nixfmt's lexer
 * (Nixfmt.Lexer) and parser (Nixfmt.Parser) do.
 */
final class Convert {
    private final String src;
    private final boolean strict;
    private final int[] lineBreaks;
    private List<Trivium> pending = new ArrayList<>();
    private final Map<Tok, Tok> toks = new HashMap<>();
    /** Line start of the open disabled region, or -1. */
    private int pendingDisable = -1;

    private int cuts;
    /** Interpolation depth: nixfmt's strict mode leaves the source lines inside interpolations alone. */
    private int depth;

    private Convert(String src, boolean strict) {
        this.src = src;
        this.strict = strict;
        this.lineBreaks = java.util.stream.IntStream.range(0, src.length())
                .filter(i -> src.charAt(i) == '\n')
                .toArray();
    }

    static Whole file(String src, Node root, boolean strict) {
        Convert c = new Convert(src, strict);
        return c.whole(root);
    }

    private Whole whole(Node root) {
        Scan sc = scan(skipHSpace(0));
        pending.addAll(convertLeading(sc.items));
        Expression e = expr(root);
        if (pendingDisable >= 0) pending.add(new DisabledToEof(src.substring(pendingDisable)));
        return new Whole(e, take());
    }

    // POSITIONS

    private int line(int offset) {
        if (strict && depth == 0) return 0;
        int lo = 0, hi = lineBreaks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (lineBreaks[mid] < offset) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Megaparsec's 1-based column, with tab stops every 8 columns. */
    private int column(int offset) {
        int start = src.lastIndexOf('\n', offset - 1) + 1;
        int col = 1;
        for (int i = start; i < offset; i++) {
            char c = src.charAt(i);
            if (Character.isLowSurrogate(c)) continue;
            col = c == '\t' ? col + 8 - ((col - 1) % 8) : col + 1;
        }
        return col;
    }

    // TRIVIA

    private sealed interface PT {}

    private record PNewlines(int n) implements PT {}

    private record PLineComment(String text, int col) implements PT {}

    private record PBlockComment(boolean doc, List<String> lines) implements PT {}

    private record PLangAnnotation(String lang) implements PT {}

    private record PDirective(boolean disable, int start, int end) implements PT {}

    private record Scan(List<PT> items, int end) {}

    static boolean isSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r') || c == ' ' || Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    private static boolean isHSpace(char c) {
        return isSpace(c) && c != '\n' && c != '\r';
    }

    private int skipHSpace(int p) {
        while (p < src.length() && isHSpace(src.charAt(p))) p++;
        return p;
    }

    private int eol(int p) {
        if (src.startsWith("\n", p)) return p + 1;
        if (src.startsWith("\r\n", p)) return p + 2;
        return -1;
    }

    private Scan scan(int p) {
        List<PT> items = new ArrayList<>();
        while (true) {
            Object[] r;
            if ((r = languageAnnotation(p)) != null || (r = formatDirective(p)) != null) {
                items.add((PT) r[0]);
                p = (int) r[1];
            } else if (src.startsWith("#", p)) {
                int col = column(p);
                int e = p + 1;
                while (e < src.length() && src.charAt(e) != '\n' && src.charAt(e) != '\r') e++;
                items.add(new PLineComment(src.substring(p + 1, e).stripTrailing(), col));
                p = skipHSpace(e);
            } else if ((r = blockComment(p)) != null) {
                items.add((PT) r[0]);
                p = (int) r[1];
            } else if (eol(p) >= 0) {
                int n = 0;
                int e;
                while ((e = eol(p)) >= 0) {
                    n++;
                    p = skipHSpace(e);
                }
                items.add(new PNewlines(n));
            } else {
                return new Scan(items, p);
            }
        }
    }

    private Object[] formatDirective(int p) {
        boolean disable;
        int q;
        if (src.startsWith("/*nixfmt:disable*/", p)) {
            disable = true;
            q = p + "/*nixfmt:disable*/".length();
        } else if (src.startsWith("/*nixfmt:enable*/", p)) {
            disable = false;
            q = p + "/*nixfmt:enable*/".length();
        } else {
            return null;
        }
        int e = q;
        while (e < src.length() && src.charAt(e) != '\n' && src.charAt(e) != '\r') e++;
        for (int i = q; i < e; i++) if (!isHSpace(src.charAt(i))) return null;
        return new Object[] {new PDirective(disable, p, e), e};
    }

    private Object[] blockComment(int p) {
        if (!src.startsWith("/*", p)) return null;
        int pos = column(p) - 1;
        int q = p + 2;
        boolean doc = src.startsWith("*", q) && !src.startsWith("*/", q);
        if (doc) q++;
        int end = src.indexOf("*/", q);
        if (end < 0) return null;
        List<String> lines = splitCommentLines(src.substring(q, end));
        lines = removeStars(pos, lines);
        lines = new ArrayList<>(lines.stream().map(String::stripTrailing).toList());
        dropTrailingEmpty(lines);
        lines = fixIndent(pos, lines);
        while (!lines.isEmpty() && lines.getFirst().isEmpty()) lines.removeFirst();
        return new Object[] {new PBlockComment(doc, lines), skipHSpace(end + 2)};
    }

    private static void dropTrailingEmpty(List<String> lines) {
        while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
    }

    /** {@code Text.lines} after normalising line ends, with trailing whitespace and empty last lines dropped. */
    private static List<String> splitCommentLines(String s) {
        s = s.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        if (!s.isEmpty()) {
            lines.addAll(Arrays.asList(s.split("\n", -1)));
            if (s.endsWith("\n")) lines.removeLast();
        }
        lines.replaceAll(String::stripTrailing);
        dropTrailingEmpty(lines);
        return lines;
    }

    private static List<String> removeStars(int pos, List<String> lines) {
        if (lines.isEmpty()) return lines;
        String start = " ".repeat(pos) + " *";
        String newStart = " ".repeat(pos);
        List<String> out = new ArrayList<>();
        out.add(lines.getFirst());
        for (String l : lines.subList(1, lines.size())) {
            if (!l.startsWith(start)) return lines;
            out.add(newStart + l.substring(start.length()));
        }
        return out;
    }

    private static List<String> fixIndent(int pos, List<String> lines) {
        if (lines.isEmpty()) return lines;
        String h = lines.getFirst();
        int offset = h.startsWith(" ") ? pos + 3 : pos + 2;
        int n = offset;
        for (String l : lines.subList(1, lines.size())) {
            if (isSpaces(l)) continue;
            int k = 0;
            while (k < l.length() && l.charAt(k) == ' ') k++;
            n = Math.min(n, k);
        }
        List<String> out = new ArrayList<>();
        out.add(h.strip());
        String prefix = " ".repeat(n);
        for (String l : lines.subList(1, lines.size())) {
            out.add(l.startsWith(prefix) ? l.substring(n) : stripStart(l));
        }
        return out;
    }

    static boolean isSpaces(String s) {
        return s.chars().allMatch(c -> c == ' ');
    }

    static String stripStart(String s) {
        int i = 0;
        while (i < s.length() && isSpace(s.charAt(i))) i++;
        return s.substring(i);
    }

    static String strip(String s) {
        int j = s.length();
        while (j > 0 && isSpace(s.charAt(j - 1))) j--;
        return stripStart(s.substring(0, j));
    }

    private Object[] languageAnnotation(int p) {
        Object[] r = blockComment(p);
        if (r == null
                || !(r[0] instanceof PBlockComment(boolean doc, List<String> lines))
                || doc
                || lines.size() != 1) {
            return null;
        }
        int q = skipHSpace((int) r[1]);
        int e = eol(q);
        if (e >= 0) q = skipHSpace(e);
        if (!src.startsWith("\"", q) && !src.startsWith("''", q)) return null;
        String lang = strip(lines.getFirst());
        if (lang.isEmpty() || lang.length() > 30) return null;
        for (char c : lang.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && "-+._".indexOf(c) < 0) return null;
        }
        return new Object[] {new PLangAnnotation(lang), r[1]};
    }

    private static boolean isTrailing(PT t) {
        return switch (t) {
            case PLineComment c -> true;
            case PBlockComment b -> !b.doc() && b.lines().size() <= 1;
            case PDirective d -> true;
            default -> false;
        };
    }

    private static String convertTrailing(List<PT> trailing) {
        List<String> texts = new ArrayList<>();
        for (PT t : trailing) {
            String s = switch (t) {
                case PLineComment c -> strip(c.text());
                case PBlockComment b
                when !b.doc() && b.lines().size() == 1 -> strip(b.lines().getFirst());
                case PDirective d -> d.disable() ? "nixfmt:disable" : "nixfmt:enable";
                default -> "";
            };
            if (!s.isEmpty()) texts.add(s);
        }
        return texts.isEmpty() ? null : String.join(" ", texts);
    }

    private List<Trivium> convertLeading(List<PT> pts) {
        List<Trivium> out = new ArrayList<>();
        for (PT t : pts) {
            switch (t) {
                case PNewlines n -> {
                    if (n.n() > 1) out.add(EMPTY_LINE);
                }
                case PLineComment c -> out.add(new LineComment(c.text()));
                case PBlockComment b -> {
                    if (b.lines().isEmpty()) {
                    } else if (!b.doc() && b.lines().size() == 1) {
                        out.add(new LineComment(" " + strip(b.lines().getFirst())));
                    } else {
                        out.add(new BlockComment(b.doc(), b.lines()));
                    }
                }
                case PLangAnnotation l -> out.add(new LanguageAnnotation(l.lang()));
                case PDirective d -> out.add(resolveDirective(d));
            }
        }
        return out;
    }

    private Trivium resolveDirective(PDirective d) {
        int lineStart = src.lastIndexOf('\n', d.start() - 1) + 1;
        for (int i = lineStart; i < d.start(); i++) {
            if (!isHSpace(src.charAt(i))) return new LineComment(d.disable() ? " nixfmt:disable" : " nixfmt:enable");
        }
        if (d.disable()) {
            if (pendingDisable < 0) pendingDisable = lineStart;
            return new Disable();
        }
        if (pendingDisable >= 0) {
            String raw = src.substring(pendingDisable, d.end());
            pendingDisable = -1;
            return new Enable(raw);
        }
        return new Enable(null);
    }

    /** Splits the trivia after a token into its trailing comment and the leading trivia of the next one. */
    private String afterToken(int end) {
        Scan sc = scan(skipHSpace(end));
        List<PT> pts = sc.items;
        int nextCol = column(sc.end);
        int k = 0;
        while (k < pts.size() && isTrailing(pts.get(k))) k++;
        List<PT> trailing = pts.subList(0, k), leading = pts.subList(k, pts.size());
        if (trailing.size() == 1 && trailing.getFirst() instanceof PLineComment(String t, int pos)) {
            if (leading.size() >= 2
                            && leading.get(0) instanceof PNewlines(int n)
                            && n == 1
                            && leading.get(1) instanceof PLineComment c2
                            && c2.col() == pos
                    || leading.size() == 1
                            && leading.getFirst() instanceof PNewlines(int n2)
                            && n2 == 1
                            && pos == nextCol) {
                pending.addAll(convertLeading(pts));
                return null;
            }
        }
        String trail = convertTrailing(trailing);
        pending.addAll(convertLeading(leading));
        return trail;
    }

    private List<Trivium> take() {
        if (pending.isEmpty()) return List.of();
        List<Trivium> t = pending;
        pending = new ArrayList<>();
        return t;
    }

    private <T> Ann<T> lexeme(int start, Supplier<T> body, Supplier<Integer> end) {
        List<Trivium> pre = take();
        int line = line(start);
        T value = body.get();
        String trail = afterToken(end.get());
        return new Ann<>(pre, line, value, trail);
    }

    private Ann<Tok> leaf(org.affogato.parser.Token t) {
        TokKind kind = switch (t.type()) {
            case ID, OR_KW -> TokKind.IDENTIFIER;
            case INT -> TokKind.INTEGER;
            case FLOAT -> TokKind.FLOAT;
            case SPATH -> TokKind.ENV_PATH;
            default -> TokKind.SYMBOL;
        };
        return lexeme(t.start(), () -> toks.computeIfAbsent(new Tok(kind, t.text()), k -> k), t::end);
    }

    private Ann<Tok> leafOrNull(org.affogato.parser.Token t) {
        return t == null ? null : leaf(t);
    }

    private Whole wholeInner(Node e, int afterOpen) {
        List<Trivium> saved = take();
        int before = pendingDisable;
        int outerCuts = cuts;
        Scan sc = scan(skipHSpace(afterOpen));
        pending.addAll(convertLeading(sc.items));
        depth++;
        Expression x = expr(e);
        depth--;
        List<Trivium> trivia = take();
        cuts = outerCuts + (before == pendingDisable ? 0 : 1);
        pending.addAll(saved);
        return new Whole(x, trivia);
    }

    // EXPRESSIONS

    private Expression expr(Node n) {
        return switch (n) {
            case Syntax.Call c -> {
                Expression acc = expr(c.fn());
                for (Node arg : c.args()) acc = applyAll(acc, arg);
                yield acc;
            }
            case Syntax.Select s -> applyAll(null, s);
            case Syntax.HasAttr h -> {
                Expression target = expr(h.target());
                Check check = new Check(leaf(h.question()), sels(h.path()));
                if (target instanceof MemberCheck m) {
                    List<Check> checks = new ArrayList<>(m.checks());
                    checks.add(check);
                    yield new MemberCheck(m.expr(), checks);
                }
                yield new MemberCheck(target, List.of(check));
            }
            case Syntax.BinOp b -> {
                Expression left = expr(b.left());
                Ann<Tok> op = leaf(b.op());
                Expression right = expr(b.right());
                if (b.op().type() == TokenType.PLUS && left instanceof Operation l && l.op().sameValue(op)) {
                    yield new Operation(l.left(), l.op(), new Operation(l.right(), op, right));
                }
                yield new Operation(left, op, right);
            }
            case Syntax.Negate m -> {
                Ann<Tok> minus = leaf(m.minus());
                yield new Negation(minus, expr(m.operand()));
            }
            case Syntax.Not x -> {
                Ann<Tok> bang = leaf(x.not());
                yield new Inversion(bang, expr(x.operand()));
            }
            case Syntax.Lambda l -> lambda(l);
            case Syntax.Let l -> {
                Ann<Tok> let = leaf(l.let());
                List<Item<Binder>> binders = binders(l.binds());
                Ann<Tok> in = leaf(l.in());
                yield new Let(let, binders, in, expr(l.body()));
            }
            case Syntax.With w -> {
                Ann<Tok> with = leaf(w.with());
                Expression scope = expr(w.scope());
                Ann<Tok> semi = leaf(w.semi());
                yield new With(with, scope, semi, expr(w.body()));
            }
            case Syntax.Assert a -> {
                Ann<Tok> kw = leaf(a.assert_());
                Expression cond = expr(a.cond());
                Ann<Tok> semi = leaf(a.semi());
                yield new Assert(kw, cond, semi, expr(a.body()));
            }
            case Syntax.If i -> {
                Ann<Tok> if_ = leaf(i.if_());
                Expression cond = expr(i.cond());
                Ann<Tok> then_ = leaf(i.then_());
                Expression then = expr(i.then());
                Ann<Tok> else_ = leaf(i.else_());
                yield new If(if_, cond, then_, then, else_, expr(i.otherwise()));
            }
            default -> term(n, null);
        };
    }

    /**
     * Applies {@code acc} to the term {@code n}, then to the {@code or} identifiers that nixfmt's grammar leaves
     * outside it; with a null {@code acc} the term itself is the function.
     */
    private Expression applyAll(Expression acc, Node n) {
        List<org.affogato.parser.Token> extra = new ArrayList<>();
        Term t = term(n, extra);
        acc = acc == null ? t : new Application(acc, t);
        for (org.affogato.parser.Token or : extra) acc = new Application(acc, new Token(leaf(or)));
        return acc;
    }

    private static boolean isOrCall(Node n) {
        return n instanceof Syntax.Call c
                && c.args().size() == 1
                && c.args().getFirst() instanceof Syntax.Ident i
                && i.tok().type() == TokenType.OR_KW;
    }

    /**
     * Converts a node that is a term in nixfmt's grammar. affogato's parser takes a trailing {@code or} identifier
     * after a simple expression as an application; nixfmt keeps it out of the term, so it goes into {@code extra}.
     */
    private Term term(Node n, List<org.affogato.parser.Token> extra) {
        if (extra != null && isOrCall(n)) {
            Syntax.Call c = (Syntax.Call) n;
            Term t = term(c.fn(), extra);
            extra.add(((Syntax.Ident) c.args().getFirst()).tok());
            return t;
        }
        return switch (n) {
            case Syntax.Ident i -> new Token(leaf(i.tok()));
            case Syntax.IntLit i -> new Token(leaf(i.tok()));
            case Syntax.FloatLit f -> new Token(leaf(f.tok()));
            case Syntax.SearchPath s -> new Token(leaf(s.tok()));
            case Syntax.Uri u -> {
                org.affogato.parser.Token t = u.tok();
                yield new SimpleString(
                        lexeme(t.start(), () -> List.of(List.<StringPart>of(new TextPart(t.text()))), t::end));
            }
            case Syntax.Str s -> string(s);
            case Syntax.Path p -> new PathTerm(lexeme(p.head().start(), () -> pathParts(p), () -> pathEnd(p)));
            case Syntax.Paren p -> {
                Ann<Tok> open = leaf(p.open());
                Expression e = expr(p.expr());
                yield new Parenthesized(open, e, leaf(p.close()));
            }
            case Syntax.AttrSet s -> {
                Ann<Tok> krec = leafOrNull(s.rec());
                Ann<Tok> open = leaf(s.open());
                List<Item<Binder>> items = binders(s.binds());
                yield new SetTerm(krec, open, items, leaf(s.close()));
            }
            case Syntax.ListExpr l -> {
                Ann<Tok> open = leaf(l.open());
                List<Item<Term>> items = new ArrayList<>();
                for (Node item : l.items()) {
                    List<org.affogato.parser.Token> more = new ArrayList<>();
                    flushComments(items);
                    items.add(new Of<>(term(item, more)));
                    for (org.affogato.parser.Token or : more) {
                        flushComments(items);
                        items.add(new Of<>(new Token(leaf(or))));
                    }
                }
                flushComments(items);
                yield new ListTerm(open, List.copyOf(items), leaf(l.close()));
            }
            case Syntax.Select s -> {
                Term target = term(s.target(), null);
                List<Selector> sels = sels(s.path());
                Ann<Tok> orKw = leafOrNull(s.or());
                Term def = s.fallback() == null ? null : term(s.fallback(), extra);
                yield new Selection(target, sels, orKw, def);
            }
            default -> throw new IllegalStateException("not a term: " + n);
        };
    }

    private <T> void flushComments(List<Item<T>> items) {
        if (!pending.isEmpty()) items.add(new Comments<>(take()));
    }

    private List<Selector> sels(List<Syntax.Sel> path) {
        List<Selector> out = new ArrayList<>();
        for (Syntax.Sel s : path) {
            Ann<Tok> dot = leafOrNull(s.dot());
            out.add(new Selector(dot, simpleSelector(s.attr())));
        }
        return List.copyOf(out);
    }

    private SimpleSelector simpleSelector(Syntax.AttrNode a) {
        return switch (a) {
            case Syntax.Ident i -> new IDSelector(leaf(i.tok()));
            case Syntax.Str s ->
                new StringSelector(lexeme(
                        s.open().start(),
                        () -> simpleStringLines(s),
                        () -> s.close().end()));
            case Syntax.Dyn d ->
                new InterpolSelector(lexeme(
                        d.open().start(),
                        () -> new Interpolation(wholeInner(d.expr(), d.open().end())),
                        () -> d.close().end()));
        };
    }

    private List<Item<Binder>> binders(List<Syntax.Binding> binds) {
        List<Item<Binder>> items = new ArrayList<>();
        for (Syntax.Binding b : binds) {
            flushComments(items);
            items.add(new Of<>(binder(b)));
        }
        flushComments(items);
        return List.copyOf(items);
    }

    private Binder binder(Syntax.Binding b) {
        return switch (b) {
            case Syntax.Assign a -> {
                List<Selector> sels = sels(a.path());
                Ann<Tok> assign = leaf(a.assign());
                Expression e = expr(a.value());
                yield new Assignment(sels, assign, e, leaf(a.semi()));
            }
            case Syntax.Inherit i -> {
                Ann<Tok> inherit = leaf(i.inherit());
                Term from = i.from() == null ? null : term(i.from(), null);
                List<SimpleSelector> names = new ArrayList<>();
                for (Syntax.AttrNode a : i.names()) names.add(simpleSelector(a));
                yield new Inherit(inherit, from, List.copyOf(names), leaf(i.semi()));
            }
        };
    }

    private Expression lambda(Syntax.Lambda l) {
        Parameter param;
        Syntax.Formals f = l.formals();
        if (f == null) {
            param = new IDParameter(leaf(l.arg()));
        } else {
            boolean argFirst = l.arg() != null && l.arg().start() < f.open().start();
            Ann<Tok> leftName = argFirst ? leaf(l.arg()) : null;
            Ann<Tok> leftAt = argFirst ? leaf(l.at()) : null;
            Ann<Tok> open = leaf(f.open());
            List<ParamAttr> attrs = new ArrayList<>();
            for (Syntax.Formal x : f.list()) {
                Ann<Tok> name = leaf(x.name());
                Ann<Tok> qmark = leafOrNull(x.question());
                Expression def = x.fallback() == null ? null : expr(x.fallback());
                attrs.add(new Attr(name, qmark, def, leafOrNull(x.comma())));
            }
            if (f.ellipsis() != null) attrs.add(new Ellipsis(leaf(f.ellipsis())));
            Ann<Tok> close = leaf(f.close());
            Ann<Tok> rightAt = !argFirst && l.at() != null ? leaf(l.at()) : null;
            Ann<Tok> rightName = !argFirst && l.arg() != null ? leaf(l.arg()) : null;
            param = new SetParameter(leftName, leftAt, open, List.copyOf(attrs), close, rightAt, rightName);
        }
        Ann<Tok> colon = leaf(l.colon());
        return new Abstraction(param, colon, expr(l.body()));
    }

    // STRINGS AND PATHS

    private List<StringPart> rawParts(String head, List<Syntax.Part> parts) {
        List<StringPart> out = new ArrayList<>();
        StringBuilder text = new StringBuilder(head);
        for (Syntax.Part p : parts) {
            switch (p) {
                case Syntax.Lit l -> text.append(src, l.start(), l.end());
                case Syntax.Interp i -> {
                    if (!text.isEmpty()) out.add(new TextPart(text.toString()));
                    text.setLength(0);
                    out.add(new Interpolation(wholeInner(i.expr(), i.open().end())));
                }
            }
        }
        if (!text.isEmpty()) out.add(new TextPart(text.toString()));
        return out;
    }

    private List<StringPart> pathParts(Syntax.Path p) {
        return normalizeLine(rawParts(p.head().text(), p.rest()));
    }

    private static int pathEnd(Syntax.Path p) {
        if (p.rest().isEmpty()) return p.head().end();
        return switch (p.rest().getLast()) {
            case Syntax.Lit l -> l.end();
            case Syntax.Interp i -> i.close().end();
        };
    }

    private List<List<StringPart>> simpleStringLines(Syntax.Str s) {
        List<List<StringPart>> lines = new ArrayList<>();
        for (List<StringPart> l : splitLines(rawParts("", s.parts()))) lines.add(normalizeLine(l));
        return lines.stream().map(List::copyOf).toList();
    }

    private Term string(Syntax.Str s) {
        if (s.open().type() == TokenType.STR_OPEN) {
            return new SimpleString(lexeme(
                    s.open().start(),
                    () -> simpleStringLines(s),
                    () -> s.close().end()));
        }
        Object[] cutHolder = new Object[1];
        Ann<List<List<StringPart>>> a = lexeme(
                s.open().start(),
                () -> {
                    int before = cuts;
                    String head = src.substring(s.open().start() + 2, s.open().end());
                    List<List<StringPart>> lines = new ArrayList<>();
                    for (List<StringPart> l : splitLines(rawParts(head, s.parts()))) {
                        lines.add(new ArrayList<>(l.stream()
                                .filter(p -> !(p instanceof TextPart(String t) && t.isEmpty()))
                                .toList()));
                    }
                    Object[] r = fixIndentedString(lines);
                    if (cuts != before) cutHolder[0] = r[0];
                    @SuppressWarnings("unchecked")
                    List<List<StringPart>> fixed = (List<List<StringPart>>) r[1];
                    return fixed.stream().map(List::copyOf).toList();
                },
                () -> s.close().end());
        String cut = (String) cutHolder[0];
        if (cut == null && shouldBeSimpleString(a.value())) {
            List<List<StringPart>> converted = new ArrayList<>();
            for (List<StringPart> line : a.value()) {
                converted.add(line.stream()
                        .map(p -> p instanceof TextPart(String t) ? (StringPart) new TextPart(convertEscapes(t)) : p)
                        .toList());
            }
            return new SimpleString(new Ann<>(a.pre(), a.line(), converted, a.trail()));
        }
        return new IndentedString(cut, a);
    }

    private static boolean shouldBeSimpleString(List<List<StringPart>> parts) {
        if (parts.size() > 1) return false;
        for (List<StringPart> line : parts) {
            for (StringPart p : line) {
                if (p instanceof TextPart(String t) && (t.indexOf('"') >= 0 || t.indexOf('\\') >= 0)) return false;
            }
        }
        return true;
    }

    private static String convertEscapes(String t) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            if (t.startsWith("''$", i)) {
                sb.append("\\$");
                i += 3;
            } else if (t.startsWith("'''", i)) {
                sb.append("''");
                i += 3;
            } else {
                sb.append(t.charAt(i++));
            }
        }
        return sb.toString();
    }

    /** Splits the text parts on newlines; the result is never empty. */
    static List<List<StringPart>> splitLines(List<StringPart> parts) {
        List<List<StringPart>> lines = new ArrayList<>();
        List<StringPart> cur = new ArrayList<>();
        for (StringPart p : parts) {
            if (p instanceof TextPart(String t)) {
                String[] pieces = t.split("\n", -1);
                for (int i = 0; i < pieces.length; i++) {
                    if (i > 0) {
                        lines.add(cur);
                        cur = new ArrayList<>();
                    }
                    cur.add(new TextPart(pieces[i]));
                }
            } else {
                cur.add(p);
            }
        }
        lines.add(cur);
        return lines;
    }

    /** Drops empty text parts and merges adjacent ones. */
    static List<StringPart> normalizeLine(List<StringPart> line) {
        List<StringPart> out = new ArrayList<>();
        for (StringPart p : line) {
            if (p instanceof TextPart(String t)) {
                if (t.isEmpty()) continue;
                if (!out.isEmpty() && out.getLast() instanceof TextPart(String prev)) {
                    out.set(out.size() - 1, new TextPart(prev + t));
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }

    private static boolean isEmptyLine(List<StringPart> line) {
        return line.isEmpty() || line.size() == 1 && line.getFirst() instanceof TextPart(String t) && isSpaces(t);
    }

    /** Port of {@code fixIndentedString}: returns the stripped indentation and the lines. */
    private static Object[] fixIndentedString(List<List<StringPart>> lines) {
        if (!lines.isEmpty()) {
            List<StringPart> first = normalizeLine(lines.getFirst());
            if (isEmptyLine(first) && lines.size() > 1) {
                lines = new ArrayList<>(lines.subList(1, lines.size()));
            } else {
                lines.set(0, first);
            }
        }
        if (!lines.isEmpty()) {
            List<StringPart> last = normalizeLine(lines.getLast());
            lines.set(lines.size() - 1, isEmptyLine(last) ? new ArrayList<>() : last);
        }
        String indentation = null;
        for (List<StringPart> line : lines) {
            String head;
            if (line.isEmpty() || isEmptyLine(line)) continue;
            head = line.getFirst() instanceof TextPart(String t) ? t : "";
            indentation = indentation == null ? head : commonPrefix(indentation, head);
        }
        List<List<StringPart>> out = new ArrayList<>();
        if (indentation == null) {
            for (int i = 0; i < lines.size(); i++) out.add(List.of());
            return new Object[] {"", out};
        }
        int k = 0;
        while (k < indentation.length() && indentation.charAt(k) == ' ') k++;
        indentation = indentation.substring(0, k);
        for (List<StringPart> line : lines) {
            List<StringPart> l = new ArrayList<>(line);
            if (!l.isEmpty() && l.getFirst() instanceof TextPart(String t)) {
                l.set(0, new TextPart(t.startsWith(indentation) ? t.substring(indentation.length()) : ""));
            }
            for (List<StringPart> split : splitLines(l)) out.add(normalizeLine(split));
        }
        return new Object[] {indentation, out};
    }

    private static String commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }
}
