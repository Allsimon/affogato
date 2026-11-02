package org.affogato.web;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.affogato.format.Formatter;
import org.affogato.lint.Diagnostic;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.affogato.parser.Highlighter;
import org.affogato.parser.ParseException;
import org.teavm.jso.JSExport;

/**
 * The formatter and linter for JavaScript. Each method returns JSON, its result or
 * {@code {"error": {"start": ..., "message": ...}}}; offsets count UTF-16 code units, as JavaScript strings do.
 */
public final class Playground {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Playground() {}

    /** {@code {"output": ...}}. */
    @JSExport
    public static String format(String src, int width, int indent, boolean strict) {
        return guard(src, () -> output(Formatter.format(src, width, indent, strict)));
    }

    /**
     * {@code {"output": ...}}: the linter's fixes, then the formatting. {@code rules} is a comma-separated list of rule
     * ids, empty for all.
     */
    @JSExport
    public static String fix(String src, int width, int indent, boolean strict, String rules) {
        return guard(src, () -> output(Formatter.format(Linter.fix(src, rules(rules)), width, indent, strict)));
    }

    /**
     * {@code {"diagnostics": [{"start", "end", "severity", "rule", "message", "fixable"}, ...]}}, of {@code rules} as
     * in {@link #fix}.
     */
    @JSExport
    public static String lint(String src, String rules) {
        return guard(src, () -> {
            StringBuilder sb = new StringBuilder("{\"diagnostics\":[");
            for (Diagnostic d : Linter.lint(src, rules(rules))) {
                if (sb.charAt(sb.length() - 1) == '}') sb.append(',');
                sb.append("{\"start\":")
                        .append(d.start())
                        .append(",\"end\":")
                        .append(d.end())
                        .append(",\"severity\":")
                        .append(quote(d.severity().name().toLowerCase(Locale.ROOT)))
                        .append(",\"rule\":")
                        .append(quote(d.rule().id()))
                        .append(",\"message\":")
                        .append(quote(d.message()))
                        .append(",\"fixable\":")
                        .append(!d.fix().isEmpty())
                        .append('}');
            }
            return sb.append("]}").toString();
        });
    }

    /** {@code {"tokens": [[start, end, kind], ...]}}, sorted and disjoint. */
    @JSExport
    public static String highlight(String src) {
        return guard(src, () -> {
            StringBuilder sb = new StringBuilder("{\"tokens\":[");
            for (Highlighter.Span s : Highlighter.highlight(src)) {
                if (sb.charAt(sb.length() - 1) == ']') sb.append(',');
                sb.append('[')
                        .append(s.start())
                        .append(',')
                        .append(s.end())
                        .append(',')
                        .append(quote(s.kind().id()))
                        .append(']');
            }
            return sb.append("]}").toString();
        });
    }

    private static Set<Rule> rules(String ids) {
        if (ids.isEmpty()) return EnumSet.allOf(Rule.class);
        Set<Rule> rules = EnumSet.noneOf(Rule.class);
        for (String id : ids.split(",")) rules.add(Rule.byId(id));
        return rules;
    }

    /** Input nested deeper than the JavaScript stack allows fails with a RangeError, wrapped in no specific type. */
    private static String guard(String src, Supplier<String> result) {
        try {
            return result.get();
        } catch (ParseException e) {
            return error(Math.min(e.offset, src.length()), e.getMessage());
        } catch (RuntimeException | Error e) {
            return error(0, String.valueOf(e.getMessage()));
        }
    }

    private static String output(String s) {
        return "{\"output\":" + quote(s) + "}";
    }

    private static String error(int start, String message) {
        return "{\"error\":{\"start\":" + start + ",\"message\":" + quote(message) + "}}";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
