package org.affogato.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.affogato.lsp.Server;
import picocli.CommandLine.Command;

@Command(name = "lsp", description = "Run a language server for Nix over stdin and stdout.")
public final class Lsp implements Callable<Integer> {
    @Override
    public Integer call() throws IOException {
        Server.serve(System.in, System.out);
        return 0;
    }
}
