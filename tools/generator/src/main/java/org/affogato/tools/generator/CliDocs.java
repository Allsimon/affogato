package org.affogato.tools.generator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.TreeMap;
import org.affogato.cli.Main;
import picocli.CommandLine;
import picocli.codegen.docgen.manpage.ManPageGenerator;

/** docs/cli/: a man page for each command that is not hidden, by picocli's ManPageGenerator. */
final class CliDocs {
    private static final Path FOLDER = Path.of("docs", "cli");

    private CliDocs() {}

    static Generate.Output generate(Path root) {
        TreeMap<Path, String> pages = new TreeMap<>();
        collect(Main.commandLine(), pages);
        return new Generate.Output(pages, FOLDER);
    }

    private static void collect(CommandLine command, SequencedMap<Path, String> pages) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            ManPageGenerator.writeSingleManPage(writer, command.getCommandSpec());
        }
        pages.put(FOLDER.resolve(command.getCommandSpec().qualifiedName("-") + ".adoc"), out.toString());
        for (CommandLine sub : command.getSubcommands().values()) {
            if (!sub.getCommandSpec().usageMessage().hidden()) collect(sub, pages);
        }
    }
}
