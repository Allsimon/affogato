package org.affogato.lint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.affogato.lint.Builders;
import org.junit.jupiter.api.Test;

/** Checks {@link Builders} against builders.txt, which the flake's lint-builders check compares with nixpkgs. */
class BuildersTest {
    private record Row(String builder, boolean strictDeps, boolean structuredAttrs) {}

    private static List<Row> rows() throws IOException {
        try (InputStream in = BuildersTest.class.getResourceAsStream("/builders.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .map(l -> l.trim().split("\\s+"))
                    .map(w -> new Row(w[0], Boolean.parseBoolean(w[1]), Boolean.parseBoolean(w[2])))
                    .toList();
        }
    }

    private static Set<String> builders(Predicate<Row> filter) throws IOException {
        return rows().stream().filter(filter).map(Row::builder).collect(Collectors.toSet());
    }

    @Test
    void finalAttrs() throws IOException {
        assertThat(Builders.FINAL_ATTRS).isEqualTo(builders(r -> !r.builder().equals("mkDerivation")));
    }

    @Test
    void strictDeps() throws IOException {
        assertThat(Builders.STRICT_DEPS).isEqualTo(builders(Row::strictDeps));
    }

    @Test
    void structuredAttrs() throws IOException {
        assertThat(Builders.STRUCTURED_ATTRS).isEqualTo(builders(Row::structuredAttrs));
    }
}
