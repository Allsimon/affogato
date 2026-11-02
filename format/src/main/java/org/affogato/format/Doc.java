package org.affogato.format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Port of nixfmt's Nixfmt.Predoc document model: a document is a list of elements. */
final class Doc {
    private Doc() {}

    sealed interface E permits Text, Spacing, Group, Nest {}

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

    /** Adds to the nest and offset of every text in {@code body}; {@link #fixup} removes it. */
    record Nest(int nest, int offset, List<E> body) implements E {}

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

    /** Returns the only non-empty part itself, so the result must not be modified. */
    @SafeVarargs
    static List<E> cat(List<E>... parts) {
        int size = 0;
        List<E> only = EMPTY;
        for (List<E> p : parts) {
            if (p.isEmpty()) continue;
            only = size == 0 ? p : null;
            size += p.size();
        }
        if (only != null) return only;
        List<E> out = new ArrayList<>(size);
        for (List<E> p : parts) out.addAll(p);
        return out;
    }

    static List<E> group(List<E> d) {
        return List.of(new Group(GroupAnn.REGULAR, trimmed(d)));
    }

    static List<E> group(GroupAnn ann, List<E> d) {
        return List.of(new Group(ann, trimmed(d)));
    }

    static List<E> nest(List<E> d) {
        return d.isEmpty() ? d : List.of(new Nest(1, 0, trimmed(d)));
    }

    static List<E> offset(int level, List<E> d) {
        return d.isEmpty() || level == 0 ? d : List.of(new Nest(0, level, trimmed(d)));
    }

    /** Drops the spare capacity of a list that a group or nest keeps. */
    private static List<E> trimmed(List<E> d) {
        if (d instanceof ArrayList<E> a) a.trimToSize();
        return d;
    }

    private static Text shifted(int nest, int offset, Text t) {
        return nest == 0 && offset == 0 ? t : new Text(t.nest() + nest, t.offset() + offset, t.kind(), t.text());
    }

    static List<E> surroundWith(List<E> outside, List<E> inner) {
        return cat(outside, inner, outside);
    }

    static List<E> sepBy(List<E> separator, List<List<E>> docs) {
        int size = Math.max(0, docs.size() - 1) * separator.size();
        for (List<E> d : docs) size += d.size();
        List<E> out = new ArrayList<>(size);
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
            case Nest n -> throw unresolved();
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
        return unexpand(new int[] {limit}, 0, 0, d, out) ? out : null;
    }

    private static final int NO_LIMIT = Integer.MIN_VALUE;

    static List<E> unexpandSpacing_(List<E> d) {
        return unexpandSpacing_(NO_LIMIT, d);
    }

    private static boolean unexpand(int[] n, int nest, int offset, List<E> d, List<E> out) {
        for (E e : d) {
            if (n[0] != NO_LIMIT && n[0] < 0) return false;
            switch (e) {
                case Text t -> {
                    if (t.kind() == Kind.DISABLE_MARKER || isVerbatim(t) || t.kind() == Kind.RAW) return false;
                    out.add(shifted(nest, offset, t));
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
                    if (!unexpand(n, nest, offset, g.body(), out)) return false;
                }
                case Nest m -> {
                    if (!unexpand(n, nest + m.nest(), offset + m.offset(), m.body(), out)) return false;
                }
            }
        }
        return n[0] == NO_LIMIT || n[0] >= 0;
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
     * Moves hard spacing and comments out of the edges of groups, merges consecutive spacings and texts, removes
     * empty groups, and applies the {@link Nest}s while reading them, as https://github.com/NixOS/nixfmt/pull/474
     * does, so that the unnested document is never built whole.
     */
    static List<E> fixup(List<E> doc) {
        Input in = new Input(doc);
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

    static IllegalStateException unresolved() {
        return new IllegalStateException("Nest left after resolveNests");
    }

    private static void pushAll(Input in, List<E> front) {
        for (int k = front.size() - 1; k >= 0; k--) in.push(front.get(k));
    }

    /**
     * {@code doc} read in place with its {@link Nest}s spliced in and applied, after the elements pushed back. A group
     * under a nest gets its body wrapped in that nest, for the recursive {@link #fixup} to apply.
     */
    private static final class Input {
        private final ArrayDeque<E> pushed = new ArrayDeque<>();
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();

        private static final class Frame {
            final List<E> doc;
            final int nest, offset;
            int next;

            Frame(List<E> doc, int nest, int offset) {
                this.doc = doc;
                this.nest = nest;
                this.offset = offset;
            }
        }

        Input(List<E> doc) {
            frames.push(new Frame(doc, 0, 0));
        }

        boolean isEmpty() {
            return peek() == null;
        }

        E peek() {
            if (pushed.isEmpty()) {
                E e = read();
                if (e == null) return null;
                pushed.push(e);
            }
            return pushed.peek();
        }

        E poll() {
            return pushed.isEmpty() ? read() : pushed.pop();
        }

        void push(E e) {
            pushed.push(e);
        }

        private E read() {
            while (!frames.isEmpty()) {
                Frame f = frames.peek();
                if (f.next == f.doc.size()) {
                    frames.pop();
                    continue;
                }
                E e = f.doc.get(f.next++);
                switch (e) {
                    case Nest n -> frames.push(new Frame(n.body(), f.nest + n.nest(), f.offset + n.offset()));
                    case Text t -> {
                        return shifted(f.nest, f.offset, t);
                    }
                    case Group g -> {
                        return f.nest == 0 && f.offset == 0
                                ? g
                                : new Group(g.ann(), List.of(new Nest(f.nest, f.offset, g.body())));
                    }
                    case Spacing s -> {
                        return s;
                    }
                }
            }
            return null;
        }
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
            else if (e instanceof Nest m) n += countVerbatims(m.body());
        }
        return n;
    }

    /** Returns {@code doc} itself when nothing changes. */
    private static List<E> removeDisabled(List<E> doc, int[] left) {
        List<E> out = null;
        for (int i = 0; i < doc.size(); i++) {
            E e = doc.get(i);
            if (e instanceof Text t && t.kind() == Kind.DISABLE_MARKER && left[0] <= 0) {
                out = copied(doc, i, out);
                out.add(new Text(t.nest(), t.offset(), Kind.COMMENT, "/*nixfmt:disable*/"));
                out.add(SP_HARDLINE);
                continue;
            }
            E r = e;
            if (isVerbatim(e)) {
                left[0]--;
            } else if (e instanceof Group g) {
                List<E> body = removeDisabled(g.body(), left);
                if (body != g.body()) r = new Group(g.ann(), body);
            } else if (e instanceof Nest n) {
                List<E> body = removeDisabled(n.body(), left);
                if (body != n.body()) r = new Nest(n.nest(), n.offset(), body);
            }
            if (r != e) out = copied(doc, i, out);
            if (out != null) out.add(r);
        }
        return out != null ? out : doc;
    }

    private static List<E> copied(List<E> doc, int upTo, List<E> out) {
        if (out != null) return out;
        List<E> copy = new ArrayList<>(doc.size() + 1);
        copy.addAll(doc.subList(0, upTo));
        return copy;
    }
}
