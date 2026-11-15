package org.affogato.lint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.affogato.parser.Lexeme;
import org.affogato.parser.Tokenizer;

/**
 * The rules comments switch off: {@code # affogato: ignore <rules>} for its own line and the next one, {@code #
 * affogato: ignore-file <rules>} for the whole file, the rules separated by commas.
 */
public final class Suppressions {
    private final Set<String> file = new HashSet<>();
    private final Map<Integer, Set<String>> lines = new HashMap<>();
    private final int[] lineStarts;

    private Suppressions(String src) {
        lineStarts = starts(src);
        for (Lexeme l : Tokenizer.tokenize(src)) {
            if (l.kind() != Lexeme.Kind.COMMENT) continue;
            String comment = src.substring(l.start(), l.end()).strip();
            int start = comment.startsWith("#") ? 1 : comment.startsWith("/*") ? 2 : -1;
            if (start < 0) continue;
            int i = skipSpace(comment, start);
            if (!comment.startsWith("affogato:", i)) continue;
            i = skipSpace(comment, i + "affogato:".length());
            boolean wholeFile = comment.startsWith("ignore-file", i);
            if (!wholeFile && !comment.startsWith("ignore", i)) continue;
            i += wholeFile ? "ignore-file".length() : "ignore".length();
            if (i == comment.length() || !isSpace(comment.charAt(i))) continue;
            List<String> rules = rules(comment.substring(i, comment.length() - (comment.endsWith("*/") ? 2 : 0)));
            if (rules == null) continue;
            if (wholeFile) {
                file.addAll(rules);
            } else {
                int line = line(l.start());
                lines.computeIfAbsent(line, k -> new HashSet<>()).addAll(rules);
                lines.computeIfAbsent(line + 1, k -> new HashSet<>()).addAll(rules);
            }
        }
    }

    static Suppressions of(String src) {
        return new Suppressions(src);
    }

    /** The comment that switches {@code rule} off for its line and the next one. */
    public static String ignore(Rule rule) {
        return "# affogato: ignore " + rule.id();
    }

    /** The comment that switches {@code rule} off in the whole file. */
    public static String ignoreFile(Rule rule) {
        return "# affogato: ignore-file " + rule.id();
    }

    /** The rules named in {@code list}, or null unless it holds only {@code [\w\s,-]}. */
    private static List<String> rules(String list) {
        List<String> rules = new ArrayList<>();
        int from = 0;
        for (int i = 0; i <= list.length(); i++) {
            char c = i < list.length() ? list.charAt(i) : ',';
            if (c == ',') {
                String rule = list.substring(from, i).strip();
                if (!rule.isEmpty()) rules.add(rule);
                from = i + 1;
            } else if (!isSpace(c)
                    && c != '-'
                    && c != '_'
                    && !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) return null;
        }
        return rules;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && isSpace(s.charAt(i))) i++;
        return i;
    }

    /** {@code \s} of a regex. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r';
    }

    private static int[] starts(String src) {
        int count = 1;
        for (int i = 0; i < src.length(); i++) if (src.charAt(i) == '\n') count++;
        int[] starts = new int[count];
        for (int i = 0, n = 1; i < src.length(); i++) if (src.charAt(i) == '\n') starts[n++] = i + 1;
        return starts;
    }

    private int line(int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        return i >= 0 ? i : -i - 2;
    }

    boolean suppresses(Diagnostic d) {
        if (d.rule() == Rule.SYNTAX_ERROR) return false;
        String id = d.rule().id();
        return file.contains(id)
                || lines.getOrDefault(line(d.start()), Set.of()).contains(id);
    }
}
