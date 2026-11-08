package org.affogato.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Callable;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.IParameterConsumer;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        exitCodeListHeading = "%nExit status%n",
        exitCodeList = {"0:The evaluation succeeded.", "1:The evaluation failed.", "2:The command line is invalid."})
abstract class Evaluate implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    static final class Input {
        @Option(
                names = {"-E", "--expr"},
                paramLabel = "EXPR",
                description = "Evaluate EXPR.")
        String expr;

        @Parameters(paramLabel = "FILE", description = "Evaluate FILE, or the default.nix of a directory.")
        Path file;
    }

    /** Takes the two values of {@code --arg NAME EXPR}; arity 2 would print the label twice in the help. */
    static final class Pair implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> args, ArgSpec argSpec, CommandSpec commandSpec) {
            if (args.size() < 2) {
                throw new ParameterException(
                        commandSpec.commandLine(),
                        "Missing " + argSpec.paramLabel() + " after " + ((OptionSpec) argSpec).longestName());
            }
            List<String> values = argSpec.getValue();
            values.add(args.pop());
            values.add(args.pop());
        }
    }

    @ArgGroup(multiplicity = "1")
    Input input;

    @Option(
            names = {"-A", "--attr"},
            paramLabel = "ATTR",
            description = "Select an attribute path in the result.")
    String attr = "";

    @Option(
            names = "--arg",
            paramLabel = "NAME EXPR",
            parameterConsumer = Pair.class,
            description = "Call a function result with NAME bound to EXPR.")
    List<String> args = new ArrayList<>();

    @Option(
            names = "--argstr",
            paramLabel = "NAME VALUE",
            parameterConsumer = Pair.class,
            description = "Call a function result with NAME bound to the string VALUE.")
    List<String> argstrs = new ArrayList<>();

    @Option(
            names = {"-I", "--include"},
            paramLabel = "PATH",
            description =
                    "Add an entry to the search path of lookups such as <nixpkgs>, as nixpkgs=/path/to/nixpkgs; the"
                            + " entries of NIX_PATH come after.")
    List<String> searchPath = new ArrayList<>();

    @Option(names = "--system", paramLabel = "SYS", description = "Set builtins.currentSystem.")
    String system;

    @Option(
            names = "--show-trace",
            description = "Print the location of the failing expression when the evaluation fails.")
    boolean showTrace;

    @Option(
            names = "--timeout",
            paramLabel = "MS",
            description = "Stop the evaluation after MS milliseconds; 0, the default, for no limit.")
    long timeout;

    abstract Interpreter.Output output();

    /** Nix strings are bytes; arguments arrive as UTF-16, so re-encode them one char per UTF-8 byte. */
    private static String bytes(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    /** Nix strings hold one byte per char; decode them as UTF-8 for the writer, which encodes. */
    private static String text(String bytes) {
        for (int i = 0; i < bytes.length(); i++) if (bytes.charAt(i) > 0xff) return bytes;
        return new String(bytes.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static void pairs(boolean string, List<String> values, List<Interpreter.Arg> out) {
        for (int i = 0; i < values.size(); i += 2) {
            out.add(new Interpreter.Arg(values.get(i), bytes(values.get(i + 1)), string));
        }
    }

    @Override
    public Integer call() throws InterruptedException {
        List<String> path = new ArrayList<>(searchPath);
        String envPath = System.getenv("NIX_PATH");
        if (envPath != null && !envPath.isEmpty()) path.add(envPath);
        List<Interpreter.Arg> nixArgs = new ArrayList<>();
        pairs(false, args, nixArgs);
        pairs(true, argstrs, nixArgs);
        var options = new Interpreter.Options(
                String.join(":", path), system == null ? "" : system, "", output(), attr, nixArgs);

        PrintWriter err = spec.commandLine().getErr();
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.ISO_8859_1);
        try {
            String file = null;
            String content;
            if (input.expr != null) {
                content = bytes(input.expr);
            } else {
                Path p = input.file.toAbsolutePath().normalize();
                if (Files.isDirectory(p)) p = p.resolve("default.nix");
                file = p.toString();
                content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            }
            out.println(Interpreter.evaluate(options, file, content, timeout));
            return 0;
        } catch (EvalException e) {
            err.println("error: " + text(e.getMessage()));
            if (showTrace && e.location != null) err.println("       at " + text(e.location));
            return 1;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }
}
