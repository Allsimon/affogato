package org.affogato.lsp;

import java.util.List;

/** The evaluated nixpkgs, kept outside this module so that the playground can do without the evaluator. */
public interface Evaluator {
    /**
     * What an attribute is: its {@code builtins.typeOf}, or {@code derivation}, or {@code error} with the message as
     * its {@code value}, or null when there is no such attribute; any other field may be null, and {@code line} and
     * {@code column} count from 1.
     */
    record Attribute(
            String type,
            String value,
            String name,
            String version,
            String description,
            String homepage,
            String doc,
            String file,
            int line,
            int column) {}

    /** The attribute names at {@code path} in nixpkgs, or null when they take longer than a request may wait. */
    List<String> names(List<String> path);

    /** The attribute at {@code path} in nixpkgs, or null when it takes longer than a request may wait. */
    default Attribute describe(List<String> path) {
        return describeAll(List.of(path)).getFirst();
    }

    /** The attribute at each path, null for those not evaluated within the time a request may wait for all. */
    List<Attribute> describeAll(List<List<String>> paths);

    /** Evaluates {@code expression} as nixpkgs from now on. */
    void configure(String expression);

    /** Drops the values, as the files they were read from may have changed. */
    void reset();
}
