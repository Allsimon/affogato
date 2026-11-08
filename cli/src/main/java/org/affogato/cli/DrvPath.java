package org.affogato.cli;

import org.affogato.Interpreter;
import picocli.CommandLine.Command;

@Command(name = "drv-path", description = "Evaluate an expression to a derivation and print its drvPath.")
final class DrvPath extends Evaluate {
    @Override
    Interpreter.Output output() {
        return Interpreter.Output.DRV_PATH;
    }
}
