package org.affogato.lint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.affogato.parser.ParseException;
import org.affogato.parser.Parser;
import org.affogato.parser.Syntax.Node;

/** Finds dead code, simplifiable patterns and semantic errors in Nix code, and fixes them. */
public final class Linter {
    /** The attribute names of {@code builtins}. */
    public static final Set<String> BUILTINS = Globals.BUILTINS;

    private static final int MAX_ROUNDS = 10;

    private Linter() {}

    public static List<Diagnostic> lint(String src) {
        return lint(src, EnumSet.allOf(Rule.class));
    }

    /**
     * The findings of {@code rules}, by position, but for those {@link Suppressions} switch off. Code that does not parse
     * gives a single {@link Rule#SYNTAX_ERROR}, whatever the rules.
     */
    public static List<Diagnostic> lint(String src, Set<Rule> rules) {
        Node root;
        try {
            root = Parser.parseSyntax(src);
            Parser.parse(src);
        } catch (ParseException e) {
            int at = Math.min(e.offset, src.length());
            return List.of(new Diagnostic(Rule.SYNTAX_ERROR, at, at, e.getMessage(), List.of()));
        }
        Scope scope = new Scope(root);
        List<Diagnostic> out = new ArrayList<>();
        DeadCode.check(src, scope, out);
        Patterns.check(src, scope, out);
        Semantics.check(src, scope, out);
        Packaging.check(src, scope, out);
        Suppressions suppressions = Suppressions.of(src);
        return out.stream()
                .filter(d -> rules.contains(d.rule()) && !suppressions.suppresses(d))
                .sorted(Comparator.comparingInt(Diagnostic::start).thenComparing(Diagnostic::rule))
                .toList();
    }

    public static String fix(String src) {
        return fix(src, EnumSet.allOf(Rule.class));
    }

    /**
     * Applies the fixes of {@code rules} until none is left, in rounds: a round skips a fix that overlaps an earlier
     * one, and the next round finds it again, along with what the fixes before it made possible.
     *
     * @throws ParseException if {@code src} is not valid Nix
     */
    public static String fix(String src, Set<Rule> rules) {
        Parser.parse(src);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Diagnostic> found = lint(src, rules);
            if (!found.isEmpty() && found.getFirst().rule() == Rule.SYNTAX_ERROR) {
                throw new IllegalStateException(
                        "fixes made invalid code: " + found.getFirst().message() + "\n" + src);
            }
            String fixed = apply(src, found);
            if (fixed.equals(src)) return src;
            src = fixed;
        }
        return src;
    }

    private static String apply(String src, List<Diagnostic> found) {
        List<Edit> accepted = new ArrayList<>();
        for (Diagnostic d : found) {
            if (d.fix().isEmpty() || d.fix().stream().anyMatch(e -> overlaps(e, accepted))) continue;
            accepted.addAll(d.fix());
        }
        accepted.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder sb = new StringBuilder(src);
        for (Edit e : accepted) sb.replace(e.start(), e.end(), e.replacement());
        return sb.toString();
    }

    private static boolean overlaps(Edit e, List<Edit> accepted) {
        for (Edit a : accepted) {
            if (e.start() < a.end() && a.start() < e.end()) return true;
            if (e.start() == a.start() || e.end() == a.end()) return true;
        }
        return false;
    }
}
