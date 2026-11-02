package org.affogato.launcher;

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
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
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
import picocli.CommandLine.Unmatched;

@Command(
        exitCodeListHeading = "%nExit status%n",
        exitCodeList = {"0:The evaluation succeeded.", "1:The evaluation failed.", "2:The command line is invalid."},
        footerHeading = "%nEngine options%n",
        footer = {
            "Any other --name or --name=value option goes to the GraalVM polyglot engine, as name=true or name=value."
                    + " This includes the language's own --nix.* options and the Truffle tools, such as --inspect"
                    + " for the Chrome DevTools debugger and --cpusampler to profile the evaluation.",
            "",
            "Runtime compilation is off by default: on nixpkgs it made evaluations 4 to 10%% slower for 1.5 times"
                    + " the CPU and memory. --engine.Compilation=true turns it back on."
        })
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

    @Option(names = "--show-trace", description = "Print the Nix call stack when the evaluation fails.")
    boolean showTrace;

    @Unmatched
    List<String> engineOptions = new ArrayList<>();

    abstract String output();

    /** Nix strings are bytes; arguments arrive as UTF-16, so re-encode them one char per UTF-8 byte. */
    private static String bytes(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static void pairs(String kind, List<String> values, List<String> out) {
        for (int i = 0; i < values.size(); i += 2) {
            out.add(kind + "\u0002" + values.get(i) + "\u0002" + bytes(values.get(i + 1)));
        }
    }

    /** Truffle's fallback runtime, on a JDK whose compiler it cannot use, rejects the compilation options. */
    private static boolean compiles() {
        try (Engine engine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            return engine.getOptions().get("engine.Compilation") != null;
        }
    }

    @Override
    public Integer call() {
        Context.Builder builder =
                Context.newBuilder("nix").allowAllAccess(true).option("engine.WarnInterpreterOnly", "false");
        // Measured on nixpkgs: runtime compilation made evaluations 4-10% slower at 1.5x the CPU and memory.
        if (compiles()) builder.option("engine.Compilation", "false");
        if (system != null) builder.option("nix.System", system);
        for (String a : engineOptions) {
            if (!a.startsWith("--")) throw new ParameterException(spec.commandLine(), "Unexpected argument: " + a);
            int eq = a.indexOf('=');
            if (eq < 0) {
                builder.option(a.substring(2), "true");
            } else {
                builder.option(a.substring(2, eq), a.substring(eq + 1));
            }
        }
        List<String> path = new ArrayList<>(searchPath);
        String envPath = System.getenv("NIX_PATH");
        if (envPath != null && !envPath.isEmpty()) path.add(envPath);
        List<String> nixArgs = new ArrayList<>();
        pairs("expr", args, nixArgs);
        pairs("str", argstrs, nixArgs);
        builder.option("nix.NixPath", String.join(":", path));
        builder.option("nix.Output", output());
        builder.option("nix.Attr", attr);
        builder.option("nix.Args", String.join("\u0001", nixArgs));

        PrintWriter err = spec.commandLine().getErr();
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.ISO_8859_1);
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            throw new ParameterException(spec.commandLine(), e.getMessage());
        }
        try (Context ctx = context) {
            Source source;
            if (input.expr != null) {
                source = Source.create("nix", bytes(input.expr));
            } else {
                Path p = input.file.toAbsolutePath().normalize();
                if (Files.isDirectory(p)) p = p.resolve("default.nix");
                String content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                source = Source.newBuilder("nix", content, p.toString()).build();
            }
            out.println(ctx.eval(source).asString());
            return 0;
        } catch (PolyglotException e) {
            if (e.isInternalError()) {
                e.printStackTrace(err);
            } else if (e.getCause() instanceof StackOverflowError
                    || e.getMessage() != null && e.getMessage().contains("StackOverflowError")) {
                err.println("error: stack overflow");
            } else {
                err.println("error: " + e.getMessage());
                if (showTrace) {
                    for (PolyglotException.StackFrame f : e.getPolyglotStackTrace()) {
                        if (!f.isGuestFrame() || f.getSourceLocation() == null) continue;
                        var loc = f.getSourceLocation();
                        err.println("       at " + f.getRootName() + " "
                                + loc.getSource().getName() + ":" + loc.getStartLine() + ":" + loc.getStartColumn());
                    }
                }
            }
            return 1;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }
}
