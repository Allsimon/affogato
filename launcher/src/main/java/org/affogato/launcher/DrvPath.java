package org.affogato.launcher;

import picocli.CommandLine.Command;

@Command(name = "drv-path", description = "Evaluate an expression to a derivation and print its drvPath.")
final class DrvPath extends Evaluate {
    @Override
    String output() {
        return "drv-path";
    }
}
