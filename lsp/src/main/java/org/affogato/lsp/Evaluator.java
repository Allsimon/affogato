package org.affogato.lsp;

import java.util.List;

/** The evaluated nixpkgs, kept outside this module so that the playground can do without the evaluator. */
public interface Evaluator {
    /** The attribute names at {@code path} in nixpkgs, or null when they take longer than a request may wait. */
    List<String> names(List<String> path);

    /** Evaluates {@code expression} as nixpkgs from now on. */
    void configure(String expression);

    /** Drops the values, as the files they were read from may have changed. */
    void reset();
}
