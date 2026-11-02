package org.affogato.launcher;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "eval", description = "Evaluate an expression and print the result, like nix-instantiate --eval.")
final class Eval extends Evaluate {
    @Option(names = "--strict", description = "Force the result deeply before printing it.")
    boolean strict;

    @Option(names = "--json", description = "Print the result as JSON.")
    boolean json;

    @Option(names = "--raw", description = "Print a string result without quotes or escapes.")
    boolean raw;

    @Override
    String output() {
        return json ? "json" : raw ? "raw" : strict ? "strict" : "eval";
    }
}
