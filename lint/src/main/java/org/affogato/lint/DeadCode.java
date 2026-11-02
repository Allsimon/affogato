package org.affogato.lint;

import java.util.ArrayList;
import java.util.List;
import org.affogato.lint.Scope.Def;
import org.affogato.lint.Scope.Kind;
import org.affogato.parser.Syntax.*;
import org.affogato.parser.Token;

/** Unused bindings, as deadnix finds them. Names starting with {@code _} are never reported. */
final class DeadCode {
    private final String src;
    private final Scope scope;
    private final List<Diagnostic> out;
    private final List<Ident> underscores = new ArrayList<>();

    private DeadCode(String src, Scope scope, List<Diagnostic> out) {
        this.src = src;
        this.scope = scope;
        this.out = out;
        for (Node n : scope.nodes) if (n instanceof Ident i && Scope.name(i).equals("_")) underscores.add(i);
    }

    static void check(String src, Scope scope, List<Diagnostic> out) {
        DeadCode d = new DeadCode(src, scope, out);
        for (Def def : scope.defs) if (reported(def)) d.unused(def);
    }

    private static boolean reported(Def def) {
        return def != null && !def.live && !def.name.startsWith("_") && def.kind != Kind.REC;
    }

    private void unused(Def def) {
        switch (def.kind) {
            case LET -> report(Rule.UNUSED_LET_BINDING, def, "unused let binding '" + def.name + "'", removeLet(def));
            case ARG -> {
                Lambda l = (Lambda) def.owner;
                // Renaming to `_` would capture any `_` the body takes from outside.
                List<Edit> fix = underscores.stream().anyMatch(i -> inside(i, l) && !bound(i, l))
                        ? List.of()
                        : List.of(new Edit(def.start, def.end, "_"));
                report(Rule.UNUSED_LAMBDA_ARG, def, "unused lambda argument '" + def.name + "'", fix);
            }
            case FORMAL -> {
                Lambda l = (Lambda) def.owner;
                Def alias = scope.owned.get(l).stream()
                        .filter(d -> d.kind == Kind.ALIAS)
                        .findFirst()
                        .orElse(null);
                if (alias != null && alias.live) return;
                Formal f = (Formal) def.sites.getFirst();
                // Without `...`, removing a formal changes which arguments the function accepts.
                List<Edit> fix = l.formals().ellipsis() == null
                        ? List.of()
                        : List.of(Text.delete(src, f.name().start(), f.comma().end()));
                report(Rule.UNUSED_FORMAL, def, "unused formal argument '" + def.name + "'", fix);
            }
            case ALIAS -> {
                Lambda l = (Lambda) def.owner;
                Token arg = l.arg();
                Formals fs = l.formals();
                Edit fix = arg.start() < fs.open().start()
                        ? new Edit(arg.start(), fs.open().start(), "")
                        : new Edit(fs.close().end(), arg.end(), "");
                report(Rule.UNUSED_AT_PATTERN, def, "unused pattern binding '" + def.name + "'", List.of(fix));
            }
            case REC -> throw new IllegalStateException();
        }
    }

    private boolean inside(Node n, Node outer) {
        for (Node p = n; p != null; p = scope.parents.get(p)) if (p == outer) return true;
        return false;
    }

    /** Whether {@code i} is bound inside {@code l}, not by it or around it. */
    private boolean bound(Ident i, Lambda l) {
        Def d = scope.resolved.get(i);
        return d != null && d.owner != l && inside(d.owner, l);
    }

    private List<Edit> removeLet(Def def) {
        List<Edit> edits = new ArrayList<>();
        for (Object site : def.sites) {
            if (site instanceof Assign a) {
                edits.add(Text.delete(src, Nodes.start(a), a.semi().end()));
                continue;
            }
            AttrNode name = (AttrNode) site;
            Inherit inh = inheritOf((Let) def.owner, name);
            if (inh.names().stream().allMatch(n -> reported(defOf(def.owner, n)))) {
                edits.add(Text.delete(src, inh.inherit().start(), inh.semi().end()));
            } else {
                int start = Nodes.attrStart(name);
                while (Character.isWhitespace(src.charAt(start - 1))) start--;
                edits.add(new Edit(start, Nodes.attrEnd(name), ""));
            }
        }
        return edits;
    }

    private Def defOf(Node owner, AttrNode name) {
        for (Def d : scope.owned.get(owner)) if (d.sites.contains(name)) return d;
        return null;
    }

    private static Inherit inheritOf(Let let, AttrNode name) {
        for (Binding b : let.binds()) {
            if (b instanceof Inherit i && i.names().contains(name)) return i;
        }
        throw new IllegalStateException();
    }

    private void report(Rule rule, Def def, String message, List<Edit> fix) {
        out.add(new Diagnostic(rule, def.start, def.end, message, fix));
    }
}
