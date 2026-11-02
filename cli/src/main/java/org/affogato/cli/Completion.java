package org.affogato.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "generate-completion", hidden = true, description = "Print a completion script for bash and zsh.")
public final class Completion implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        out.print(AutoComplete.bash(spec.root().name(), spec.root().commandLine()));
        out.flush();
        return 0;
    }
}
