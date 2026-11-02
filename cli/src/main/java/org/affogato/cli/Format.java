package org.affogato.cli;

import picocli.CommandLine.Command;

@Command(name = "format", description = "Format Nix files in place, as nixfmt does.")
public final class Format extends Rewrite {
    @Override
    String rewrite(String src) {
        return style.format(src);
    }

    @Override
    String verb() {
        return "formatted";
    }
}
