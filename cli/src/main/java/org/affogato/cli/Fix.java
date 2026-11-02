package org.affogato.cli;

import org.affogato.lint.Linter;
import picocli.CommandLine.Command;

@Command(name = "fix", description = "Apply the linter's fixes to Nix files in place, then format them.")
public final class Fix extends Rewrite {
    @Override
    String rewrite(String src) {
        return style.format(Linter.fix(src));
    }

    @Override
    String verb() {
        return "fixed";
    }
}
