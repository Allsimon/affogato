package org.affogato.lint;

import java.util.List;
import java.util.Locale;

/** A finding between offsets {@code start} and {@code end}, with the edits that fix it, if any. */
public record Diagnostic(Rule rule, Severity severity, int start, int end, String message, List<Edit> fix) {
    Diagnostic(Rule rule, int start, int end, String message, List<Edit> fix) {
        this(rule, rule.severity(), start, end, message, fix);
    }

    /** {@code file:line:column: severity[rule]: message}, with lines and columns counted from 1. */
    public String render(String src, String fileName) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < start; i++) {
            if (src.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return fileName + ":" + line + ":" + (start - lineStart + 1) + ": "
                + severity.name().toLowerCase(Locale.ROOT) + "[" + rule.id() + "]: " + message;
    }
}
