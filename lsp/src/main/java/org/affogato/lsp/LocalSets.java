package org.affogato.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.affogato.lint.Linter;
import org.affogato.parser.Lexeme;
import org.affogato.parser.NodeKind;
import org.affogato.parser.Scopes;
import org.affogato.parser.SyntaxTree;
import org.affogato.parser.SyntaxTree.Node;
import org.affogato.parser.SyntaxView;

/** The attributes of the set literals a file binds to a name, followed through selections and other names. */
final class LocalSets {
    private static final int DEPTH = 32;

    /** A binding or {@code inherit} of a set, its attribute path read from {@code index}. */
    private record Member(Node node, int index) {}

    private final SyntaxTree tree;
    private final SyntaxView<Node> view;

    LocalSets(SyntaxTree tree) {
        this.tree = tree;
        this.view = tree.view();
    }

    /**
     * The attributes of the set {@code definition}, the name a {@code let} or {@code rec} set binds, selects with
     * {@code path}: each name with the node of its first definition; null when no set literal is found.
     */
    Map<String, Node> attributes(Node definition, List<String> path) {
        List<Member> members = members(definition, 0);
        for (String name : path) {
            if (members == null) return null;
            members = step(members, name, 0);
        }
        return names(members);
    }

    /**
     * The attributes of the set literal {@code expr} is, or names through a name the file binds and a selection, as
     * in {@code with x.a;}; null when no set literal is found.
     */
    Map<String, Node> attributes(Node expr) {
        return names(value(expr, 0));
    }

    /** The {@code with}s whose body holds {@code node}, the innermost first. */
    static List<Node> withs(Node node) {
        List<Node> withs = new ArrayList<>();
        for (Node child = node, parent = node.parent(); parent != null; child = parent, parent = parent.parent()) {
            if (parent.kind != NodeKind.WITH) continue;
            List<Node> parts =
                    parent.children().stream().filter(c -> c.kind != null).toList();
            if (parts.size() >= 2 && parts.getLast() == child) withs.add(parent);
        }
        return withs;
    }

    /** The set a {@code with} brings into scope. */
    static Node scope(Node with) {
        return firstNode(with.children(), null);
    }

    private Map<String, Node> names(List<Member> members) {
        if (members == null) return null;
        Map<String, Node> names = new LinkedHashMap<>();
        for (Member m : members) {
            if (m.node().kind == NodeKind.INHERIT) {
                for (Node child : m.node().children()) {
                    String name = name(child);
                    if (name != null) names.putIfAbsent(name, child);
                }
            } else {
                Node segment = segments(m.node()).get(m.index());
                String name = name(segment);
                if (name != null) names.putIfAbsent(name, segment);
            }
        }
        return names;
    }

    private List<Member> members(Node definition, int depth) {
        if (definition == null || definition.kind != NodeKind.ATTR_NAME || depth > DEPTH) return null;
        Node parent = definition.parent();
        Node container = parent == null ? null : parent.parent();
        if (container != null && parent.kind == NodeKind.ATTR_PATH) container = container.parent();
        if (container == null) return null;
        return step(bindings(container), Scopes.name(view, definition), depth + 1);
    }

    private List<Member> step(List<Member> members, String name, int depth) {
        if (depth > DEPTH) return null;
        List<Member> out = null;
        for (Member m : members) {
            List<Member> found = null;
            if (m.node().kind == NodeKind.INHERIT) {
                Node from = null;
                Node source = null;
                for (Node child : m.node().children()) {
                    if (child.kind == NodeKind.PAREN) source = child;
                    else if (name.equals(name(child))) from = child;
                }
                if (from != null && source != null) {
                    List<Member> set = value(source, depth + 1);
                    found = set == null ? null : step(set, name, depth + 1);
                } else if (from != null && from.kind == NodeKind.ATTR_NAME)
                    found = members(Scopes.resolve(view, name, Scopes.inheritedFrom(view, from)), depth + 1);
            } else {
                List<Node> segments = segments(m.node());
                if (m.index() >= segments.size() || !name.equals(name(segments.get(m.index())))) continue;
                found = m.index() + 1 < segments.size()
                        ? List.of(new Member(m.node(), m.index() + 1))
                        : value(value(m.node()), depth + 1);
            }
            if (found == null) continue;
            if (out == null) out = new ArrayList<>();
            out.addAll(found);
        }
        return out;
    }

    private List<Member> value(Node expr, int depth) {
        while (expr != null && expr.kind == NodeKind.PAREN) expr = firstNode(expr.children(), null);
        if (expr == null || depth > DEPTH) return null;
        if (expr.kind == NodeKind.ATTR_SET) return bindings(expr);
        if (expr.kind == NodeKind.VAR) {
            String name = tree.text(expr);
            Node definition = Scopes.resolve(view, name, expr);
            if (definition != null) return members(definition, depth + 1);
            if (Linter.GLOBALS.contains(name)) return null;
            for (Node with : withs(expr)) {
                List<Member> scope = value(scope(with), depth + 1);
                if (scope != null && names(scope).containsKey(name)) return step(scope, name, depth + 1);
            }
            return null;
        }
        if (expr.kind != NodeKind.SELECT) return null;
        List<Member> members = value(expr.children().getFirst(), depth + 1);
        for (Node child : expr.children()) {
            if (child.token == Lexeme.Kind.OR_KW) return null;
            if (child.kind != NodeKind.ATTR_PATH) continue;
            for (Node segment : child.children()) {
                if (segment.kind == null) continue;
                String name = name(segment);
                if (name == null || members == null) return null;
                members = step(members, name, depth + 1);
            }
        }
        return members;
    }

    private static List<Member> bindings(Node container) {
        List<Member> members = new ArrayList<>();
        for (Node child : container.children())
            if (child.kind == NodeKind.BINDING || child.kind == NodeKind.INHERIT) members.add(new Member(child, 0));
        return members;
    }

    private static List<Node> segments(Node binding) {
        for (Node child : binding.children())
            if (child.kind == NodeKind.ATTR_PATH)
                return child.children().stream().filter(c -> c.kind != null).toList();
        return List.of();
    }

    private static Node value(Node binding) {
        List<Node> children = binding.children();
        Node path = firstNode(children, null);
        return path == null ? null : firstNode(children, path);
    }

    private static Node firstNode(List<Node> children, Node after) {
        boolean past = after == null;
        for (Node child : children) {
            if (child == after) past = true;
            else if (past && child.kind != null) return child;
        }
        return null;
    }

    /** The name an attribute path segment gives, unless it is computed. */
    String name(Node segment) {
        if (segment.kind == NodeKind.ATTR_NAME) return tree.text(segment);
        if (segment.kind != NodeKind.STRING) return null;
        String text = tree.text(segment);
        if (text.length() < 2 || text.contains("\\") || text.contains("${")) return null;
        return text.substring(1, text.length() - 1);
    }
}
