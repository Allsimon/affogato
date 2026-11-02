package org.affogato.launcher.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.affogato.launcher.Main;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.codegen.docgen.manpage.ManPageGenerator;

/**
 * Generates a man page in docs/cli/ for each command with picocli's ManPageGenerator. When one is out of date, the
 * test rewrites it and fails, so that the new version gets committed.
 */
class CliDocsTest {
    /** Tests run from the project root; the Nix build of the tests includes the generated files. */
    private static final Path DOCS = Path.of("docs");

    private static final Path GENERATED = DOCS.resolve("cli");

    @Test
    void docsAreUpToDate() throws IOException {
        assumeTrue(Files.isDirectory(DOCS), "no docs/ in " + Path.of("").toAbsolutePath());
        Map<String, String> pages = new TreeMap<>();
        collect(Main.commandLine(), pages);
        Files.createDirectories(GENERATED);
        List<String> stale = new ArrayList<>();
        try (Stream<Path> s = Files.list(GENERATED)) {
            for (Path p : s.toList()) {
                if (!pages.containsKey(p.getFileName().toString())) {
                    Files.delete(p);
                    stale.add(p.toString());
                }
            }
        }
        for (var e : pages.entrySet()) {
            Path p = GENERATED.resolve(e.getKey());
            String committed = Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
            if (!committed.equals(e.getValue())) {
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                stale.add(p.toString());
            }
        }
        assertThat(stale).as("out of date and rewritten").isEmpty();
    }

    private static void collect(CommandLine cmd, Map<String, String> pages) {
        StringWriter out = new StringWriter();
        try (PrintWriter pw = new PrintWriter(out)) {
            ManPageGenerator.writeSingleManPage(pw, cmd.getCommandSpec());
        }
        pages.put(cmd.getCommandSpec().qualifiedName("-") + ".adoc", out.toString());
        for (CommandLine sub : cmd.getSubcommands().values()) {
            if (!sub.getCommandSpec().usageMessage().hidden()) collect(sub, pages);
        }
    }
}
