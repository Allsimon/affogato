package org.affogato.launcher;

import org.affogato.cli.Check;
import org.affogato.cli.Completion;
import org.affogato.cli.Fix;
import org.affogato.cli.Format;
import org.affogato.cli.Lsp;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(
        name = "affogato",
        description = "A Nix language implementation on GraalVM.",
        subcommands = {Format.class, Check.class, Fix.class, Lsp.class, Eval.class, DrvPath.class, Completion.class})
public final class Main {
    @Option(
            names = {"-h", "--help"},
            usageHelp = true,
            scope = ScopeType.INHERIT,
            description = "Show this help and exit.")
    boolean help;

    public static CommandLine commandLine() {
        return org.affogato.cli.Main.configure(new CommandLine(new Main()));
    }

    public static void main(String[] argv) throws Exception {
        org.affogato.cli.Main.run(commandLine(), argv);
    }
}
