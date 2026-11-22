package org.affogato.lint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Replaces the source between offsets {@code start} and {@code end} with {@code replacement}. */
public record Edit(int start, int end, String replacement) {
    /** Applies {@code edits}, which must not overlap. */
    public static String apply(String src, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder sb = new StringBuilder(src);
        for (Edit e : sorted) sb.replace(e.start(), e.end(), e.replacement());
        return sb.toString();
    }
}
