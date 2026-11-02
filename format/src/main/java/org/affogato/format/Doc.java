package org.affogato.format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Port of nixfmt's Nixfmt.Predoc document model: a document is a list of elements. */
final class Doc {
    private Doc() {}

    sealed interface E permits Text, Spacing, Group {}

    enum Kind {
        REGULAR,
        COMMENT,
        TRAILING_COMMENT,
        TRAILING,
        DISABLE_MARKER,
        VERBATIM,
        VERBATIM_TO_EOF,
        RAW
    }

    record Text(int nest, int offset, Kind kind, String text) implements E {}

    /** Ordered as nixfmt's {@code Spacing}; {@code n} counts the line breaks of NEWLINES. */
    record Spacing(int kind, int n) implements E, Comparable<Spacing> {
        @Override
        public int compareTo(Spacing o) {
            return kind != o.kind ? Integer.compare(kind, o.kind) : Integer.compare(n, o.n);
        }
    }

    enum GroupAnn {
        REGULAR,
        PRIORITY,
        TRANSPARENT
    }

    record Group(GroupAnn ann, List<E> body) implements E {}

    static final int SOFTBREAK = 0, BREAK = 1, HARDSPACE = 2, SOFTSPACE = 3, SPACE = 4, HARDLINE = 5, EMPTYLINE = 6;
    static final int NEWLINES = 7;

    static final Spacing SP_SOFTBREAK = new Spacing(SOFTBREAK, 0);
    static final Spacing SP_BREAK = new Spacing(BREAK, 0);
    static final Spacing SP_HARDSPACE = new Spacing(HARDSPACE, 0);
    static final Spacing SP_SOFTSPACE = new Spacing(SOFTSPACE, 0);
    static final Spacing SP_SPACE = new Spacing(SPACE, 0);
    static final Spacing SP_HARDLINE = new Spacing(HARDLINE, 0);
    static final Spacing SP_EMPTYLINE = new Spacing(EMPTYLINE, 0);

    static final List<E> EMPTY = List.of();
    static final List<E> softline_ = List.of(SP_SOFTBREAK);
    static final List<E> line_ = List.of(SP_BREAK);
    static final List<E> softline = List.of(SP_SOFTSPACE);
    static final List<E> line = List.of(SP_SPACE);
    static final List<E> hardspace = List.of(SP_HARDSPACE);
    static final List<E> hardline = List.of(SP_HARDLINE);
    static final List<E> emptyline = List.of(SP_EMPTYLINE);
    static final List<E> newline = List.of(new Spacing(NEWLINES, 1));

    static List<E> text(String t) {
        return t.isEmpty() ? EMPTY : List.of(new Text(0, 0, Kind.REGULAR, t));
    }

    static List<E> comment(String t) {
        return t.isEmpty() ? EMPTY : List.of(new Text(0, 0, Kind.COMMENT, t));
    }

    static List<E> trailingComment(String t) {
        return t.isEmpty() ? EMPTY : List.of(new Text(0, 0, Kind.TRAILING_COMMENT, t));
    }

    static List<E> trailing(String t) {
        return t.isEmpty() ? EMPTY : List.of(new Text(0, 0, Kind.TRAILING, t));
    }

    static final List<E> disableMarker = List.of(new Text(0, 0, Kind.DISABLE_MARKER, ""));

    static List<E> verbatim(String t) {
        return List.of(new Text(0, 0, Kind.VERBATIM, t));
    }

    static List<E> verbatimToEof(String t) {
        return List.of(new Text(0, 0, Kind.VERBATIM_TO_EOF, t));
    }

    static List<E> rawText(String t) {
        return List.of(new Text(0, 0, Kind.RAW, t));
    }

    @SafeVarargs
    static List<E> cat(List<E>... parts) {
        List<E> out = new ArrayList<>();
        for (List<E> p : parts) out.addAll(p);
        return out;
    }

    static List<E> group(List<E> d) {
        return List.of(new Group(GroupAnn.REGULAR, d));
    }

    static List<E> group(GroupAnn ann, List<E> d) {
        return List.of(new Group(ann, d));
    }

    static List<E> nest(List<E> d) {
        List<E> out = new ArrayList<>(d.size());
        for (E e : d) {
            out.add(
                    switch (e) {
                        case Text t -> new Text(t.nest() + 1, t.offset(), t.kind(), t.text());
                        case Group g -> new Group(g.ann(), nest(g.body()));
                        case Spacing s -> s;
                    });
        }
        return out;
    }

    static List<E> offset(int level, List<E> d) {
        List<E> out = new ArrayList<>(d.size());
        for (E e : d) {
            out.add(
                    switch (e) {
                        case Text t -> new Text(t.nest(), t.offset() + level, t.kind(), t.text());
                        case Group g -> new Group(g.ann(), offset(level, g.body()));
                        case Spacing s -> s;
                    });
        }
        return out;
    }

    static List<E> surroundWith(List<E> outside, List<E> inner) {
        return cat(outside, inner, outside);
    }

    static List<E> sepBy(List<E> separator, List<List<E>> docs) {
        List<E> out = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) out.addAll(separator);
            out.addAll(docs.get(i));
        }
        return out;
    }

    static int textWidth(String t) {
        return t.codePointCount(0, t.length());
    }

    static boolean isSoft(E e) {
        return e instanceof Spacing s && s.kind() <= SPACE && s.kind() != HARDSPACE;
    }

    static boolean isHard(E e) {
        return e instanceof Spacing s && !isSoft(s);
    }

    static boolean isComment(E e) {
        return switch (e) {
            case Text t -> t.kind() == Kind.COMMENT || t.kind() == Kind.TRAILING_COMMENT;
            case Group g -> g.body().stream().allMatch(x -> isComment(x) || isHard(x));
            case Spacing s -> false;
        };
    }

    static boolean isVerbatim(E e) {
        return e instanceof Text t && (t.kind() == Kind.VERBATIM || t.kind() == Kind.VERBATIM_TO_EOF);
    }

    /** Forces a group's compact layout, without recursing into inner groups. */
    static List<E> unexpandSpacing(List<E> d) {
        List<E> out = new ArrayList<>(d.size());
        for (E e : d) {
            if (e instanceof Spacing s) {
                switch (s.kind()) {
                    case SPACE, SOFTSPACE -> out.add(SP_HARDSPACE);
                    case BREAK, SOFTBREAK -> {}
                    default -> out.add(s);
                }
            } else {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Forces the compact layout recursively, or returns null if there is a hard line break or the result exceeds
     * {@code limit} (negative for no limit).
     */
    static List<E> unexpandSpacing_(int limit, List<E> d) {
        List<E> out = new ArrayList<>();
        return unexpand(limit, d, out) ? out : null;
    }

    private static final int NO_LIMIT = Integer.MIN_VALUE;

    static List<E> unexpandSpacing_(List<E> d) {
        return unexpandSpacing_(NO_LIMIT, d);
    }

    private static boolean unexpand(int[] n, List<E> d, List<E> out) {
        for (E e : d) {
            if (n[0] != NO_LIMIT && n[0] < 0) return false;
            switch (e) {
                case Text t -> {
                    if (t.kind() == Kind.DISABLE_MARKER || isVerbatim(t) || t.kind() == Kind.RAW) return false;
                    out.add(t);
                    if (n[0] != NO_LIMIT) n[0] -= textWidth(t.text());
                }
                case Spacing s -> {
                    switch (s.kind()) {
                        case HARDSPACE, SPACE, SOFTSPACE -> {
                            out.add(SP_HARDSPACE);
                            if (n[0] != NO_LIMIT) n[0] -= 1;
                        }
                        case BREAK, SOFTBREAK -> {}
                        default -> {
                            return false;
                        }
                    }
                }
                case Group g -> {
                    if (!unexpand(n, g.body(), out)) return false;
                }
            }
        }
        return n[0] == NO_LIMIT || n[0] >= 0;
    }

    private static boolean unexpand(int limit, List<E> d, List<E> out) {
        return unexpand(new int[] {limit}, d, out);
    }

    private static List<E> simplifyGroup(GroupAnn ann, List<E> d) {
        if (d.size() == 1 && d.getFirst() instanceof Group g && g.ann() == ann) return g.body();
        return d;
    }

    static Spacing merge(Spacing x, Spacing y) {
        if (x.compareTo(y) > 0) return merge(y, x);
        if (x.kind() == BREAK && y.kind() == SOFTSPACE) return SP_SPACE;
        if (x.kind() == BREAK && y.kind() == HARDSPACE) return SP_SPACE;
        if (x.kind() == SOFTBREAK && y.kind() == HARDSPACE) return SP_SOFTSPACE;
        if (y.kind() == NEWLINES) {
            return switch (x.kind()) {
                case NEWLINES -> new Spacing(NEWLINES, x.n() + y.n());
                case EMPTYLINE -> new Spacing(NEWLINES, y.n() + 2);
                case HARDSPACE -> y;
                default -> new Spacing(NEWLINES, y.n() + 1);
            };
        }
        return y;
    }

    private static boolean isPre(E e) {
        return isHard(e) || isComment(e) || isVerbatim(e);
    }

    /**
     * Moves hard spacing and comments out of the edges of groups, merges consecutive spacings and texts, and removes
     * empty groups.
     */
    static List<E> fixup(List<E> doc) {
        ArrayDeque<E> in = new ArrayDeque<>(doc);
        List<E> out = new ArrayList<>();
        while (!in.isEmpty()) {
            E x = in.poll();
            E y = in.peek();
            if (x instanceof Spacing a && y instanceof Spacing b) {
                in.poll();
                in.push(merge(a, b));
            } else if (x instanceof Text a && y instanceof Text b && a.kind() == b.kind()) {
                in.poll();
                in.push(new Text(a.nest(), a.offset(), a.kind(), a.text() + b.text()));
            } else if (x instanceof Spacing a && a.kind() == HARDSPACE && y instanceof Group g) {
                in.poll();
                List<E> body = new ArrayList<>(g.body().size() + 1);
                body.add(a);
                body.addAll(g.body());
                in.push(new Group(g.ann(), body));
            } else if (x instanceof Spacing || x instanceof Group) {
                Spacing a = null;
                Group g;
                if (x instanceof Spacing s) {
                    if (!(y instanceof Group yg)) {
                        out.add(x);
                        continue;
                    }
                    in.poll();
                    a = s;
                    g = yg;
                } else {
                    g = (Group) x;
                }
                List<E> inner = fixup(g.body());
                int i = 0;
                while (i < inner.size() && isPre(inner.get(i))) i++;
                int j = inner.size();
                while (j > i && isHard(inner.get(j - 1))) j--;
                List<E> pre = new ArrayList<>();
                if (a != null) pre.add(a);
                pre.addAll(inner.subList(0, i));
                List<E> post = inner.subList(j, inner.size());
                List<E> body = simplifyGroup(g.ann(), inner.subList(i, j));
                if (body.isEmpty()) {
                    pushAll(in, cat(pre, post));
                } else {
                    out.addAll(fixup(pre));
                    out.add(new Group(g.ann(), new ArrayList<>(body)));
                    pushAll(in, post);
                }
            } else {
                out.add(x);
            }
        }
        return out;
    }

    private static void pushAll(ArrayDeque<E> in, List<E> front) {
        for (int k = front.size() - 1; k >= 0; k--) in.push(front.get(k));
    }

    /** Turns disable markers without a verbatim region after them into plain comments. */
    static List<E> removeDisabled(List<E> doc) {
        int[] left = {countVerbatims(doc)};
        return removeDisabled(doc, left);
    }

    private static int countVerbatims(List<E> doc) {
        int n = 0;
        for (E e : doc) {
            if (isVerbatim(e)) n++;
            else if (e instanceof Group g) n += countVerbatims(g.body());
        }
        return n;
    }

    private static List<E> removeDisabled(List<E> doc, int[] left) {
        List<E> out = new ArrayList<>(doc.size());
        for (E e : doc) {
            if (e instanceof Text t && t.kind() == Kind.DISABLE_MARKER) {
                if (left[0] > 0) {
                    out.add(t);
                } else {
                    out.add(new Text(t.nest(), t.offset(), Kind.COMMENT, "/*nixfmt:disable*/"));
                    out.add(SP_HARDLINE);
                }
            } else if (isVerbatim(e)) {
                left[0]--;
                out.add(e);
            } else if (e instanceof Group g) {
                out.add(new Group(g.ann(), removeDisabled(g.body(), left)));
            } else {
                out.add(e);
            }
        }
        return out;
    }
}
