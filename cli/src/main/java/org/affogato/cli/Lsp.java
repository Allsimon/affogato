package org.affogato.cli;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;
import org.affogato.evaluator.SessionEvaluator;
import org.affogato.lsp.Server;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "lsp", description = "Run a language server for Nix over stdin and stdout.")
public final class Lsp implements Callable<Integer> {
    @Option(
            names = "--nixpkgs",
            paramLabel = "EXPR",
            description = "Complete pkgs and lib from this expression (default: ${DEFAULT-VALUE}).")
    String nixpkgs = "import <nixpkgs> { }";

    @Option(
            names = "--nixos-options",
            paramLabel = "EXPR",
            description = "Complete the options of NixOS modules from this expression (default: ${DEFAULT-VALUE}).")
    String nixosOptions = "(import <nixpkgs/nixos> { configuration = { }; }).options";

    @Override
    public Integer call() throws IOException {
        try (SessionEvaluator evaluator = new SessionEvaluator(
                nixpkgs,
                Map.of("nixos", nixosOptions),
                System.getProperty("user.dir"),
                "",
                SessionEvaluator::printConfig)) {
            Server.serve(System.in, System.out, evaluator.main());
        }
        return 0;
    }
}
