package org.affogato.lsp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.affogato.parser.Lexeme;
import org.affogato.parser.NodeKind;
import org.affogato.parser.Scopes;
import org.affogato.parser.SyntaxTree;
import org.affogato.parser.SyntaxTree.Node;
import org.affogato.parser.SyntaxView;

/** The attributes a file exports and where other files select them through {@code import}. */
final class Imports {
    /** An attribute {@code name} of the set {@code file} evaluates to. */
    record Attribute(String file, String name) {}

    private Imports() {}

    /** The set a file evaluates to, under its function arguments and {@code let}s, or null. */
    static Node exported(SyntaxTree tree) {
        Node node = parts(tree.root()).isEmpty() ? null : parts(tree.root()).getFirst();
        while (node != null && node.kind != NodeKind.ATTR_SET) {
            if (node.kind != NodeKind.LAMBDA && node.kind != NodeKind.LET && node.kind != NodeKind.PAREN) return null;
            List<Node> parts = parts(node);
            node = parts.isEmpty() ? null : parts.getLast();
        }
        return node;
    }

    /** The first names of the bindings of the set {@code file} evaluates to that give {@code name}. */
    static List<Node> declarations(SyntaxTree tree, String name) {
        List<Node> found = new ArrayList<>();
        Node set = exported(tree);
        if (set == null) return found;
        for (Node binding : set.children()) {
            Node first = first(binding);
            if (first != null
                    && first.kind == NodeKind.ATTR_NAME
                    && tree.text(first).equals(name)) found.add(first);
        }
        return found;
    }

    /** The attribute of {@code file} the name at {@code token} declares or selects, or null. */
    static Attribute at(SyntaxTree tree, String file, Node token) {
        Node name = token.parent();
        if (name == null || name.kind != NodeKind.ATTR_NAME) return null;
        Node path = name.parent();
        Node parent = path == null ? null : path.parent();
        if (parent == null || path.kind != NodeKind.ATTR_PATH || first(parent) != name) return null;
        if (parent.kind == NodeKind.BINDING) {
            Node set = exported(tree);
            return set != null && parent.parent() == set ? new Attribute(file, tree.text(name)) : null;
        }
        if (parent.kind != NodeKind.SELECT) return null;
        String imported = imported(tree, file, parts(parent).getFirst());
        return imported == null ? null : new Attribute(imported, tree.text(name));
    }

    /** The first names of the selections in {@code tree}, the text of {@code file}, of {@code attribute}. */
    static List<Node> uses(SyntaxTree tree, String file, Attribute attribute) {
        List<Node> uses = new ArrayList<>();
        collect(tree.root(), node -> {
            if (node.kind != NodeKind.SELECT) return;
            Node path = null;
            for (Node child : node.children()) if (child.kind == NodeKind.ATTR_PATH) path = child;
            Node name = path == null ? null : path.children().getFirst();
            if (name == null
                    || name.kind != NodeKind.ATTR_NAME
                    || !tree.text(name).equals(attribute.name())
                    || !attribute.file().equals(imported(tree, file, parts(node).getFirst()))) return;
            uses.add(name);
        });
        return uses;
    }

    /**
     * The file {@code expr} imports, as {@code import ./a.nix}, also applied to arguments, in parentheses, or through
     * a name bound to it; else null.
     */
    static String imported(SyntaxTree tree, String file, Node expr) {
        for (int depth = 0; expr != null && depth < 16; depth++) {
            switch (expr.kind) {
                case PAREN -> expr = parts(expr).isEmpty() ? null : parts(expr).getFirst();
                case APPLY -> {
                    List<Node> parts = parts(expr);
                    Node function = parts.getFirst();
                    if (function.kind == NodeKind.VAR
                            && tree.text(function).equals("import")
                            && Scopes.resolve(tree.view(), "import", function) == null)
                        return path(tree, file, parts.get(1));
                    expr = function;
                }
                case VAR -> {
                    SyntaxView<Node> view = tree.view();
                    Node definition = Scopes.resolve(view, tree.text(expr), expr);
                    Node path = definition == null ? null : definition.parent();
                    Node binding = path == null ? null : path.parent();
                    if (definition == null
                            || definition.kind != NodeKind.ATTR_NAME
                            || path.kind != NodeKind.ATTR_PATH
                            || binding == null
                            || binding.kind != NodeKind.BINDING
                            || parts(path).size() != 1) return null;
                    expr = parts(binding).getLast();
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static String path(SyntaxTree tree, String file, Node node) {
        List<Node> children = node.children();
        if (node.kind != NodeKind.PATH || children.size() != 1 || children.getFirst().token != Lexeme.Kind.PATH)
            return null;
        String text = tree.text(node);
        if (text.startsWith("~")) return null;
        File target = text.startsWith("/") ? new File(text) : new File(new File(file).getParentFile(), text);
        String resolved = normalize(target.getPath());
        return new File(resolved).isDirectory() ? resolved + "/default.nix" : resolved;
    }

    private static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else parts.add(part);
        }
        return "/" + String.join("/", parts);
    }

    private static Node first(Node node) {
        for (Node child : node.children())
            if (child.kind == NodeKind.ATTR_PATH)
                return child.children().isEmpty() ? null : child.children().getFirst();
        return null;
    }

    private static List<Node> parts(Node node) {
        return node.children().stream().filter(c -> c.token == null).toList();
    }

    private static void collect(Node node, java.util.function.Consumer<Node> visit) {
        visit.accept(node);
        for (Node child : node.children()) collect(child, visit);
    }
}
