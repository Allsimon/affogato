package org.affogato.parser.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.affogato.parser.Quotes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuotesTest {
    static Stream<Arguments> toggles() {
        return Stream.of(
                Arguments.of("\"a\\nb ${x} c\"", "''a\nb ${x} c''"),
                Arguments.of("\"it''s \\${not} $${raw}\"", "''it'''s ''${not} $${raw}''"),
                Arguments.of("\"tab\\t quote\\\" end'\"", "''tab\t quote\" end''\\'''"),
                Arguments.of("\"\\nfirst\"", "''''\\nfirst''"),
                Arguments.of(
                        "''\n  mkdir $out\n  cp ${./a} \"$out\"\n''", "\"mkdir $out\\ncp ${./a} \\\"$out\\\"\\n\""),
                Arguments.of("''a ''${b} ''\\n $${c}''", "\"a \\${b} \\n $${c}\""),
                Arguments.of("\"${ \"x\" + y }$\"", "''${ \"x\" + y }$''"),
                Arguments.of("\"a$${b}\"", "''a$${b}''"));
    }

    @ParameterizedTest
    @MethodSource
    void toggles(String from, String to) {
        assertThat(Quotes.toggle(from)).isEqualTo(to);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"a\\nb\"", "\"x ${y}\"", "\"\"", "\"it''s\""})
    void roundTrips(String literal) {
        assertThat(Quotes.toggle(Quotes.toggle(literal))).isEqualTo(literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"  indented\"", "\"a\" + \"b\"", "1", "\"unterminated"})
    void refusesWhatWouldChangeTheValue(String source) {
        assertThat(Quotes.toggle(source)).isNull();
    }
}
