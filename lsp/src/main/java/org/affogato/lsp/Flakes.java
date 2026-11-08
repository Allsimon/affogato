package org.affogato.lsp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.affogato.parser.NodeKind;
import org.affogato.parser.SyntaxTree;
import org.affogato.parser.SyntaxTree.Node;

/** The schema of a {@code flake.nix}: its attributes, those of its inputs, and its outputs by system. */
final class Flakes {
    static final List<String> FLAKE = List.of("description", "inputs", "outputs", "nixConfig");

    static final List<String> INPUT = List.of("url", "flake", "follows", "inputs");

    // https://nix.dev/manual/nix/latest/command-ref/new-cli/nix3-flake-check
    static final List<String> OUTPUTS = List.of(
            "apps",
            "bundlers",
            "checks",
            "devShells",
            "formatter",
            "hydraJobs",
            "legacyPackages",
            "nixosConfigurations",
            "nixosModules",
            "overlays",
            "packages",
            "templates");

    static final Set<String> PER_SYSTEM =
            Set.of("apps", "bundlers", "checks", "devShells", "formatter", "legacyPackages", "packages");

    static final List<String> SYSTEMS = List.of("x86_64-linux", "aarch64-linux", "x86_64-darwin", "aarch64-darwin");

    private static final String OUT = "<outputs>";

    private Flakes() {}

    static boolean isFlake(String uri) {
        return uri.endsWith("/flake.nix");
    }

    /** The names a binding typed as {@code typed} in {@code set} may take, or null where the schema says nothing. */
    static List<String> names(SyntaxTree tree, Node set, List<String> typed) {
        List<String> path = path(tree, set);
        if (path == null) return null;
        path.addAll(typed.subList(0, typed.size() - 1));
        if (path.isEmpty()) return FLAKE;
        if (path.getFirst().equals("inputs")) {
            if (path.size() == 2 || path.size() == 4 && path.get(2).equals("inputs")) return INPUT;
            return null;
        }
        if (!path.getFirst().equals(OUT)) return null;
        if (path.size() == 1) return OUTPUTS;
        return path.size() == 2 && PER_SYSTEM.contains(path.get(1)) ? SYSTEMS : null;
    }

    /** Where in the schema the bindings of {@code set} are, its outputs starting with {@link #OUT}; else null. */
    private static List<String> path(SyntaxTree tree, Node set) {
        if (set == null || set.kind != NodeKind.ATTR_SET) return null;
        Node parent = set.parent();
        if (parent == null) return null;
        if (parent.kind == null) return new ArrayList<>();
        if (parent.kind == NodeKind.LAMBDA && parts(parent).getLast() == set) {
            Node binding = parent.parent();
            List<String> outer = binding == null || binding.kind != NodeKind.BINDING ? null : names(tree, binding);
            return outer != null && outer.equals(List.of("outputs")) && isRoot(binding.parent())
                    ? new ArrayList<>(List.of(OUT))
                    : null;
        }
        if (parent.kind != NodeKind.BINDING || parts(parent).getLast() != set) return null;
        List<String> outer = path(tree, parent.parent());
        List<String> names = names(tree, parent);
        if (outer == null || names == null) return null;
        outer.addAll(names);
        return outer;
    }

    private static boolean isRoot(Node set) {
        return set != null && set.kind == NodeKind.ATTR_SET && set.parent() != null && set.parent().kind == null;
    }

    /** The {@code outputs} function of a flake, when {@code tree} is one. */
    static Node outputs(SyntaxTree tree) {
        for (Node set : tree.root().children()) {
            if (set.kind != NodeKind.ATTR_SET) continue;
            for (Node binding : set.children()) {
                if (binding.kind != NodeKind.BINDING || !List.of("outputs").equals(names(tree, binding))) continue;
                Node value = parts(binding).getLast();
                if (value.kind == NodeKind.LAMBDA) return value;
            }
        }
        return null;
    }

    /** The names of the inputs the flake declares. */
    static List<String> inputs(SyntaxTree tree) {
        Set<String> inputs = new LinkedHashSet<>();
        for (Node set : tree.root().children()) {
            if (set.kind != NodeKind.ATTR_SET) continue;
            for (Node binding : set.children()) {
                List<String> names = binding.kind == NodeKind.BINDING ? names(tree, binding) : null;
                if (names == null || names.isEmpty() || !names.getFirst().equals("inputs")) continue;
                if (names.size() > 1) inputs.add(names.get(1));
                else if (parts(binding).getLast().kind == NodeKind.ATTR_SET) {
                    for (Node input : parts(binding).getLast().children()) {
                        List<String> inner = input.kind == NodeKind.BINDING ? names(tree, input) : null;
                        if (inner != null && !inner.isEmpty()) inputs.add(inner.getFirst());
                        if (input.kind == NodeKind.INHERIT)
                            for (Node name : input.children())
                                if (name.kind == NodeKind.ATTR_NAME) inputs.add(tree.text(name));
                    }
                }
            }
        }
        return List.copyOf(inputs);
    }

    private static List<Node> parts(Node node) {
        return node.children().stream().filter(c -> c.token == null).toList();
    }

    private static List<String> names(SyntaxTree tree, Node binding) {
        List<Node> parts = parts(binding);
        if (parts.isEmpty() || parts.getFirst().kind != NodeKind.ATTR_PATH) return null;
        List<String> names = new ArrayList<>();
        for (Node segment : parts.getFirst().children()) {
            if (segment.kind == NodeKind.ATTR_NAME) names.add(tree.text(segment));
            else if (segment.kind == NodeKind.STRING) {
                String text = tree.text(segment);
                if (text.contains("\\") || text.contains("${")) return null;
                names.add(text.substring(1, text.length() - 1));
            } else if (segment.kind != null) return null;
        }
        return names;
    }
}
