package org.affogato.lint;

/** Edits that keep the surrounding layout tidy. */
final class Text {
    private Text() {}

    /**
     * Deletes {@code start..end} with the blanks after it, and the whole line when nothing else is left on it.
     */
    static Edit delete(String src, int start, int end) {
        int e = end;
        while (e < src.length() && isBlank(src.charAt(e))) e++;
        int s = start;
        while (s > 0 && isBlank(src.charAt(s - 1))) s--;
        boolean lineStart = s == 0 || src.charAt(s - 1) == '\n';
        boolean lineEnd = e == src.length() || src.charAt(e) == '\n';
        if (lineStart && lineEnd) return new Edit(s, e < src.length() ? e + 1 : e, "");
        return new Edit(lineEnd ? s : start, e, "");
    }

    /**
     * Deletes an opening parenthesis and the blanks after it, keeping a space where a name would otherwise run into
     * what follows: {@code f(./x)} becomes {@code f ./x}, not the path {@code f./x}.
     */
    static Edit removeOpen(String src, int start, int end) {
        char after = src.charAt(end);
        boolean merge = start > 0
                && isWord(src.charAt(start - 1))
                && (isWord(after) || after == '.' || after == '/' || after == '~');
        return new Edit(start, end, merge ? " " : "");
    }

    static Edit removeClose(String src, int start, int end) {
        boolean merge = end < src.length() && isWord(src.charAt(start - 1)) && isWord(src.charAt(end));
        return new Edit(start, end, merge ? " " : "");
    }

    /** The start of the {@code #} comment lines right above the line of {@code at}, else {@code at}. */
    static int leadingComments(String src, int at) {
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
    static int trailingComment(String src, int at) {
        int i = at;
        while (i < src.length() && isBlank(src.charAt(i))) i++;
        if (i == src.length() || src.charAt(i) != '#') return at;
        int end = src.indexOf('\n', i);
        return end < 0 ? src.length() : end;
    }

    // TeaVM 0.16.0's String.isBlank() only accepts spaces, so the playground would keep line breaks and tabs.
    // TODO: use String.isBlank() once TeaVM's matches the JDK.
    static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return false;
        return true;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-';
    }
}
