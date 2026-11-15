package org.affogato.lsp.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.affogato.lsp.Json;
import org.junit.jupiter.api.Test;

/**
 * Generates BuiltinDocs.java from {@code nix __dump-language}. When it is out of date, the test rewrites it and fails,
 * so that the new version gets committed.
 */
class BuiltinDocsTest {
    /** Tests run from the project root; the Nix build of the tests has no {@code nix} to dump. */
    private static final Path SOURCE = Path.of("lsp/src/main/java/org/affogato/lsp/BuiltinDocs.java");

    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final String INDENT = " ".repeat(16);

    @Test
    void docsAreUpToDate() throws IOException, InterruptedException {
        assumeTrue(Files.exists(SOURCE), "no " + SOURCE + " in " + Path.of("").toAbsolutePath());
        String version = run("nix", "--version");
        assumeTrue(version != null, "no nix");
        Matcher number = Pattern.compile("(\\d+)\\.(\\d+)").matcher(version);
        assertThat(number.find()).as(version).isTrue();
        String manual = "https://nix.dev/manual/nix/" + number.group(1) + "." + number.group(2) + "/";

        String generated = generate(manual, new TreeMap<>(map(Json.parse(run("nix", "__dump-language")))));
        String committed = Files.readString(SOURCE, StandardCharsets.UTF_8);
        if (!committed.equals(generated)) Files.writeString(SOURCE, generated, StandardCharsets.UTF_8);
        assertThat(committed).as(SOURCE + " out of date and rewritten").isEqualTo(generated);
    }

    private static String generate(String manual, Map<String, Object> dump) {
        StringBuilder cases = new StringBuilder();
        for (Map.Entry<String, Object> entry : dump.entrySet())
            cases.append("            case ")
                    .append(literal(entry.getKey()))
                    .append(" -> ")
                    .append(block(markdown(manual, entry.getKey(), map(entry.getValue()))))
                    .append(";\n");
        return """
            package org.affogato.lsp;

            /** Nix's documentation of its builtins, from %s, written by BuiltinDocsTest. */
            final class BuiltinDocs {
                private BuiltinDocs() {}

                /** The documentation of {@code builtins.name} in Markdown, or null. */
                static String markdown(String name) {
                    return switch (name) {
            %s            default -> null;
                    };
                }
            }
            """.formatted(manual, cases);
    }

    private static String markdown(String manual, String name, Map<String, Object> entry) {
        StringJoiner signature = new StringJoiner(" ");
        signature.add("builtins." + name);
        if (entry.get("args") instanceof List<?> args) for (Object arg : args) signature.add((String) arg);
        StringBuilder text = new StringBuilder("```nix\n" + signature + "\n```");
        if (entry.get("type") instanceof String type)
            text.append("\n\n*Type:* ").append(type);
        if (entry.get("experimental-feature") instanceof String feature)
            text.append("\n\n*Experimental feature* [`%s`](%sdevelopment/experimental-features.html#xp-feature-%s)"
                    .formatted(feature, manual, feature));
        if (Boolean.TRUE.equals(entry.get("impure-only"))) text.append("\n\n*Impure only*");
        String doc = LINK.matcher(((String) entry.get("doc")).strip())
                .replaceAll(m -> Matcher.quoteReplacement("](" + link(manual, m.group(1)) + ")"));
        return text.append("\n\n").append(doc).toString();
    }

    /** {@code target} as an absolute URL, the manual's own links resolved from its builtins page. */
    private static String link(String manual, String target) {
        if (target.matches("[a-z]+://.*")) return target;
        if (target.startsWith("#")) target = "@docroot@/language/builtins.md" + target;
        else if (target.startsWith("./")) target = "@docroot@/language/" + target.substring(2);
        else if (target.startsWith("../")) target = "@docroot@/" + target.substring(3);
        return target.replace("@docroot@/", manual).replaceAll("\\.md(?=#|$)", ".html");
    }

    private static String literal(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** {@code s} as a text block laid out as palantir-java-format wants it, so that the build accepts it as is. */
    private static String block(String s) {
        s = s.replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\"");
        StringJoiner lines = new StringJoiner("\n");
        for (String line : s.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            lines.add(trimmed.isEmpty() ? "" : INDENT + trimmed.replace("\t", "\\t"));
        }
        return "\"\"\"\n" + lines + "\\\n" + INDENT + "\"\"\"";
    }

    /** The output of {@code command}, or null when it cannot be started. */
    private static String run(String... command) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            return null;
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
