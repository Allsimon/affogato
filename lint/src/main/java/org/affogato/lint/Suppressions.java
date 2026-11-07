package org.affogato.lint;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.affogato.parser.Lexeme;
import org.affogato.parser.Tokenizer;

/**
 * The rules comments switch off: {@code # affogato: ignore <rules>} for its own line and the next one, {@code #
 * affogato: ignore-file <rules>} for the whole file, the rules separated by commas.
 */
public final class Suppressions {
    private static final Pattern DIRECTIVE =
            Pattern.compile("^(?:#|/\\*)\\s*affogato:\\s*(ignore|ignore-file)\\s+([\\w\\s,-]+?)\\s*(?:\\*/)?$");

    private final Set<String> file = new HashSet<>();
    private final Map<Integer, Set<String>> lines = new HashMap<>();
    private final int[] lineStarts;

    private Suppressions(String src) {
        lineStarts = starts(src);
        for (Lexeme l : Tokenizer.tokenize(src)) {
            if (l.kind() != Lexeme.Kind.COMMENT) continue;
            Matcher m = DIRECTIVE.matcher(src.substring(l.start(), l.end()).strip());
            if (!m.matches()) continue;
            List<String> rules = Arrays.stream(m.group(2).split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (m.group(1).equals("ignore-file")) {
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
