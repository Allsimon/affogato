package org.affogato.cli;

import org.affogato.format.Formatter;
import picocli.CommandLine.Option;

final class Style {
    @Option(
            names = "--width",
            paramLabel = "N",
            description = "Fit lines in N columns where possible (default: ${DEFAULT-VALUE}).")
    int width = Formatter.DEFAULT_WIDTH;

    @Option(names = "--indent", paramLabel = "N", description = "Indent by N spaces (default: ${DEFAULT-VALUE}).")
    int indent = Formatter.DEFAULT_INDENT;

    @Option(names = "--strict", description = "Depend less on how the input was laid out.")
    boolean strict;

    String format(String src) {
        return Formatter.format(src, width, indent, strict);
    }
}
