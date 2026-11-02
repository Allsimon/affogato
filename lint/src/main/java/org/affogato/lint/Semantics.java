package org.affogato.lint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.affogato.lint.Scope.Def;
import org.affogato.lint.Scope.Kind;
import org.affogato.parser.Syntax.*;
import org.affogato.parser.TokenType;

/** Checks that need name resolution, as nixd makes them. */
final class Semantics {
    private Semantics() {}

    static void check(String src, Scope scope, List<Diagnostic> out) {
        for (Ident i : scope.undefined) {
            out.add(diag(Rule.UNDEFINED_VARIABLE, i, "undefined variable '" + Scope.name(i) + "'", List.of()));
        }
        Set<String> tested = tested(scope);
        for (Node n : scope.nodes) {
            switch (n) {
                case Select s -> unknownBuiltin(s, scope, tested, out);
                case Ident i -> prefixed(i, scope, out);
                case AttrSet s
                when s.rec() != null && s.rec().type() == TokenType.REC -> {
                    boolean used =
                            scope.owned.getOrDefault(s, List.of()).stream().anyMatch(d -> !d.uses.isEmpty());
                    if (!used) {
                        out.add(new Diagnostic(
                                Rule.EXTRA_REC,
                                s.rec().start(),
                                s.rec().end(),
                                "'rec' is not needed: no attribute refers to another",
                                List.of(Text.delete(
                                        src, s.rec().start(), s.rec().end()))));
                    }
                }
                case With w
                when !scope.usedWiths.contains(w) -> {
                    out.add(new Diagnostic(
                            Rule.EXTRA_WITH,
                            w.with().start(),
                            w.semi().end(),
                            "unused 'with': no variable comes from it",
                            List.of(new Edit(w.with().start(), Nodes.start(w.body()), ""))));
                }
                default -> {}
            }
        }
        for (Def d : scope.defs) {
            if (d.kind == Kind.REC || !Globals.UNPREFIXED.contains(d.name) || inheritedFrom(d)) continue;
            out.add(new Diagnostic(
                    Rule.SHADOWED_BUILTIN, d.start, d.end, "'" + d.name + "' shadows a builtin", List.of()));
        }
    }

    /** Names that the file tests with {@code builtins ? name}, so it can use them when Nix has them. */
    private static Set<String> tested(Scope scope) {
        Set<String> names = new HashSet<>();
        for (Node n : scope.nodes) {
            if (n instanceof HasAttr h
                    && h.target() instanceof Ident b
                    && Scope.name(b).equals("builtins")
                    && h.path().size() == 1)
                names.add(Nodes.staticName(h.path().getFirst().attr()));
        }
        return names;
    }

    private static void unknownBuiltin(Select s, Scope scope, Set<String> tested, List<Diagnostic> out) {
        if (s.fallback() != null
                || !(s.target() instanceof Ident b)
                || !Scope.name(b).equals("builtins")
                || !scope.isGlobal(b)) return;
        AttrNode attr = s.path().getFirst().attr();
        String name = Nodes.staticName(attr);
        if (name == null || Globals.BUILTINS.contains(name) || tested.contains(name)) return;
        out.add(new Diagnostic(
                Rule.UNKNOWN_BUILTIN,
                Nodes.start(b),
                Nodes.attrEnd(attr),
                "unknown builtin 'builtins." + name + "'",
                List.of()));
    }

    private static void prefixed(Ident i, Scope scope, List<Diagnostic> out) {
        String name = Scope.name(i);
        if (!name.startsWith("__") || name.equals("__curPos") || !scope.isGlobal(i)) return;
        String builtin = name.substring(2);
        List<Edit> fix = scope.lookup(i, "builtins") == null
                ? List.of(new Edit(Nodes.start(i), Nodes.end(i), "builtins." + builtin))
                : List.of();
        out.add(diag(Rule.PREFIXED_BUILTIN, i, "'" + name + "' is 'builtins." + builtin + "'", fix));
    }

    /** {@code inherit (lib) map;} rebinds a name to what is most likely the same function. */
    private static boolean inheritedFrom(Def d) {
        return d.kind == Kind.LET
                && d.sites.getFirst() instanceof AttrNode a
                && ((Let) d.owner)
                        .binds().stream()
                                .anyMatch(b -> b instanceof Inherit inh
                                        && inh.from() != null
                                        && inh.names().contains(a));
    }

    private static Diagnostic diag(Rule rule, Ident i, String message, List<Edit> fix) {
        return new Diagnostic(rule, Nodes.start(i), Nodes.end(i), message, fix);
    }
}
