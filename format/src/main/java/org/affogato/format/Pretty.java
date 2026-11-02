package org.affogato.format;

import static org.affogato.format.Doc.*;
import static org.affogato.format.Tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Port of nixfmt's Nixfmt.Pretty: renders the tree as a document. */
final class Pretty {
    private Pretty() {}

    private static final Tok COMMA = new Tok(TokKind.SYMBOL, ",");
    private static final Set<String> COMPARISONS = Set.of("<", ">", "<=", ">=", "==", "!=");

    // TRIVIA AND TOKENS

    private static LineComment toLineComment(String c) {
        return new LineComment(" " + c);
    }

    private static <T> Ann<T> moveTrailingCommentUp(Ann<T> a) {
        if (a.trail() == null) return a;
        List<Trivium> pre = new ArrayList<>(a.pre());
        pre.add(toLineComment(a.trail()));
        return new Ann<>(pre, a.line(), a.value(), null);
    }

    private static final AnnMap MOVE_TRAILING_COMMENT_UP = new AnnMap() {
        @Override
        public <T> Ann<T> apply(Ann<T> a) {
            return moveTrailingCommentUp(a);
        }
    };

    private static AnnMap prependTrivia(List<Trivium> extra) {
        return new AnnMap() {
            @Override
            public <T> Ann<T> apply(Ann<T> a) {
                if (extra.isEmpty()) return a;
                List<Trivium> pre = new ArrayList<>(extra);
                pre.addAll(a.pre());
                return a.withPre(pre);
            }
        };
    }

    private static int firstTokenLine(Expression e) {
        return firstToken(e).line();
    }

    private static List<E> trailComment(String c) {
        return c == null ? EMPTY : cat(hardspace, Doc.trailingComment("# " + c), hardline);
    }

    private static List<E> trivium(Trivium t) {
        return switch (t) {
            case EmptyLine e -> emptyline;
            case LineComment c -> cat(comment("#" + c.text()), hardline);
            case LanguageAnnotation l -> cat(comment("/* " + l.lang() + " */"), hardspace);
            case Disable d -> disableMarker;
            case DisabledToEof d -> verbatimToEof(d.raw());
            case Enable e ->
                e.raw() != null ? cat(verbatim(e.raw()), hardline) : cat(comment("/*nixfmt:enable*/"), hardline);
            case BlockComment b -> {
                List<E> lines = new ArrayList<>();
                for (String l : b.lines()) lines.addAll(l.isEmpty() ? emptyline : cat(comment(l), hardline));
                yield cat(comment(b.doc() ? "/**" : "/*"), hardline, offset(2, lines), comment("*/"), hardline);
            }
        };
    }

    static List<E> trivia(List<Trivium> ts) {
        if (ts.isEmpty()) return EMPTY;
        if (ts.size() == 1 && ts.getFirst() instanceof LanguageAnnotation) return trivium(ts.getFirst());
        List<E> out = new ArrayList<>(hardline);
        for (Trivium t : ts) out.addAll(trivium(t));
        return out;
    }

    private static <T> List<E> ann(Ann<T> a, List<E> value) {
        return cat(trivia(a.pre()), value, trailComment(a.trail()));
    }

    private static List<E> leaf(Ann<Tok> a) {
        return a == null ? EMPTY : ann(a, text(a.value().text()));
    }

    private static List<E> token(Tok t) {
        return text(t.text());
    }

    // ITEMS

    private interface Render<T> {
        List<E> apply(T t);
    }

    private static <T> List<E> item(Item<T> i, Render<T> r) {
        return switch (i) {
            case Comments<T> c -> trivia(c.trivia());
            case Of<T> o -> group(r.apply(o.value()));
        };
    }

    private static <T> List<E> renderItems(List<E> separator, List<Item<T>> items, Render<T> r) {
        List<E> out = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            Item<T> it = items.get(i);
            boolean more;
            if (it instanceof Comments<T> c
                    && i + 1 < items.size()
                    && items.get(i + 1) instanceof Of<T> next
                    && !c.trivia().isEmpty()
                    && c.trivia().getLast() instanceof LanguageAnnotation lang) {
                out.addAll(trivia(c.trivia().subList(0, c.trivia().size() - 1)));
                out.addAll(trivium(lang));
                out.addAll(hardspace);
                out.addAll(group(r.apply(next.value())));
                i += 2;
            } else {
                out.addAll(item(it, r));
                i++;
            }
            more = i < items.size();
            if (more) out.addAll(separator);
        }
        return out;
    }

    private static <T> boolean hasOnlyComments(List<Item<T>> items) {
        return !items.isEmpty() && items.stream().allMatch(i -> i instanceof Comments);
    }

    private static List<E> renderList(List<E> itemSep, Ann<Tok> open, List<Item<Term>> items, Ann<Tok> close) {
        List<E> sur;
        if (open.line() != close.line()) sur = hardline;
        else if (hasOnlyComments(items)) sur = hardline;
        else if (open.hasTrivia() && items.isEmpty()) sur = hardline;
        else if (items.isEmpty()) sur = hardspace;
        else sur = line;
        return cat(
                leaf(open.withTrail(null)),
                surroundWith(sur, nest(cat(trailComment(open.trail()), renderItems(itemSep, items, Pretty::termInst)))),
                leaf(close));
    }

    // SELECTORS AND BINDERS

    private static List<E> simpleSelector(SimpleSelector s) {
        return switch (s) {
            case IDSelector i -> leaf(i.name());
            case InterpolSelector i -> ann(i.interp(), stringPart(i.interp().value()));
            case StringSelector i -> ann(i.str(), prettySimpleString(i.str().value()));
        };
    }

    private static List<E> selector(Selector s) {
        return cat(leaf(s.dot()), simpleSelector(s.sel()));
    }

    private static List<E> selectors(List<Selector> sels) {
        List<E> out = new ArrayList<>();
        for (Selector s : sels) out.addAll(selector(s));
        return out;
    }

    private static List<E> binder(Binder b) {
        return switch (b) {
            case Inherit i -> {
                boolean oneLine =
                        i.inherit().line() == i.semi().line() && i.sels().size() < 4;
                List<E> sep = oneLine ? line : hardline;
                List<E> nosep = oneLine ? line_ : hardline;
                List<List<E>> ids =
                        i.sels().stream().map(Pretty::simpleSelector).toList();
                if (i.from() == null) {
                    yield group(cat(leaf(i.inherit()), sep, nest(cat(sepBy(sep, ids), nosep, leaf(i.semi())))));
                }
                yield group(cat(
                        leaf(i.inherit()),
                        nest(cat(
                                group(GroupAnn.REGULAR, cat(line, termInst(i.from()))),
                                sep,
                                sepBy(sep, ids),
                                nosep,
                                leaf(i.semi())))));
            }
            case Assignment a -> {
                List<E> rhs = a.sels().size() <= 4 && a.sels().stream().allMatch(Pretty::isSimpleSelector)
                        ? absorbRHS(a.expr())
                        : cat(line_, group(GroupAnn.PRIORITY, absorbRHS(a.expr())));
                yield group(cat(selectors(a.sels()), nest(cat(hardspace, leaf(a.assign()), rhs)), leaf(a.semi())));
            }
        };
    }

    private static List<E> krec(Ann<Tok> krec) {
        return krec == null ? EMPTY : cat(leaf(krec), hardspace);
    }

    private static List<E> prettySet(boolean wide, SetTerm s) {
        Ann<Tok> open = s.open(), close = s.close();
        if (open.lone() && s.items().isEmpty() && close.pre().isEmpty()) {
            List<E> sep = open.line() != close.line() ? hardline : hardspace;
            return cat(krec(s.krec()), leaf(open), sep, leaf(close));
        }
        List<E> sep = wide && !s.items().isEmpty() || open.line() != close.line() ? hardline : line;
        List<E> surrounded = surroundWith(
                sep, nest(cat(trailComment(open.trail()), renderItems(hardline, s.items(), Pretty::binder))));
        return cat(krec(s.krec()), leaf(open.withTrail(null)), surrounded, leaf(close));
    }

    // TERMS

    private static List<E> prettyTermWide(Term t) {
        return t instanceof SetTerm s ? prettySet(true, s) : prettyTerm(t);
    }

    /** A term without the group that {@link #termInst} puts around lists. */
    private static List<E> prettyTerm(Term t) {
        return switch (t) {
            case Token k -> leaf(k.leaf());
            case SimpleString s -> ann(s.str(), prettySimpleString(s.str().value()));
            case IndentedString s ->
                ann(s.str(), prettyIndentedString(s.cut(), s.str().value()));
            case PathTerm p -> ann(p.path(), stringParts(p.path().value()));
            case Selection s -> {
                List<E> sep;
                if (s.term() instanceof Token k) {
                    TokKind kind = k.leaf().value().kind();
                    sep = kind == TokKind.INTEGER || kind == TokKind.FLOAT ? hardspace : EMPTY;
                } else if (s.term() instanceof Parenthesized) {
                    sep = softline_;
                } else {
                    sep = line_;
                }
                List<E> rest = s.orKw() == null
                        ? EMPTY
                        : cat(softline, nest(cat(leaf(s.orKw()), hardspace, termInst(s.def()))));
                yield cat(termInst(s.term()), sep, selectors(s.sels()), rest);
            }
            case ListTerm l -> {
                if (l.open().trail() == null
                        && l.items().isEmpty()
                        && l.close().pre().isEmpty()) {
                    List<E> sep = l.open().line() != l.close().line() ? hardline : hardspace;
                    yield cat(leaf(l.open()), sep, leaf(l.close()));
                }
                yield renderList(hardline, l.open(), l.items(), l.close());
            }
            case SetTerm s -> prettySet(false, s);
            case Parenthesized p -> {
                Expression expr = p.expr();
                List<E> inner = parenInner(expr);
                yield group(cat(
                        leaf(moveTrailingCommentUp(p.open())),
                        nest(cat(inner, trivia(p.close().pre()))),
                        leaf(p.close().withPre(List.of()))));
            }
        };
    }

    private static List<E> parenInner(Expression expr) {
        if (isAbsorbableExpr(expr)) return group(absorbExpr(false, expr));
        if (expr instanceof Application a) return prettyApp(true, EMPTY, true, a.fn(), a.arg());
        if (expr instanceof Selection s) {
            return isAbsorbable(s.term()) ? cat(line_, group(pretty(expr)), line_) : cat(group(pretty(expr)), line_);
        }
        return cat(line_, group(pretty(expr)), line_);
    }

    private static List<E> termInst(Term t) {
        return t instanceof ListTerm ? group(prettyTerm(t)) : prettyTerm(t);
    }

    // PARAMETERS

    private static List<E> paramAttr(ParamAttr p) {
        return switch (p) {
            case Attr a when a.qmark() == null -> cat(leaf(a.name()), leaf(a.comma()));
            case Attr a ->
                group(cat(leaf(a.name()), hardspace, nest(cat(leaf(a.qmark()), absorbRHS(a.def()))), leaf(a.comma())));
            case Ellipsis e -> leaf(e.dots());
        };
    }

    private static ParamAttr moveParamAttrComment(ParamAttr p) {
        if (p instanceof Attr a && a.comma() != null && a.comma().lone()) {
            if (a.qmark() == null) {
                if (a.name().trail() == null) return p;
                return new Attr(
                        a.name().withTrail(null),
                        null,
                        null,
                        a.comma().withTrail(a.name().trail()));
            }
            String[] comment = new String[1];
            Expression def = mapLast(
                    new AnnMap() {
                        @Override
                        public <T> Ann<T> apply(Ann<T> x) {
                            comment[0] = x.trail();
                            return x.withTrail(null);
                        }
                    },
                    a.def());
            return new Attr(a.name(), a.qmark(), def, a.comma().withTrail(comment[0]));
        }
        return p;
    }

    private static boolean containsDirective(List<Trivium> ts) {
        return ts.stream().anyMatch(t -> t instanceof Disable || t instanceof DisabledToEof || t instanceof Enable);
    }

    private static List<ParamAttr> moveParamsComments(List<ParamAttr> attrs) {
        List<ParamAttr> xs = new ArrayList<>(attrs);
        List<ParamAttr> out = new ArrayList<>();
        int i = 0;
        while (i < xs.size()) {
            ParamAttr x = xs.get(i);
            ParamAttr y = i + 1 < xs.size() ? xs.get(i + 1) : null;
            if (x instanceof Attr a
                    && a.comma() != null
                    && a.comma().trail() == null
                    && !containsDirective(a.comma().pre())) {
                List<Trivium> moved = a.comma().pre();
                if (y instanceof Attr b) {
                    out.add(new Attr(a.name(), a.qmark(), a.def(), a.comma().withPre(List.of())));
                    xs.set(
                            i + 1,
                            new Attr(b.name().withPre(concat(moved, b.name().pre())), b.qmark(), b.def(), b.comma()));
                    i++;
                    continue;
                }
                if (y instanceof Ellipsis e && i + 2 == xs.size()) {
                    out.add(new Attr(a.name(), a.qmark(), a.def(), a.comma().withPre(List.of())));
                    out.add(new Ellipsis(e.dots().withPre(concat(moved, e.dots().pre()))));
                    return out;
                }
            }
            if (x instanceof Attr a && a.comma() == null && y == null) {
                out.add(new Attr(
                        a.name(),
                        a.qmark(),
                        a.def(),
                        new Ann<>(List.of(), a.name().line(), COMMA, null)));
                return out;
            }
            out.add(x);
            i++;
        }
        return out;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<E> pair(Ann<Tok> a, Ann<Tok> b) {
        return a == null ? EMPTY : cat(leaf(a), leaf(b));
    }

    private static List<E> parameter(Parameter p) {
        return switch (p) {
            case IDParameter i -> leaf(i.name());
            case SetParameter s
            when s.attrs().isEmpty() -> {
                List<E> sep = s.open().line() != s.close().line() ? hardline : hardspace;
                yield cat(
                        pair(s.leftName(), s.leftAt()),
                        group(cat(leaf(moveTrailingCommentUp(s.open())), sep, leaf(s.close()))),
                        pair(s.rightAt(), s.rightName()));
            }
            case SetParameter s -> {
                List<E> sep = canFlattenAttrs(s.open(), s.attrs(), s.close()) ? line : hardline;
                List<ParamAttr> attrs = moveParamsComments(s.attrs()).stream()
                        .map(Pretty::moveParamAttrComment)
                        .toList();
                List<List<E>> docs = new ArrayList<>();
                for (int i = 0; i < attrs.size(); i++) {
                    ParamAttr a = attrs.get(i);
                    if (i == attrs.size() - 1
                            && a instanceof Attr x
                            && x.comma() != null
                            && x.comma().lone()
                            && x.comma().value().equals(COMMA)) {
                        docs.add(cat(paramAttr(new Attr(x.name(), x.qmark(), x.def(), null)), trailing(",")));
                    } else {
                        docs.add(paramAttr(a));
                    }
                }
                yield cat(
                        pair(s.leftName(), s.leftAt()),
                        group(cat(
                                leaf(moveTrailingCommentUp(s.open())),
                                surroundWith(sep, nest(sepBy(sep, docs))),
                                nest(trivia(s.close().pre())),
                                leaf(s.close().withPre(List.of())))),
                        pair(s.rightAt(), s.rightName()));
            }
        };
    }

    // APPLICATIONS

    private static List<E> prettyApp(boolean indentFunction, List<E> pre, boolean hasPost, Expression f, Expression a) {
        List<Trivium>[] hoisted = new List[1];
        Expression fWithoutComment = mapFirst(
                new AnnMap() {
                    @Override
                    public <T> Ann<T> apply(Ann<T> x) {
                        Ann<T> m = moveTrailingCommentUp(x);
                        List<Trivium> annots = new ArrayList<>(), rest = new ArrayList<>();
                        for (Trivium t : m.pre()) (t instanceof LanguageAnnotation ? annots : rest).add(t);
                        hoisted[0] = rest;
                        return m.withPre(annots);
                    }
                },
                f);
        List<Trivium> comment = hoisted[0];
        App app = new App(indentFunction, comment.isEmpty());
        List<E> post = hasPost ? line_ : EMPTY;

        List<E> body;
        if (fWithoutComment instanceof Application fa && fa.arg() instanceof ListTerm && a instanceof ListTerm) {
            body = group(
                    GroupAnn.REGULAR,
                    cat(
                            pre,
                            group(GroupAnn.TRANSPARENT, app.absorbApp(fa.fn())),
                            line,
                            nest(group(absorbInner(fa.arg()))),
                            line,
                            nest(group(absorbInner(a))),
                            post));
        } else {
            List<E> renderedF = cat(pre, group(GroupAnn.TRANSPARENT, app.absorbApp(fWithoutComment)));
            List<E> unexpanded = unexpandSpacing_(renderedF);
            if (isSimple(new Application(f, a)) && unexpanded != null) {
                body = group(GroupAnn.REGULAR, cat(unexpanded, hardspace, absorbLast(a)));
            } else {
                body = group(GroupAnn.REGULAR, cat(renderedF, line, absorbLast(a), post));
            }
        }
        return cat(trivia(comment), body, hasPost && !comment.isEmpty() ? hardline : EMPTY);
    }

    private record App(boolean indentFunction, boolean noComment) {
        List<E> absorbApp(Expression e) {
            if (e instanceof Application ap) {
                if (ap.arg() instanceof Selection) {
                    return cat(
                            group(GroupAnn.TRANSPARENT, absorbApp(ap.fn())),
                            line,
                            nest(group(GroupAnn.REGULAR, absorbInner(ap.arg()))));
                }
                if (ap.fn() instanceof Application inner
                        && inner.arg() instanceof ListTerm l1
                        && ap.arg() instanceof ListTerm l2) {
                    return group(
                            GroupAnn.TRANSPARENT,
                            cat(
                                    group(GroupAnn.TRANSPARENT, absorbApp(inner.fn())),
                                    nest(group(
                                            GroupAnn.REGULAR,
                                            cat(line, group(absorbInner(l1)), line, group(absorbInner(l2)))))));
                }
                return cat(
                        group(GroupAnn.TRANSPARENT, absorbApp(ap.fn())),
                        line,
                        nest(group(GroupAnn.PRIORITY, absorbInner(ap.arg()))));
            }
            if (indentFunction && noComment) return group(GroupAnn.REGULAR, cat(line_, pretty(e)));
            return pretty(e);
        }
    }

    private static List<E> absorbInner(Expression e) {
        if (e instanceof ListTerm l
                && l.items().size() <= 6
                && l.items().stream().allMatch(i -> !(i instanceof Of<Term> o) || isSimple(o.value()))) {
            return renderList(line, l.open(), l.items(), l.close());
        }
        return pretty(e);
    }

    private static List<E> absorbLast(Expression arg) {
        if (arg instanceof Term t && isAbsorbable(t)) return group(GroupAnn.PRIORITY, nest(prettyTerm(t)));
        if (arg instanceof Parenthesized p) {
            if (p.expr() instanceof Abstraction ab
                    && ab.param() instanceof IDParameter id
                    && ab.body() instanceof Term body
                    && isAbsorbable(body)
                    && !p.open().hasTrivia()
                    && !id.name().hasTrivia()
                    && !ab.colon().hasTrivia()) {
                return group(
                        GroupAnn.PRIORITY,
                        nest(cat(
                                leaf(p.open()),
                                leaf(id.name()),
                                leaf(ab.colon()),
                                hardspace,
                                prettyTermWide(body),
                                leaf(p.close()))));
            }
            if (p.expr() instanceof Application ap
                    && ap.fn() instanceof Token ident
                    && ident.leaf().value().kind() == TokKind.IDENTIFIER
                    && ap.arg() instanceof Term body
                    && isAbsorbable(body)
                    && !p.open().hasTrivia()
                    && !ident.leaf().hasTrivia()
                    && !p.close().hasTrivia()) {
                return group(
                        GroupAnn.PRIORITY,
                        nest(cat(
                                leaf(p.open()),
                                token(ident.leaf().value()),
                                hardspace,
                                prettyTermWide(body),
                                leaf(p.close()))));
            }
            return absorbParen(p.open(), p.expr(), p.close());
        }
        return group(GroupAnn.REGULAR, nest(pretty(arg)));
    }

    // OPERATIONS

    private record Operand(Ann<Tok> op, Expression expr) {}

    private static void flatten(Ann<Tok> op, Ann<Tok> opL, Expression e, List<Operand> out) {
        if (e instanceof Operation o && o.op().sameValue(op)) {
            flatten(op, opL, o.left(), out);
            flatten(op, o.op(), o.right(), out);
        } else {
            out.add(new Operand(opL, e));
        }
    }

    private static List<E> prettyOp(boolean forceFirstTermWide, Expression operation, Ann<Tok> op) {
        List<Operand> operands = new ArrayList<>();
        flatten(op, null, operation, operands);
        String o = op.value().text();
        boolean pipe = o.equals("|>") || o.equals("<|");
        List<E> sep = pipe && operands.size() > 3 ? hardline : line;
        List<E> out = new ArrayList<>();
        for (Operand x : operands) {
            if (x.op() == null) {
                if (x.expr() instanceof Term t && isAbsorbable(t) && forceFirstTermWide) out.addAll(prettyTermWide(t));
                else out.addAll(pretty(x.expr()));
            } else {
                out.addAll(cat(sep, leaf(moveTrailingCommentUp(x.op())), nest(absorbOperation(x.expr()))));
            }
        }
        return group(GroupAnn.REGULAR, out);
    }

    private static List<E> absorbOperation(Expression x) {
        if (x instanceof Term t && isAbsorbable(t)) return cat(hardspace, termInst(t));
        if (x instanceof Operation) return group(GroupAnn.REGULAR, cat(line, pretty(x)));
        if (x instanceof Application a) return group(cat(hardspace, prettyApp(false, line, false, a.fn(), a.arg())));
        return cat(hardspace, pretty(x));
    }

    private static List<E> prettyWith(boolean absorb, With w) {
        if (absorb && w.body() instanceof Term body) {
            return group(
                    GroupAnn.REGULAR,
                    cat(
                            line_,
                            leaf(w.with()),
                            hardspace,
                            nest(group(pretty(w.scope()))),
                            leaf(w.semi()),
                            hardspace,
                            group(GroupAnn.PRIORITY, prettyTermWide(body))));
        }
        return cat(
                group(cat(leaf(w.with()), hardspace, nest(group(pretty(w.scope()))), leaf(w.semi()))),
                line,
                pretty(w.body()));
    }

    // ABSORPTION

    private static boolean canFlattenAttrs(Ann<Tok> open, List<ParamAttr> attrs, Ann<Tok> close) {
        if (open.line() != close.line()) return false;
        if (close.hasTrivia()) return false;
        for (ParamAttr p : attrs) {
            boolean trivia = switch (p) {
                case Attr a ->
                    a.name().hasTrivia() || a.comma() != null && a.comma().hasTrivia();
                case Ellipsis e -> e.dots().hasTrivia();
            };
            if (trivia) return false;
        }
        int n = attrs.size();
        boolean ellipsis = n > 0 && attrs.getLast() instanceof Ellipsis;
        int plain = ellipsis ? n - 1 : n;
        if (plain > 2) return false;
        for (int i = 0; i < plain; i++) {
            if (!(attrs.get(i) instanceof Attr a) || a.qmark() != null) return false;
        }
        return true;
    }

    private static boolean isAbsorbableExpr(Expression e) {
        return switch (e) {
            case Term t -> isAbsorbable(t);
            case With w -> w.body() instanceof Term t && isAbsorbable(t);
            case Abstraction a
            when a.param() instanceof IDParameter -> {
                if (a.body() instanceof Term t) yield isAbsorbable(t);
                if (a.body() instanceof Abstraction) yield isAbsorbableExpr(a.body());
                yield false;
            }
            case Abstraction a
            when a.param() instanceof SetParameter s
                    && s.leftName() == null
                    && s.rightAt() == null
                    && canFlattenAttrs(s.open(), s.attrs(), s.close())
                    && s.open().pre().isEmpty()
                    && s.open().trail() == null
                    && a.colon().line() == firstTokenLine(a.body()) -> isAbsorbableExpr(a.body());
            default -> false;
        };
    }

    private static boolean isAbsorbable(Term t) {
        return switch (t) {
            case IndentedString s -> s.str().value().size() >= 2;
            case PathTerm p -> false;
            case SetTerm s ->
                !s.items().isEmpty()
                        || s.open().line() != s.close().line()
                        || s.open().hasTrivia();
            case ListTerm l ->
                !l.items().isEmpty()
                        || l.open().line() != l.close().line()
                        || l.open().hasTrivia();
            case Parenthesized p
            when p.open().lone() && p.expr() instanceof Term inner ->
                firstToken(inner).pre().stream().allMatch(x -> x instanceof LanguageAnnotation) && isAbsorbable(inner);
            default -> false;
        };
    }

    private static List<E> absorbParen(Ann<Tok> open, Expression expr, Ann<Tok> close) {
        List<Trivium> moved = open.trail() == null ? List.of() : List.of(toLineComment(open.trail()));
        return group(
                GroupAnn.PRIORITY,
                nest(cat(
                        leaf(open.withTrail(null)),
                        surroundWith(
                                line_,
                                group(
                                        GroupAnn.REGULAR,
                                        nest(cat(pretty(mapFirst(prependTrivia(moved), expr)), trivia(close.pre()))))),
                        leaf(close.withPre(List.of())))));
    }

    private static List<E> absorbExpr(boolean wide, Expression e) {
        if (e instanceof Term t && isAbsorbable(t)) return wide ? prettyTermWide(t) : prettyTerm(t);
        if (e instanceof With w && w.body() instanceof Term t && isAbsorbable(t)) return prettyWith(true, w);
        return pretty(e);
    }

    private static boolean isUpdateConcatPlus(Tok t) {
        return t.text().equals("//") || t.text().equals("++") || t.text().equals("+");
    }

    private static List<E> absorbRHS(Expression e) {
        if (e instanceof SetTerm s
                && s.items().size() == 1
                && s.items().getFirst() instanceof Of<Binder> o
                && o.value() instanceof Inherit) {
            return nest(cat(hardspace, group(absorbExpr(false, e))));
        }
        if (isAbsorbableExpr(e)) return nest(cat(hardspace, group(absorbExpr(true, e))));
        if (e instanceof Parenthesized p) return nest(cat(hardspace, absorbParen(p.open(), p.expr(), p.close())));
        if (e instanceof SimpleString || e instanceof IndentedString || e instanceof PathTerm) {
            return nest(cat(hardspace, group(pretty(e))));
        }
        if (e instanceof Term) return nest(group(GroupAnn.REGULAR, cat(line, pretty(e))));
        if (e instanceof Application a) return nest(prettyApp(false, line, false, a.fn(), a.arg()));
        if (e instanceof With) return nest(group(GroupAnn.REGULAR, cat(line, pretty(e))));
        if (e instanceof Operation o
                && o.left() instanceof Term t
                && isAbsorbable(t)
                && firstToken(t).pre().isEmpty()
                && isUpdateConcatPlus(o.op().value())) {
            return cat(hardspace, prettyOp(true, e, o.op()));
        }
        if (e instanceof Operation o
                && o.op().lone()
                && o.right() instanceof Term t
                && isAbsorbable(t)
                && isUpdateConcatPlus(o.op().value())) {
            return nest(group(
                    GroupAnn.REGULAR,
                    cat(
                            line,
                            pretty(o.left()),
                            line,
                            group(
                                    GroupAnn.TRANSPARENT,
                                    cat(
                                            token(o.op().value()),
                                            hardspace,
                                            group(GroupAnn.PRIORITY, prettyTermWide(t)))))));
        }
        return nest(cat(line, group(pretty(e))));
    }

    // EXPRESSIONS

    static List<E> pretty(Expression e) {
        return switch (e) {
            case Term t -> termInst(t);
            case With w -> prettyWith(false, w);
            case Let l -> {
                Ann<Tok> in = l.in();
                List<Trivium> moved = new ArrayList<>(in.pre());
                if (in.trail() != null) moved.add(toLineComment(in.trail()));
                Expression body = mapFirst(prependTrivia(moved), l.body());
                List<E> letPart =
                        group(cat(leaf(l.let()), hardline, nest(renderItems(hardline, l.binders(), Pretty::binder))));
                List<E> inPart = group(cat(token(in.value()), hardline, pretty(body)));
                yield cat(letPart, hardline, inPart);
            }
            case Assert a -> {
                Expression f = new Token(a.assert_());
                Expression arg = a.cond();
                Expression[] split = insertIntoApp(f, arg);
                yield group(cat(
                        prettyApp(false, EMPTY, false, split[0], split[1]),
                        leaf(a.semi()),
                        hardline,
                        pretty(a.body())));
            }
            case If i -> group(GroupAnn.REGULAR, prettyIf(line, mapFirst(MOVE_TRAILING_COMMENT_UP, i)));
            case Abstraction a
            when a.param() instanceof IDParameter id ->
                group(GroupAnn.REGULAR, cat(line_, leaf(id.name()), leaf(a.colon()), absorbAbs(1, a.body())));
            case Abstraction a -> abstraction(a);
            case Application a -> prettyApp(false, EMPTY, false, a.fn(), a.arg());
            case Operation o
            when COMPARISONS.contains(o.op().value().text()) ->
                cat(pretty(o.left()), softline, leaf(o.op()), hardspace, pretty(o.right()));
            case Operation o -> prettyOp(false, o, o.op());
            case MemberCheck m -> {
                List<E> checks = new ArrayList<>();
                for (Check c : m.checks()) {
                    checks.addAll(cat(line, leaf(moveTrailingCommentUp(c.qmark())), hardspace, selectors(c.sels())));
                }
                yield group(cat(pretty(m.expr()), nest(checks)));
            }
            case Negation n -> cat(leaf(n.minus()), pretty(n.expr()));
            case Inversion n -> cat(leaf(n.bang()), pretty(n.expr()));
        };
    }

    private static List<E> abstraction(Abstraction a) {
        if (a.param() instanceof SetParameter s && s.leftName() == null && s.rightAt() == null) {
            int bodyLine = firstTokenLine(a.body());
            if (canFlattenAttrs(s.open(), s.attrs(), s.close())
                    && a.colon().line() == bodyLine
                    && isAbsorbableExpr(a.body())) {
                return cat(parameter(s), leaf(a.colon()), hardspace, group(GroupAnn.PRIORITY, pretty(a.body())));
            }
            if (a.colon().line() != bodyLine) {
                return cat(parameter(s), leaf(a.colon()), hardline, pretty(a.body()));
            }
        }
        if (a.body() instanceof Term t && isAbsorbable(t)) {
            return cat(parameter(a.param()), leaf(a.colon()), line, group(prettyTermWide(t)));
        }
        return cat(parameter(a.param()), leaf(a.colon()), line, pretty(a.body()));
    }

    private static List<E> absorbAbs(int depth, Expression e) {
        if (e instanceof Abstraction a && a.param() instanceof IDParameter id) {
            return cat(hardspace, leaf(id.name()), leaf(a.colon()), absorbAbs(depth + 1, a.body()));
        }
        if (isAbsorbableExpr(e)) return cat(hardspace, group(GroupAnn.PRIORITY, absorbExpr(false, e)));
        return cat(depth <= 2 ? line : hardline, pretty(e));
    }

    private static Expression[] insertIntoApp(Expression insert, Expression e) {
        if (e instanceof Application a) {
            Expression[] inner = insertIntoApp(insert, a.fn());
            return new Expression[] {new Application(inner[0], inner[1]), a.arg()};
        }
        return new Expression[] {insert, e};
    }

    private static List<E> prettyIf(List<E> sep, Expression e) {
        if (e instanceof If i) {
            return cat(
                    group(cat(leaf(i.if_()), line, nest(pretty(i.cond())), line, leaf(i.then_()))),
                    surroundWith(sep, nest(group(pretty(i.then())))),
                    leaf(moveTrailingCommentUp(i.else_())),
                    hardspace,
                    prettyIf(hardline, i.otherwise()));
        }
        return cat(line, nest(group(pretty(e))));
    }

    static List<E> whole(Whole w) {
        return group(cat(pretty(w.expr()), trivia(w.trivia())));
    }

    private static boolean isSimpleSelector(Selector s) {
        return s.sel() instanceof IDSelector;
    }

    private static boolean isSimple(Expression e) {
        return switch (e) {
            case SimpleString s -> s.str().lone();
            case IndentedString s -> s.str().lone();
            case PathTerm p -> p.path().lone();
            case Token t -> t.leaf().lone() && t.leaf().value().kind() != TokKind.SYMBOL;
            case Selection s ->
                isSimple(s.term()) && s.sels().stream().allMatch(Pretty::isSimpleSelector) && s.orKw() == null;
            case Parenthesized p -> p.open().lone() && p.close().lone() && isSimple(p.expr());
            case Application a -> {
                if (a.fn() instanceof Application b && b.fn() instanceof Application) yield false;
                yield isSimple(a.fn()) && isSimple(a.arg());
            }
            default -> false;
        };
    }

    // STRINGS

    private static List<E> stringPart(StringPart p) {
        return switch (p) {
            case TextPart t -> text(t.text());
            case Interpolation i
            when i.w().trivia().isEmpty() && i.w().expr() instanceof Term t && isAbsorbable(t) ->
                group(cat(text("${"), prettyTerm(t), text("}")));
            case Interpolation i
            when i.w().trivia().isEmpty() && isSimple(i.w().expr()) -> {
                List<E> d = pretty(i.w().expr());
                List<E> u = unexpandSpacing_(d);
                yield cat(text("${"), u != null ? u : d, text("}"));
            }
            case Interpolation i -> {
                List<E> whole = whole(i.w());
                List<E> u = unexpandSpacing_(30, whole);
                yield group(cat(text("${"), u != null ? u : surroundWith(line_, nest(whole)), text("}")));
            }
        };
    }

    private static List<E> stringParts(List<StringPart> parts) {
        if (parts.size() == 1
                && parts.getFirst() instanceof Interpolation i
                && i.w().trivia().isEmpty()) {
            return stringParts(List.of(new TextPart(""), i));
        }
        if (parts.size() == 2
                && parts.get(0) instanceof TextPart(String pre)
                && parts.get(1) instanceof Interpolation i
                && i.w().trivia().isEmpty()
                && Convert.isSpaces(pre)) {
            Expression expr = i.w().expr();
            return cat(text(pre), offset(textWidth(pre), group(cat(text("${"), nest(parenInner(expr)), text("}")))));
        }
        if (parts.size() == 1 && parts.getFirst() instanceof Interpolation i) {
            return group(cat(text("${"), surroundWith(line_, nest(whole(i.w()))), text("}")));
        }
        if (!parts.isEmpty() && parts.getFirst() instanceof TextPart(String t)) {
            int k = 0;
            while (k < t.length() && Convert.isSpace(t.charAt(k))) k++;
            List<E> rest = new ArrayList<>();
            for (StringPart p : parts.subList(1, parts.size())) rest.addAll(stringPart(p));
            return cat(text(t), offset(textWidth(t.substring(0, k)), rest));
        }
        List<E> out = new ArrayList<>();
        for (StringPart p : parts) out.addAll(stringPart(p));
        return out;
    }

    private static List<E> prettySimpleString(List<List<StringPart>> lines) {
        return group(cat(
                text("\""),
                sepBy(text("\n"), lines.stream().map(Pretty::stringParts).toList()),
                text("\"")));
    }

    private static List<E> prettyIndentedString(String cut, List<List<StringPart>> lines) {
        List<List<E>> docs = new ArrayList<>();
        for (List<StringPart> l : lines) docs.add(cut == null ? stringParts(l) : rawLine(cut, l));
        return group(cat(text("''"), lines.size() >= 2 ? line_ : EMPTY, nest(sepBy(newline, docs)), text("''")));
    }

    private static List<E> rawLine(String ind, List<StringPart> parts) {
        if (parts.isEmpty()) return EMPTY;
        if (parts.getFirst() instanceof TextPart(String t)) {
            int k = 0;
            while (k < t.length() && Convert.isSpace(t.charAt(k))) k++;
            String ws = t.substring(0, k);
            List<StringPart> rest = new ArrayList<>();
            rest.add(new TextPart(t.substring(k)));
            rest.addAll(parts.subList(1, parts.size()));
            return cat(rawText(ind + ws), offset(textWidth(ws), stringParts(rest)));
        }
        return cat(rawText(ind), stringParts(parts));
    }
}
