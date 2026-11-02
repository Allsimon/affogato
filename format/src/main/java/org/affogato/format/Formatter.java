package org.affogato.format;

import java.util.List;
import org.affogato.parser.ParseException;
import org.affogato.parser.Parser;

/** Formats Nix code as nixfmt does. */
public final class Formatter {
    public static final int DEFAULT_WIDTH = 100;
    public static final int DEFAULT_INDENT = 2;

    private Formatter() {}

    public static String format(String src) {
        return format(src, DEFAULT_WIDTH, DEFAULT_INDENT, false);
    }

    /**
     * Formats {@code src} for lines of {@code width} columns where possible, indenting by {@code indent} spaces. In
     * {@code strict} mode the output depends less on how the input was laid out.
     *
     * @throws ParseException if {@code src} is not valid Nix
     */
    public static String format(String src, int width, int indent, boolean strict) {
        List<Doc.E> doc = Doc.fixup(Doc.removeDisabled(pretty(src, strict)));
        String out = Layout.layout(width, indent, doc);
        return endsWithEofRegion(doc) ? Convert.stripStart(out) : Convert.strip(out) + "\n";
    }

    /** Apart from {@link #format}, so that the tree is garbage before {@link Doc#fixup}. */
    private static List<Doc.E> pretty(String src, boolean strict) {
        Tree.Whole whole = Convert.file(src, Parser.parseSyntax(src), strict);
        Tree.Expression expr = Tree.mapFirst(
                new Tree.AnnMap() {
                    @Override
                    public <T> Tree.Ann<T> apply(Tree.Ann<T> a) {
                        List<Tree.Trivium> pre = a.pre();
                        int i = 0;
                        while (i < pre.size() && pre.get(i) instanceof Tree.EmptyLine) i++;
                        return a.withPre(pre.subList(i, pre.size()));
                    }
                },
                whole.expr());
        return Pretty.whole(new Tree.Whole(expr, whole.trivia()));
    }

    private static boolean endsWithEofRegion(List<Doc.E> doc) {
        for (int i = doc.size() - 1; i >= 0; i--) {
            switch (doc.get(i)) {
                case Doc.Spacing s -> {}
                case Doc.Text t -> {
                    return t.kind() == Doc.Kind.VERBATIM_TO_EOF;
                }
                case Doc.Group g -> {
                    return endsWithEofRegion(g.body());
                }
                case Doc.Nest n -> throw Doc.unresolved();
            }
        }
        return false;
    }
}
