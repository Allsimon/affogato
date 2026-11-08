package org.affogato.lsp;

import java.util.List;
import java.util.Map;

/** The evaluated nixpkgs, kept outside this module so that the playground can do without the evaluator. */
public interface Evaluator {
    /**
     * Starts a path that {@link #names} and {@link #describeAll} look up in the arguments the modules of the option sets
     * receive, as a flake's {@code inputs} passed as a special argument, rather than in nixpkgs.
     */
    String MODULE_ARGUMENTS = "<module arguments>";

    /**
     * What an attribute is: its {@code builtins.typeOf}, or {@code derivation}, or {@code option} with the description
     * of its type as its {@code value}, or {@code error} with the message as its {@code value}, or null when there is
     * no such attribute; any other field may be null, and {@code line} and {@code column} count from 1.
     */
    record Attribute(
            String type,
            String value,
            String name,
            String version,
            String description,
            String homepage,
            String doc,
            String defaultValue,
            String example,
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

    /** Runs {@code then}, on any thread, once each of {@code paths} is described, however that ends. */
    void whenDescribed(List<List<String>> paths, Runnable then);

    /** The names at {@code path} in the options of every option set, or null as for {@link #names}. */
    List<String> optionNames(List<String> path);

    /** The option at {@code path} in the first option set that has it, or null as for {@link #names}. */
    Attribute describeOption(List<String> path);

    /** Takes the option sets from now on, each a name and the expression of its options. */
    void configureOptions(Map<String, String> expressions);

    /**
     * The evaluator of a project whose modules take the option sets {@code options}, and {@code pkgs} and {@code lib}
     * from the arguments of the first, sharing this one's evaluation; with no option set, this one's nixpkgs. The same
     * {@code key} gives the same project.
     */
    Evaluator project(String key, Map<String, String> options);

    /**
     * The expression of the options of the devenv project at {@code root} as the devenv CLI gives it, or null when
     * there is none, so far.
     */
    default String devenvOptions(String root) {
        return null;
    }

    /** Evaluates {@code expression} as nixpkgs from now on. */
    void configure(String expression);

    /** Drops the values, as the files they were read from may have changed. */
    void reset();
}
