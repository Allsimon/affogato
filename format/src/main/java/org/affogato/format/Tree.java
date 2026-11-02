package org.affogato.format;

import java.util.ArrayList;
import java.util.List;

/** Port of nixfmt's Nixfmt.Types: the syntax tree with comments attached to tokens. */
final class Tree {
    private Tree() {}

    sealed interface Trivium {}

    record EmptyLine() implements Trivium {}

    record LineComment(String text) implements Trivium {}

    record BlockComment(boolean doc, List<String> lines) implements Trivium {}

    record LanguageAnnotation(String lang) implements Trivium {}

    /** {@code /*nixfmt:disable*}{@code /}. The raw region text is carried by the directive that closes it. */
    record Disable() implements Trivium {}

    /** The virtual close of a region left open, which extends to the end of the file. */
    record DisabledToEof(String raw) implements Trivium {}

    /** {@code raw} is the text of the region it closes, or null for a lone enable. */
    record Enable(String raw) implements Trivium {}

    static final EmptyLine EMPTY_LINE = new EmptyLine();

    enum TokKind {
        INTEGER,
        FLOAT,
        IDENTIFIER,
        ENV_PATH,
        SYMBOL
    }

    /** A token; {@code text} is what gets printed. */
    record Tok(TokKind kind, String text) {}

    /** An annotated value: leading trivia, source line, and the trailing comment on the same line or null. */
    record Ann<T>(List<Trivium> pre, int line, T value, String trail) {
        Ann<T> withPre(List<Trivium> p) {
            return new Ann<>(p, line, value, trail);
        }

        Ann<T> withTrail(String t) {
            return new Ann<>(pre, line, value, t);
        }

        boolean hasTrivia() {
            return !pre.isEmpty() || trail != null;
        }

        boolean lone() {
            return !hasTrivia();
        }

        /** Equality of the value alone, as nixfmt compares annotated syntax. */
        boolean sameValue(Ann<?> o) {
            return value.equals(o.value);
        }
    }

    sealed interface StringPart {}

    record TextPart(String text) implements StringPart {}

    record Interpolation(Whole w) implements StringPart {}

    /** An expression with the trivia that follows it. */
    record Whole(Expression expr, List<Trivium> trivia) {}

    sealed interface SimpleSelector {}

    record IDSelector(Ann<Tok> name) implements SimpleSelector {}

    record InterpolSelector(Ann<StringPart> interp) implements SimpleSelector {}

    record StringSelector(Ann<List<List<StringPart>>> str) implements SimpleSelector {}

    record Selector(Ann<Tok> dot, SimpleSelector sel) {}

    sealed interface Binder {}

    record Inherit(Ann<Tok> inherit, Term from, List<SimpleSelector> sels, Ann<Tok> semi) implements Binder {}

    record Assignment(List<Selector> sels, Ann<Tok> assign, Expression expr, Ann<Tok> semi) implements Binder {}

    sealed interface Item<T> {}

    record Of<T>(T value) implements Item<T> {}

    record Comments<T>(List<Trivium> trivia) implements Item<T> {}

    sealed interface Expression {}

    sealed interface Term extends Expression {}

    record Token(Ann<Tok> leaf) implements Term {}

    record SimpleString(Ann<List<List<StringPart>>> str) implements Term {}

    /** {@code cut} is the stripped indentation when a disabled region cuts through the string, or null. */
    record IndentedString(String cut, Ann<List<List<StringPart>>> str) implements Term {}

    record PathTerm(Ann<List<StringPart>> path) implements Term {}

    record ListTerm(Ann<Tok> open, List<Item<Term>> items, Ann<Tok> close) implements Term {}

    record SetTerm(Ann<Tok> krec, Ann<Tok> open, List<Item<Binder>> items, Ann<Tok> close) implements Term {}

    record Selection(Term term, List<Selector> sels, Ann<Tok> orKw, Term def) implements Term {}

    record Parenthesized(Ann<Tok> open, Expression expr, Ann<Tok> close) implements Term {}

    sealed interface ParamAttr {}

    record Attr(Ann<Tok> name, Ann<Tok> qmark, Expression def, Ann<Tok> comma) implements ParamAttr {}

    record Ellipsis(Ann<Tok> dots) implements ParamAttr {}

    sealed interface Parameter {}

    record IDParameter(Ann<Tok> name) implements Parameter {}

    /** {@code { ... }}, {@code { ... }@x} or {@code x@{ ... }}; absent context tokens are null. */
    record SetParameter(
            Ann<Tok> leftName,
            Ann<Tok> leftAt,
            Ann<Tok> open,
            List<ParamAttr> attrs,
            Ann<Tok> close,
            Ann<Tok> rightAt,
            Ann<Tok> rightName)
            implements Parameter {}

    record With(Ann<Tok> with, Expression scope, Ann<Tok> semi, Expression body) implements Expression {}

    record Let(Ann<Tok> let, List<Item<Binder>> binders, Ann<Tok> in, Expression body) implements Expression {}

    record Assert(Ann<Tok> assert_, Expression cond, Ann<Tok> semi, Expression body) implements Expression {}

    record If(Ann<Tok> if_, Expression cond, Ann<Tok> then_, Expression then, Ann<Tok> else_, Expression otherwise)
            implements Expression {}

    record Abstraction(Parameter param, Ann<Tok> colon, Expression body) implements Expression {}

    record Application(Expression fn, Expression arg) implements Expression {}

    record Operation(Expression left, Ann<Tok> op, Expression right) implements Expression {}

    record Check(Ann<Tok> qmark, List<Selector> sels) {}

    record MemberCheck(Expression expr, List<Check> checks) implements Expression {}

    record Negation(Ann<Tok> minus, Expression expr) implements Expression {}

    record Inversion(Ann<Tok> bang, Expression expr) implements Expression {}

    /** A function over annotated values of any type, applied to the first or last token. */
    interface AnnMap {
        <T> Ann<T> apply(Ann<T> a);
    }

    static Expression mapFirst(AnnMap f, Expression e) {
        return switch (e) {
            case Term t -> mapFirst(f, t);
            case With w -> new With(f.apply(w.with()), w.scope(), w.semi(), w.body());
            case Let l -> new Let(f.apply(l.let()), l.binders(), l.in(), l.body());
            case Assert a -> new Assert(f.apply(a.assert_()), a.cond(), a.semi(), a.body());
            case If i -> new If(f.apply(i.if_()), i.cond(), i.then_(), i.then(), i.else_(), i.otherwise());
            case Abstraction a -> new Abstraction(mapFirst(f, a.param()), a.colon(), a.body());
            case Application a -> new Application(mapFirst(f, a.fn()), a.arg());
            case Operation o -> new Operation(mapFirst(f, o.left()), o.op(), o.right());
            case MemberCheck m -> new MemberCheck(mapFirst(f, m.expr()), m.checks());
            case Negation n -> new Negation(f.apply(n.minus()), n.expr());
            case Inversion n -> new Inversion(f.apply(n.bang()), n.expr());
        };
    }

    static Term mapFirst(AnnMap f, Term t) {
        return switch (t) {
            case Token k -> new Token(f.apply(k.leaf()));
            case SimpleString s -> new SimpleString(f.apply(s.str()));
            case IndentedString s -> new IndentedString(s.cut(), f.apply(s.str()));
            case PathTerm p -> new PathTerm(f.apply(p.path()));
            case ListTerm l -> new ListTerm(f.apply(l.open()), l.items(), l.close());
            case SetTerm s ->
                s.krec() != null
                        ? new SetTerm(f.apply(s.krec()), s.open(), s.items(), s.close())
                        : new SetTerm(null, f.apply(s.open()), s.items(), s.close());
            case Selection s -> new Selection(mapFirst(f, s.term()), s.sels(), s.orKw(), s.def());
            case Parenthesized p -> new Parenthesized(f.apply(p.open()), p.expr(), p.close());
        };
    }

    static Parameter mapFirst(AnnMap f, Parameter p) {
        return switch (p) {
            case IDParameter i -> new IDParameter(f.apply(i.name()));
            case SetParameter s ->
                s.leftName() != null
                        ? new SetParameter(
                                f.apply(s.leftName()),
                                s.leftAt(),
                                s.open(),
                                s.attrs(),
                                s.close(),
                                s.rightAt(),
                                s.rightName())
                        : new SetParameter(
                                null, null, f.apply(s.open()), s.attrs(), s.close(), s.rightAt(), s.rightName());
        };
    }

    /** The first token of an expression. */
    static Ann<?> firstToken(Expression e) {
        Ann<?>[] seen = new Ann<?>[1];
        mapFirst(
                new AnnMap() {
                    @Override
                    public <T> Ann<T> apply(Ann<T> a) {
                        seen[0] = a;
                        return a;
                    }
                },
                e);
        return seen[0];
    }

    static Ann<?> firstToken(Term t) {
        return firstToken((Expression) t);
    }

    static Expression mapLast(AnnMap f, Expression e) {
        return switch (e) {
            case Term t -> mapLast(f, t);
            case With w -> new With(w.with(), w.scope(), w.semi(), mapLast(f, w.body()));
            case Let l -> new Let(l.let(), l.binders(), l.in(), mapLast(f, l.body()));
            case Assert a -> new Assert(a.assert_(), a.cond(), a.semi(), mapLast(f, a.body()));
            case If i -> new If(i.if_(), i.cond(), i.then_(), i.then(), i.else_(), mapLast(f, i.otherwise()));
            case Abstraction a -> new Abstraction(a.param(), a.colon(), mapLast(f, a.body()));
            case Application a -> new Application(a.fn(), mapLast(f, a.arg()));
            case Operation o -> new Operation(o.left(), o.op(), mapLast(f, o.right()));
            case MemberCheck m -> {
                List<Check> checks = new ArrayList<>(m.checks());
                Check last = checks.getLast();
                checks.set(
                        checks.size() - 1,
                        last.sels().isEmpty()
                                ? new Check(f.apply(last.qmark()), last.sels())
                                : new Check(last.qmark(), mapLast(f, last.sels())));
                yield new MemberCheck(m.expr(), checks);
            }
            case Negation n -> new Negation(n.minus(), mapLast(f, n.expr()));
            case Inversion n -> new Inversion(n.bang(), mapLast(f, n.expr()));
        };
    }

    static Term mapLast(AnnMap f, Term t) {
        return switch (t) {
            case Token k -> new Token(f.apply(k.leaf()));
            case SimpleString s -> new SimpleString(f.apply(s.str()));
            case IndentedString s -> new IndentedString(s.cut(), f.apply(s.str()));
            case PathTerm p -> new PathTerm(f.apply(p.path()));
            case ListTerm l -> new ListTerm(l.open(), l.items(), f.apply(l.close()));
            case SetTerm s -> new SetTerm(s.krec(), s.open(), s.items(), f.apply(s.close()));
            case Selection s -> {
                if (s.def() != null) yield new Selection(s.term(), s.sels(), s.orKw(), mapLast(f, s.def()));
                if (!s.sels().isEmpty()) yield new Selection(s.term(), mapLast(f, s.sels()), null, null);
                yield new Selection(mapLast(f, s.term()), s.sels(), null, null);
            }
            case Parenthesized p -> new Parenthesized(p.open(), p.expr(), f.apply(p.close()));
        };
    }

    private static List<Selector> mapLast(AnnMap f, List<Selector> sels) {
        List<Selector> out = new ArrayList<>(sels);
        Selector last = out.getLast();
        SimpleSelector s = switch (last.sel()) {
            case IDSelector i -> new IDSelector(f.apply(i.name()));
            case InterpolSelector i -> new InterpolSelector(f.apply(i.interp()));
            case StringSelector i -> new StringSelector(f.apply(i.str()));
        };
        out.set(out.size() - 1, new Selector(last.dot(), s));
        return out;
    }
}
