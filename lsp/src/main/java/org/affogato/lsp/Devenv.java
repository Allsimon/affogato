package org.affogato.lsp;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.affogato.parser.NodeKind;
import org.affogato.parser.SyntaxTree;
import org.affogato.parser.SyntaxTree.Node;

/** devenv projects: which files are their modules. */
final class Devenv {
    static final String OPTIONS = "devenv";

    private static final Set<String> MODULES = Set.of("devenv.nix", "devenv.local.nix");

    private static final int MAX_IMPORTS = 256;

    private Devenv() {}

    /**
     * The root of the devenv project the file at {@code path} is a module of: the nearest folder above it with a
     * {@code devenv.yaml} or a {@code devenv.lock}, when the file is a {@code devenv.nix} or {@code devenv.local.nix}
     * or reached from those of the root through {@code imports}; else null.
     */
    static String root(String path, Function<String, SyntaxTree> read) {
        File file = new File(path);
        File root = file.getParentFile();
        while (root != null && !new File(root, "devenv.yaml").isFile() && !new File(root, "devenv.lock").isFile())
            root = root.getParentFile();
        if (root == null) return null;
        if (MODULES.contains(file.getName())) return root.getPath();
        Deque<String> pending = new ArrayDeque<>();
        for (String name : MODULES) pending.add(new File(root, name).getPath());
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < MAX_IMPORTS) {
            String module = pending.poll();
            if (!seen.add(module)) continue;
            if (module.equals(file.getPath())) return root.getPath();
            SyntaxTree tree = new File(module).isFile() ? read.apply(module) : null;
            if (tree != null) pending.addAll(imports(tree, module));
        }
        return null;
    }

    /** The files a module lists in its {@code imports}. */
    static List<String> imports(SyntaxTree tree, String file) {
        Node set = Imports.exported(tree);
        if (set == null) return List.of();
        List<String> found = new ArrayList<>();
        for (Node binding : set.children()) {
            if (binding.kind != NodeKind.BINDING) continue;
            List<Node> parts = parts(binding);
            if (parts.size() != 2
                    || parts.getFirst().kind != NodeKind.ATTR_PATH
                    || !tree.text(parts.getFirst()).equals("imports")
                    || parts.getLast().kind != NodeKind.LIST) continue;
            for (Node element : parts(parts.getLast())) {
                String path = element.kind == NodeKind.PATH ? Imports.path(tree, file, element) : null;
                if (path != null) found.add(path);
            }
        }
        return found;
    }

    private static List<Node> parts(Node node) {
        return node.children().stream().filter(c -> c.token == null).toList();
    }
}
