package org.affogato.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nix's lexical scopes over a {@link SyntaxView}: {@code let}, {@code rec} sets and lambdas, the innermost first. A
 * name that comes from a {@code with} or the builtins resolves to nothing.
 */
public final class Scopes {
    private Scopes() {}

    /** The definition of {@code name} visible from {@code from}: an ATTR_NAME, PARAM or FORMAL, or null. */
    public static <N> N resolve(SyntaxView<N> view, String name, N from) {
        for (N scope = view.parent(from); scope != null; scope = view.parent(scope)) {
            for (N definition : definitions(view, scope)) if (name.equals(name(view, definition))) return definition;
        }
        return null;
    }

    /** Every definition visible from {@code from}, the innermost hiding the outer ones of the same name. */
    public static <N> List<N> visible(SyntaxView<N> view, N from) {
        Map<String, N> visible = new LinkedHashMap<>();
        for (N scope = view.parent(from); scope != null; scope = view.parent(scope)) {
            for (N definition : definitions(view, scope)) visible.putIfAbsent(name(view, definition), definition);
        }
        return List.copyOf(visible.values());
    }

    /**
     * Where the variable an {@code inherit name;} copies is looked up from: the set holding the {@code inherit},
     * whose own bindings do not count; null for any other node, an {@code inherit (e) name;} included.
     */
    public static <N> N inheritedFrom(SyntaxView<N> view, N attrName) {
        N inherit = view.parent(attrName);
        if (inherit == null || view.kind(inherit) != NodeKind.INHERIT) return null;
        for (N child : view.children(inherit)) if (view.kind(child) == NodeKind.PAREN) return null;
        return view.parent(inherit);
    }

    /** The name a definition gives. */
    public static <N> String name(SyntaxView<N> view, N definition) {
        List<N> children = view.children(definition);
        return children.isEmpty() ? view.text(definition) : view.text(children.getFirst());
    }

    /** The definitions a node holds for its children. */
    public static <N> List<N> definitions(SyntaxView<N> view, N scope) {
        NodeKind kind = view.kind(scope);
        List<N> definitions = new ArrayList<>();
        if (kind == NodeKind.LET || kind == NodeKind.ATTR_SET && isRecursive(view, scope)) {
            for (N child : view.children(scope)) {
                if (view.kind(child) == NodeKind.BINDING) {
                    for (N path : view.children(child)) {
                        if (view.kind(path) != NodeKind.ATTR_PATH) continue;
                        List<N> attrs = view.children(path);
                        if (!attrs.isEmpty() && view.kind(attrs.getFirst()) == NodeKind.ATTR_NAME)
                            definitions.add(attrs.getFirst());
                        break;
                    }
                } else if (view.kind(child) == NodeKind.INHERIT) {
                    for (N name : view.children(child))
                        if (view.kind(name) == NodeKind.ATTR_NAME) definitions.add(name);
                }
            }
        } else if (kind == NodeKind.LAMBDA) {
            for (N child : view.children(scope)) {
                if (view.kind(child) == NodeKind.PARAM) definitions.add(child);
                else if (view.kind(child) == NodeKind.FORMALS) {
                    for (N formal : view.children(child))
                        if (view.kind(formal) == NodeKind.FORMAL) definitions.add(formal);
                }
            }
        }
        return definitions;
    }

    /** {@code rec { }} or the legacy {@code let { }}. */
    private static <N> boolean isRecursive(SyntaxView<N> view, N set) {
        List<N> children = view.children(set);
        if (children.isEmpty()) return false;
        Lexeme.Kind first = view.token(children.getFirst());
        return first == Lexeme.Kind.REC || first == Lexeme.Kind.LET;
    }
}
