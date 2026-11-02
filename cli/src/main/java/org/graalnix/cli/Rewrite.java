package org.graalnix.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.graalnix.parser.ParseException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        exitCodeListHeading = "%nExit status%n",
        exitCodeList = {
            "0:Every file was processed.",
            "1:A file cannot be read or does not parse, or would change under --check.",
            "2:The command line is invalid."
        },
        footerHeading = "%nFiles%n",
        footer = {
            "Files are read as UTF-8, and one that is not is refused. A file is written only when its content"
                    + " changes. A file that fails is reported on standard error, and the other files are still"
                    + " processed. Each file rewritten is named on standard error, then a count of the files"
                    + " changed, unchanged and failed, and the time taken.",
            "",
            "This follows the treefmt formatter spec:",
            "https://treefmt.com/latest/reference/formatter-spec/"
        })
abstract class Rewrite implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Mixin
    Style style;

    @Parameters(
            paramLabel = "FILE",
            description = "Files to rewrite, or directories to search for .nix files; standard input to standard output"
                    + " without any.")
    List<Path> files = List.of();

    @Option(
            names = {"-q", "--quiet"},
            description = "Print only errors, not the files rewritten and the summary.")
    boolean quiet;

    @Option(
            names = {"-c", "--check"},
            description = "Write nothing, and fail if a file would change.")
    boolean check;

    abstract String rewrite(String src);

    abstract String verb();

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (files.isEmpty()) {
            String src;
            try {
                src = Sources.read(System.in);
            } catch (IOException e) {
                err.println("error: " + Sources.message(e));
                return 1;
            }
            String result = rewrite("<stdin>", src, err);
            if (result == null) return 1;
            if (check) return result.equals(src) ? 0 : 1;
            out.print(result);
            out.flush();
            return 0;
        }
        long start = System.nanoTime();
        String verb = check ? "would be " + verb() : verb();
        int changed = 0;
        int unchanged = 0;
        int failed = 0;
        for (Path p : Sources.expand(files)) {
            try {
                String src = Sources.read(p);
                String result = rewrite(p.toString(), src, err);
                if (result == null) {
                    failed++;
                } else if (result.equals(src)) {
                    unchanged++;
                } else {
                    if (!check) Files.writeString(p, result, StandardCharsets.UTF_8);
                    if (!quiet) err.println(verb + " " + p);
                    changed++;
                }
            } catch (IOException e) {
                err.println("error: " + Sources.message(e));
                failed++;
            }
        }
        if (!quiet) {
            err.println(files(changed) + " " + verb + ", " + unchanged + " unchanged"
                    + (failed > 0 ? ", " + failed + " failed" : "")
                    + " in " + duration(System.nanoTime() - start));
        }
        return failed > 0 || check && changed > 0 ? 1 : 0;
    }

    private static String duration(long nanos) {
        long millis = nanos / 1_000_000;
        return millis < 1000 ? millis + "ms" : String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static String files(int n) {
        return n + (n == 1 ? " file" : " files");
    }

    private String rewrite(String name, String src, PrintWriter err) {
        try {
            return rewrite(src);
        } catch (ParseException e) {
            err.println(name + ":" + Sources.position(src, e.offset) + ": error: " + e.getMessage());
        } catch (IllegalStateException e) {
            err.println(name + ": error: " + e.getMessage());
        }
        return null;
    }
}
