package org.affogato.format;

import static org.affogato.format.Doc.*;

import java.util.ArrayList;
import java.util.List;

/** Port of nixfmt's {@code layoutGreedy}. */
final class Layout {

    /** The elements of {@code list} from {@code from} on, followed by {@code next}: a lazily concatenated document. */
    private record Seq(List<E> list, int from, Seq next) {
        static Seq of(List<E> list, Seq next) {
            return new Seq(list, 0, next);
        }

        static Seq norm(Seq s) {
            while (s != null && s.from >= s.list.size()) s = s.next;
            return s;
        }
    }

    private static E head(Seq s) {
        return s.list.get(s.from);
    }

    private static Seq tail(Seq s) {
        return Seq.norm(new Seq(s.list, s.from + 1, s.next));
    }

    /** Merges a leading pair of spacings, as the functions below do before matching. */
    private static Seq mergeHead(Seq s) {
        while (s != null && head(s) instanceof Spacing a) {
            Seq t = tail(s);
            if (t == null || !(head(t) instanceof Spacing b)) break;
            s = Seq.of(List.of(merge(a, b)), tail(t));
        }
        return s;
    }

    private record Indent(int ci, int nl, Indent next) {}

    private final int tw;
    private final int iw;
    private int cc;
    private Indent indents = new Indent(0, 0, null);
    private boolean suppress;
    private final StringBuilder out = new StringBuilder();

    private Layout(int tw, int iw) {
        this.tw = tw;
        this.iw = iw;
    }

    static String layout(int tw, int iw, List<E> doc) {
        Layout l = new Layout(tw, iw);
        l.go(List.of(new Group(GroupAnn.REGULAR, doc)), null);
        return l.out.toString();
    }

    private void emit(String t) {
        if (!suppress) out.append(t);
    }

    private void putText(int textNL, int textOffset, String t, boolean withIndent) {
        while (true) {
            int nl = indents.nl();
            if (textNL > nl) {
                indents = new Indent(cc == 0 ? indents.ci() + iw : indents.ci(), textNL, indents);
                break;
            } else if (textNL < nl) {
                indents = indents.next();
            } else {
                break;
            }
        }
        int old = cc;
        cc = withIndent ? cc + textWidth(t) : Math.max(1, cc + textWidth(t));
        if (old == 0 && withIndent) emit(" ".repeat(indents.ci() + textOffset));
        emit(t);
    }

    private void putPlain(String t) {
        cc += textWidth(t);
        emit(t);
    }

    private void putNL(int n) {
        cc = 0;
        emit("\n".repeat(n));
    }

    private void go(List<E> xs, Seq ys) {
        for (int i = 0; i < xs.size(); i++) goOne(xs.get(i), Seq.norm(new Seq(xs, i + 1, ys)));
    }

    private void goOne(E x, Seq xs) {
        boolean needsIndent = cc == 0;
        switch (x) {
            case Text t -> {
                switch (t.kind()) {
                    case TRAILING_COMMENT -> {
                        if (cc == 2 && nextIndent(xs)[0] > indents.nl()) {
                            putPlain(" ");
                            putPlain(t.text());
                        } else {
                            putText(t.nest(), t.offset(), t.text(), true);
                        }
                    }
                    case DISABLE_MARKER -> suppress = true;
                    case RAW -> putText(t.nest(), t.offset(), t.text(), false);
                    case VERBATIM, VERBATIM_TO_EOF -> {
                        String s = t.text();
                        if (!(needsIndent || suppress)) out.append('\n');
                        out.append(s);
                        cc = textWidth(s.substring(s.lastIndexOf('\n') + 1));
                        suppress = false;
                    }
                    default -> putText(t.nest(), t.offset(), t.text(), true);
                }
            }
            case Spacing sp -> {
                if (needsIndent) return;
                switch (sp.kind()) {
                    case BREAK, SPACE, HARDLINE -> putNL(1);
                    case HARDSPACE -> putPlain(" ");
                    case EMPTYLINE -> putNL(2);
                    case NEWLINES -> putNL(sp.n());
                    case SOFTBREAK -> {
                        if (!firstLineFits(tw - cc, tw, xs)) putNL(1);
                    }
                    case SOFTSPACE -> {
                        if (firstLineFits(tw - cc - 1, tw, xs)) putPlain(" ");
                        else putNL(1);
                    }
                    default -> throw new IllegalStateException();
                }
            }
            case Group g -> {
                if (goGroup(g.body(), xs)) return;
                if (g.ann() != GroupAnn.TRANSPARENT) {
                    List<List<E>[]> prios = priorityGroups(g.body());
                    for (int i = prios.size() - 1; i >= 0; i--) {
                        if (goPriorityGroup(prios.get(i), xs)) return;
                    }
                }
                go(g.body(), xs);
            }
            case Nest n -> throw Doc.unresolved();
        }
    }

    private boolean goPriorityGroup(List<E>[] seg, Seq rest) {
        int cc0 = cc;
        Indent indents0 = indents;
        boolean suppress0 = suppress;
        int length0 = out.length();
        List<E> pre = seg[0], prio = seg[1], post = seg[2];
        if (goGroup(pre, Seq.norm(Seq.of(prio, Seq.norm(Seq.of(post, rest)))))) {
            go(prio, Seq.norm(Seq.of(unexpandSpacing(post), rest)));
            if (goGroup(post, rest)) return true;
        }
        cc = cc0;
        indents = indents0;
        suppress = suppress0;
        out.setLength(length0);
        return false;
    }

    /** Renders the group on one line if it fits, together with what follows up to its first line break. */
    private boolean goGroup(List<E> grp, Seq rest) {
        if (grp.isEmpty()) return true;
        if (cc == 0) {
            List<E> g = grp;
            if (grp.getFirst() instanceof Spacing) {
                g = grp.subList(1, grp.size());
            } else if (grp.getFirst() instanceof Group inner
                    && !inner.body().isEmpty()
                    && inner.body().getFirst() instanceof Spacing) {
                g = new ArrayList<>(grp);
                g.set(
                        0,
                        new Group(
                                inner.ann(),
                                inner.body().subList(1, inner.body().size())));
            }
            int[] ni = nextIndent(Seq.norm(Seq.of(g, null)));
            int nl = ni[0], off = ni[1];
            int lastLineNL = indents.nl();
            int lineNL = lastLineNL + (nl > lastLineNL ? iw : 0);
            int indentWillIncrease = nextIndent(rest)[0] > lineNL ? iw : 0;
            String t = fits(indentWillIncrease, tw - firstLineWidth(rest), Seq.norm(Seq.of(g, null)));
            if (t == null) return false;
            putText(nl, off, t, true);
            return true;
        } else {
            int lineNL = indents.nl();
            int indentWillIncrease = nextIndent(rest)[0] > lineNL ? iw : 0;
            String t = fits(indentWillIncrease - cc, tw - cc - firstLineWidth(rest), Seq.norm(Seq.of(grp, null)));
            if (t == null) return false;
            emit(t);
            cc += textWidth(t);
            return true;
        }
    }

    /** The (pre, prio, post) segmentations of a group, one per priority group it holds. */
    @SuppressWarnings("unchecked")
    private static List<List<E>[]> priorityGroups(List<E> doc) {
        List<Boolean> prio = new ArrayList<>();
        List<List<E>> segs = new ArrayList<>();
        segments(doc, prio, segs);
        List<List<E>[]> out = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            if (!prio.get(i)) continue;
            List<E> pre = new ArrayList<>(), post = new ArrayList<>();
            for (int j = 0; j < i; j++) pre.addAll(segs.get(j));
            for (int j = i + 1; j < segs.size(); j++) post.addAll(segs.get(j));
            out.add(new List[] {pre, segs.get(i), post});
        }
        return out;
    }

    private static void segments(List<E> doc, List<Boolean> prio, List<List<E>> segs) {
        for (E x : doc) {
            if (x instanceof Group g && g.ann() == GroupAnn.PRIORITY) {
                prio.add(true);
                segs.add(g.body());
            } else if (x instanceof Group g && g.ann() == GroupAnn.TRANSPARENT) {
                segments(g.body(), prio, segs);
            } else if (!prio.isEmpty() && !prio.getLast()) {
                segs.getLast().add(x);
            } else {
                prio.add(false);
                segs.add(new ArrayList<>(List.of(x)));
            }
        }
    }

    /**
     * Fits the document on one line of width {@code c}, or returns null. {@code ni} is the indentation of the next
     * line relative to this one, for trailing comments.
     */
    private static String fits(int ni, int c, Seq s) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (c < 0) return null;
            s = mergeHead(s);
            if (s == null) return sb.toString();
            E x = head(s);
            s = tail(s);
            switch (x) {
                case Text t -> {
                    switch (t.kind()) {
                        case REGULAR -> {
                            int w = textWidth(t.text());
                            sb.append(t.text());
                            ni -= w;
                            c -= w;
                        }
                        case COMMENT -> sb.append(t.text());
                        case TRAILING_COMMENT -> {
                            if (ni == 0) sb.append(' ');
                            sb.append(t.text());
                        }
                        case TRAILING -> {}
                        default -> {
                            return null;
                        }
                    }
                }
                case Spacing sp -> {
                    switch (sp.kind()) {
                        case SOFTBREAK, BREAK -> {}
                        case SOFTSPACE, SPACE, HARDSPACE -> {
                            sb.append(' ');
                            ni--;
                            c--;
                        }
                        default -> {
                            return null;
                        }
                    }
                }
                case Group g -> s = Seq.norm(Seq.of(g.body(), s));
                case Nest n -> throw Doc.unresolved();
            }
        }
    }

    private static int firstLineWidth(Seq s) {
        int w = 0;
        while (true) {
            s = mergeHead(s);
            if (s == null) return w;
            E x = head(s);
            s = tail(s);
            switch (x) {
                case Text t -> {
                    if (t.kind() != Kind.COMMENT && t.kind() != Kind.TRAILING_COMMENT) w += textWidth(t.text());
                }
                case Spacing sp -> {
                    if (sp.kind() != HARDSPACE) return w;
                    w++;
                }
                case Group g -> s = Seq.norm(Seq.of(g.body(), s));
                case Nest n -> throw Doc.unresolved();
            }
        }
    }

    private static boolean firstLineFits(int targetWidth, int maxWidth, Seq s) {
        int c = maxWidth;
        while (true) {
            if (c < 0) return false;
            s = mergeHead(s);
            if (s == null) return maxWidth - c <= targetWidth;
            E x = head(s);
            s = tail(s);
            switch (x) {
                case Text t -> {
                    if (t.kind() == Kind.REGULAR) c -= textWidth(t.text());
                    else if (isVerbatim(t)) return maxWidth - c <= targetWidth;
                }
                case Spacing sp -> {
                    if (sp.kind() != HARDSPACE) return maxWidth - c <= targetWidth;
                    c--;
                }
                case Group g -> {
                    String t = fits(0, c - firstLineWidth(s), Seq.norm(Seq.of(g.body(), null)));
                    if (t == null) s = Seq.norm(Seq.of(g.body(), s));
                    else c -= textWidth(t);
                }
                case Nest n -> throw Doc.unresolved();
            }
        }
    }

    /** Nesting level and offset of the first text; like nixfmt, it looks no further than the first group. */
    private static int[] nextIndent(Seq s) {
        while (s != null) {
            E x = head(s);
            s = tail(s);
            if (x instanceof Text t) return new int[] {t.nest(), t.offset()};
            if (x instanceof Group g) s = Seq.norm(Seq.of(g.body(), null));
        }
        return new int[] {0, 0};
    }
}
