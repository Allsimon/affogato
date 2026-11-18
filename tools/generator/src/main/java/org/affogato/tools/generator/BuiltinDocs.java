package org.affogato.tools.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.affogato.lsp.Json;

/** lsp/src/main/java/org/affogato/lsp/BuiltinDocs.java, from {@code nix __dump-language} of the nix on the PATH. */
final class BuiltinDocs {
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final String INDENT = " ".repeat(16);

    private BuiltinDocs() {}

    static Generate.Output generate(Path root) throws IOException, InterruptedException {
        String version = run("nix", "--version");
        Matcher number = Pattern.compile("(\\d+)\\.(\\d+)").matcher(version);
        if (!number.find()) throw new IllegalStateException("nix --version printed no version: " + version);
        String manual = "https://nix.dev/manual/nix/" + number.group(1) + "." + number.group(2) + "/";
        SequencedMap<Path, String> files = new LinkedHashMap<>();
        files.put(
                Path.of("lsp", "src", "main", "java", "org", "affogato", "lsp", "BuiltinDocs.java"),
                generate(manual, new TreeMap<>(map(Json.parse(run("nix", "__dump-language"))))));
        return new Generate.Output(files);
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

            /** Nix's documentation of its builtins, from %s, written by tools/generator (builtin-docs). */
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

    private static String run(String... command) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "builtin-docs runs " + String.join(" ", command) + ": no nix on the PATH", e);
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + " failed");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
