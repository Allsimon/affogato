package org.affogato.lint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.affogato.parser.Syntax.*;
import org.affogato.parser.Token;
import org.affogato.parser.TokenType;

/**
 * Resolves every variable to its definition, and finds the {@code let} bindings that the code can reach. A variable
 * that no definition binds marks every enclosing {@code with} as used, even a global, because {@code with} shadows
 * globals.
 */
final class Scope {
    enum Kind {
        LET,
        REC,
        ARG,
        FORMAL,
        ALIAS
    }

    static final class Def {
        final String name;
        final Kind kind;
        final Node owner;
        /** Where the name is bound: an {@link Assign}, an {@link AttrNode} of an {@link Inherit}, or a {@link Token}. */
        final List<Object> sites = new ArrayList<>();

        final int start;
        final int end;
        final List<Use> uses = new ArrayList<>();
        boolean live;

        Def(String name, Kind kind, Node owner, int start, int end) {
            this.name = name;
            this.kind = kind;
            this.owner = owner;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * A use counts once one definition of each group is live: the groups are the {@code let} bindings around it. The
     * source of an {@code inherit (from)} is one group of all the names it binds.
     */
    static final class Use {
        final Def def;
        final List<Def[]> groups;
        int pending;

        Use(Def def, List<Def[]> groups) {
            this.def = def;
            this.groups = groups;
        }
    }

    /** {@code depth} counts the groups around the frame: uses below it depend only on the groups past those. */
    private record Frame(Frame parent, Map<String, Def> names, With with, int depth) {}

    final List<Def> defs = new ArrayList<>();
    final Map<Node, List<Def>> owned = new IdentityHashMap<>();
    final Map<Ident, Def> resolved = new IdentityHashMap<>();
    /** The names of {@code inherit} without a source, and the definitions they take. */
    final Map<AttrNode, Def> inherited = new IdentityHashMap<>();

    final List<Ident> undefined = new ArrayList<>();
    final Set<With> usedWiths = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<Node, Node> parents = new IdentityHashMap<>();
    final List<Node> nodes = new ArrayList<>();
    private final Map<Ident, Frame> frames = new IdentityHashMap<>();
    private final List<Use> uses = new ArrayList<>();

    Scope(Node root) {
        walk(root, null, null, List.of());
        liveness();
    }

    /** The definition {@code name} would resolve to where {@code at} is, ignoring {@code with}. */
    Def lookup(Ident at, String name) {
        for (Frame f = frames.get(at); f != null; f = f.parent) {
            Def d = f.names.get(name);
            if (d != null) return d;
        }
        return null;
    }

    /** Whether {@code at} is the global it names; a {@code with} around it is assumed not to shadow globals. */
    boolean isGlobal(Ident at) {
        return !resolved.containsKey(at) && Globals.NAMES.contains(name(at));
    }

    static String name(Ident i) {
        return i.tok().type() == TokenType.OR_KW ? "or" : i.tok().text();
    }

    private void walk(Node n, Node parent, Frame f, List<Def[]> groups) {
        parents.put(n, parent);
        nodes.add(n);
        switch (n) {
            case Ident i -> use(i, name(i), f, groups);
            case SearchPath s -> {
                use(null, "__findFile", f, groups);
                use(null, "__nixPath", f, groups);
            }
            case AttrSet s
            when s.rec() != null -> {
                Frame inner = new Frame(f, new LinkedHashMap<>(), null, groups.size());
                define(inner, s, s.binds(), Kind.REC);
                for (Binding b : s.binds()) binding(b, n, inner, f, groups, null);
            }
            case AttrSet s -> {
                for (Binding b : s.binds()) binding(b, n, f, f, groups, null);
            }
            case Let l -> {
                Frame inner = new Frame(f, new LinkedHashMap<>(), null, groups.size());
                define(inner, l, l.binds(), Kind.LET);
                for (Binding b : l.binds()) binding(b, n, inner, f, groups, inner);
                walk(l.body(), n, inner, groups);
            }
            case Lambda l -> {
                Frame inner = new Frame(f, new LinkedHashMap<>(), null, groups.size());
                if (l.arg() != null) {
                    Kind kind = l.formals() == null ? Kind.ARG : Kind.ALIAS;
                    def(inner, l.arg().text(), kind, l, l.arg().start(), l.arg().end(), l.arg());
                }
                if (l.formals() != null) {
                    for (Formal fm : l.formals().list()) {
                        def(
                                inner,
                                fm.name().text(),
                                Kind.FORMAL,
                                l,
                                fm.name().start(),
                                fm.name().end(),
                                fm);
                    }
                    for (Formal fm : l.formals().list()) {
                        if (fm.fallback() != null) walk(fm.fallback(), n, inner, groups);
                    }
                }
                walk(l.body(), n, inner, groups);
            }
            case With w -> {
                walk(w.scope(), n, f, groups);
                walk(w.body(), n, new Frame(f, Map.of(), w, groups.size()), groups);
            }
            default -> {
                for (Node c : Nodes.children(n)) walk(c, n, f, groups);
            }
        }
    }

    private void define(Frame frame, Node owner, List<Binding> binds, Kind kind) {
        for (Binding b : binds) {
            switch (b) {
                case Assign a -> {
                    AttrNode first = a.path().getFirst().attr();
                    String name = Nodes.staticName(first);
                    if (name != null) def(frame, name, kind, owner, Nodes.attrStart(first), Nodes.attrEnd(first), a);
                }
                case Inherit i -> {
                    for (AttrNode a : i.names()) {
                        String name = Nodes.staticName(a);
                        if (name != null) def(frame, name, kind, owner, Nodes.attrStart(a), Nodes.attrEnd(a), a);
                    }
                }
            }
        }
    }

    private void def(Frame frame, String name, Kind kind, Node owner, int start, int end, Object site) {
        Def d = frame.names.get(name);
        if (d == null) {
            d = new Def(name, kind, owner, start, end);
            frame.names.put(name, d);
            defs.add(d);
            owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(d);
        }
        d.sites.add(site);
    }

    /**
     * Walks a binding of an attribute set or {@code let} whose values see {@code inner}; inherited names come from
     * {@code outer}. In a {@code let}, {@code let} is its frame, and its bindings group the uses below them.
     */
    private void binding(Binding b, Node owner, Frame inner, Frame outer, List<Def[]> groups, Frame let) {
        switch (b) {
            case Assign a -> {
                List<Def[]> g = groups;
                if (let != null) {
                    Def d = let.names.get(Nodes.staticName(a.path().getFirst().attr()));
                    if (d != null) g = with(groups, new Def[] {d});
                }
                for (Sel s : a.path()) attr(s.attr(), owner, inner, groups);
                walk(a.value(), owner, inner, g);
            }
            case Inherit i -> {
                List<Def> bound = new ArrayList<>();
                if (let != null) {
                    for (AttrNode name : i.names()) {
                        Def d = let.names.get(Nodes.staticName(name));
                        if (d != null) bound.add(d);
                    }
                }
                if (i.from() != null) {
                    List<Def[]> g = bound.isEmpty() ? groups : with(groups, bound.toArray(Def[]::new));
                    walk(i.from().expr(), owner, inner, g);
                    for (AttrNode name : i.names()) attr(name, owner, inner, groups);
                    return;
                }
                for (AttrNode name : i.names()) {
                    String s = Nodes.staticName(name);
                    if (s == null) continue;
                    Def d = let != null ? let.names.get(s) : null;
                    Def from = use(null, s, outer, d == null ? groups : with(groups, new Def[] {d}));
                    if (from != null) inherited.put(name, from);
                }
            }
        }
    }

    private void attr(AttrNode a, Node owner, Frame f, List<Def[]> groups) {
        List<Node> exprs = new ArrayList<>();
        Nodes.attr(a, exprs);
        for (Node e : exprs) walk(e, owner, f, groups);
    }

    private static List<Def[]> with(List<Def[]> groups, Def[] group) {
        List<Def[]> out = new ArrayList<>(groups.size() + 1);
        out.addAll(groups);
        out.add(group);
        return out;
    }

    private Def use(Ident ident, String name, Frame f, List<Def[]> groups) {
        if (ident != null) frames.put(ident, f);
        boolean withs = false;
        for (Frame fr = f; fr != null; fr = fr.parent) {
            if (fr.with != null) {
                withs = true;
                continue;
            }
            Def d = fr.names.get(name);
            if (d != null) {
                if (ident != null) resolved.put(ident, d);
                Use u = new Use(d, groups.subList(fr.depth, groups.size()));
                d.uses.add(u);
                uses.add(u);
                return d;
            }
        }
        for (Frame fr = f; fr != null; fr = fr.parent) if (fr.with != null) usedWiths.add(fr.with);
        if (ident != null && !withs && !Globals.NAMES.contains(name)) undefined.add(ident);
        return null;
    }

    /** Marks live the definitions reached by uses whose every group holds a live definition. */
    private void liveness() {
        Map<Def, List<Use>> waiting = new HashMap<>();
        ArrayDeque<Def> queue = new ArrayDeque<>();
        for (Use u : uses) {
            u.pending = u.groups.size();
            if (u.pending == 0) queue.add(u.def);
            for (Def[] g : u.groups) {
                for (Def d : g)
                    waiting.computeIfAbsent(d, k -> new ArrayList<>()).add(u);
            }
        }
        while (!queue.isEmpty()) {
            Def d = queue.poll();
            if (d.live) continue;
            d.live = true;
            for (Use u : waiting.getOrDefault(d, List.of())) {
                if (u.pending == 0) continue;
                for (Def[] g : u.groups) {
                    if (!contains(g, d)) continue;
                    if (satisfiedBefore(g, d)) continue;
                    if (--u.pending == 0) queue.add(u.def);
                }
            }
        }
    }

    private static boolean contains(Def[] group, Def d) {
        for (Def x : group) if (x == d) return true;
        return false;
    }

    /** Whether another definition of {@code group} was already live, so the group was satisfied before {@code d}. */
    private static boolean satisfiedBefore(Def[] group, Def d) {
        for (Def x : group) if (x != d && x.live) return true;
        return false;
    }
}
