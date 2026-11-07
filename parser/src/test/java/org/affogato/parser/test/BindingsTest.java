package org.affogato.parser.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.affogato.parser.Bindings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BindingsTest {
    private static String apply(String src, Bindings.Rewrite r) {
        return r == null ? null : src.substring(0, r.start()) + r.text() + src.substring(r.end());
    }

    private static String expand(String src) {
        int at = src.indexOf('|');
        String clean = src.replace("|", "");
        return apply(clean, Bindings.expand(clean, at));
    }

    private static String collapse(String src) {
        int at = src.indexOf('|');
        String clean = src.replace("|", "");
        return apply(clean, Bindings.collapse(clean, at));
    }

    @Test
    void expandsTheFirstAttribute() {
        assertThat(expand("{ a.|b.\"c\" = f x; }")).isEqualTo("{ a = { b.\"c\" = f x; }; }");
    }

    @Test
    void expandsTheInnermostBindingAtTheOffset() {
        assertThat(expand("{ a = { b.|c = 1; }; }")).isEqualTo("{ a = { b = { c = 1; }; }; }");
    }

    @Test
    void collapsesASetOfOneBinding() {
        assertThat(collapse("let |a = { b.c = [ 1 ]; }; in a")).isEqualTo("let a.b.c = [ 1 ]; in a");
    }

    @Test
    void roundTrips() {
        String src = "{ a.b = 1; }";
        String expanded = apply(src, Bindings.expand(src, 3));
        assertThat(apply(expanded, Bindings.collapse(expanded, 3))).isEqualTo(src);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ |a = 1; }", "{ a.|b = ; }"})
    void refusesToExpand(String src) {
        assertThat(expand(src)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{ |a = { b = 1; c = 2; }; }",
                "{ |a = rec { b = 1; }; }",
                "{ |a = { /* why */ b = 1; }; }",
                "{ |a = 1; }"
            })
    void refusesToCollapse(String src) {
        assertThat(collapse(src)).isNull();
    }
}
