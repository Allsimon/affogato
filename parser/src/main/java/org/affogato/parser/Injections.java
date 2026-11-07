package org.affogato.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which language a string is written in, by where it stands:
 *
 * <ul>
 *   <li>shell in the attributes and the builders {@link Rules} names, and in the {@code text} of {@code
 *       writeShellApplication};
 *   <li>the language of a {@code writers.*} function, such as Python for {@code writePython3};
 *   <li>the language of a file name: the one given to {@code writeText}, {@code writeScript} or {@code
 *       builtins.toFile}, or the quoted name around a {@code text}, as in {@code environment.etc."nginx.json".text};
 *   <li>whatever a comment before the string names: {@code /* lua *\/ ''...''}, or {@code # language=json} above it.
 * </ul>
 */
public final class Injections {
    /** What an editor injects: {@link Kind#SHELL}, a language a comment names, or that of a file name. */
    public record Injection(Kind kind, String value) {
        public enum Kind {
            SHELL,
            NAMED,
            FILE
        }

        static Injection shell() {
            return new Injection(Kind.SHELL, "shell");
        }
    }

    /** Which attributes and builders get shell. */
    public record Rules(Pattern shellAttributes, Set<String> shellBuilders) {
        public static final String SHELL_ATTRIBUTES =
                "(pre|post)[A-Z]\\w*|\\w+Phase|shellHook|buildCommand|script|enterShell|enterTest|exec";
        public static final Set<String> SHELL_BUILDERS = Set.of(
                "runCommand",
                "runCommandLocal",
                "runCommandCC",
                "runCommandNoCC",
                "writeShellScript",
                "writeShellScriptBin");
        public static final Rules DEFAULT = new Rules(Pattern.compile(SHELL_ATTRIBUTES), SHELL_BUILDERS);
    }

    /** The builders whose first argument is a file name and whose last one is its content. */
    private static final Set<String> NAMED_BUILDERS =
            Set.of("writeText", "writeTextDir", "writeScript", "writeScriptBin", "toFile");
    /** Module system functions that pass their last argument through, as in {@code lib.mkAfter ''...''}. */
    private static final Set<String> WRAPPERS =
            Set.of("mkBefore", "mkAfter", "mkForce", "mkDefault", "mkOverride", "mkOrder");
    /** The extension of the language of each of nixpkgs' {@code writers}, with and without {@code Bin}. */
    private static final Map<String, String> WRITERS = new HashMap<>();

    static {
        Map.ofEntries(
                        Map.entry("Bash", "sh"),
                        Map.entry("Dash", "sh"),
                        Map.entry("Fish", "fish"),
                        Map.entry("Python3", "py"),
                        Map.entry("PyPy3", "py"),
                        Map.entry("Perl", "pl"),
                        Map.entry("Ruby", "rb"),
                        Map.entry("JS", "js"),
                        Map.entry("Lua", "lua"),
                        Map.entry("Rust", "rs"),
                        Map.entry("Haskell", "hs"),
                        Map.entry("Nu", "nu"),
                        Map.entry("Nim", "nim"),
                        Map.entry("Guile", "scm"),
                        Map.entry("Babashka", "clj"))
                .forEach((name, extension) -> {
                    WRITERS.put("write" + name, extension);
                    WRITERS.put("write" + name + "Bin", extension);
                });
    }

    /** {@code lua} in {@code /* lua *\/}. */
    public static final Pattern BLOCK_LANGUAGE = Pattern.compile("/\\*\\s*([\\w+#.-]+)\\s*\\*/");
    /** {@code json} in {@code # language=json}. */
    public static final Pattern LINE_LANGUAGE = Pattern.compile("#\\s*language\\s*=\\s*([\\w+#.-]+)\\s*");

    private Injections() {}

    /** What to inject into {@code string}, a STRING or IND_STRING, or null. */
    public static <N> Injection of(SyntaxView<N> view, N string, Rules rules) {
        String named = commentedLanguage(view, string);
        if (named != null) return new Injection(Injection.Kind.NAMED, named);
        N parent = view.parent(string);
        NodeKind kind = parent == null ? null : view.kind(parent);
        if (kind == NodeKind.BINDING) {
            List<String> path = names(view, parent);
            String last = path.isEmpty() ? null : path.getLast();
            if (last == null) return null;
            if (rules.shellAttributes().matcher(last).matches()) return Injection.shell();
            return last.equals("text") ? textLanguage(view, parent) : null;
        }
        if (kind == NodeKind.APPLY) {
            List<N> parts = view.children(parent);
            if (parts.getLast() != string) return null;
            String function = functionName(view, parts.getFirst());
            if (function == null) return null;
            if (WRAPPERS.contains(function)) return of(view, parent, rules);
            if (rules.shellBuilders().contains(function)) return Injection.shell();
            if (WRITERS.containsKey(function))
                return new Injection(Injection.Kind.FILE, "script." + WRITERS.get(function));
            if (NAMED_BUILDERS.contains(function) && parts.size() > 2) {
                String name = constant(view, parts.get(1));
                return name == null || !name.contains(".") ? null : new Injection(Injection.Kind.FILE, name);
            }
        }
        return null;
    }

    /**
     * The language of a {@code text} binding: that of the nearest quoted file name in its path or around it, or shell
     * in the argument of {@code writeShellApplication}.
     */
    private static <N> Injection textLanguage(SyntaxView<N> view, N binding) {
        for (N node = binding; node != null; node = enclosingBinding(view, node)) {
            List<String> names = names(view, node);
            for (int i = names.size() - (node == binding ? 2 : 1); i >= 0; i--) {
                String name = names.get(i);
                if (name != null && name.contains("."))
                    return new Injection(Injection.Kind.FILE, name.substring(name.lastIndexOf('/') + 1));
            }
        }
        N set = view.parent(binding);
        N apply = set == null ? null : view.parent(set);
        if (set != null
                && view.kind(set) == NodeKind.ATTR_SET
                && apply != null
                && view.kind(apply) == NodeKind.APPLY
                && "writeShellApplication"
                        .equals(functionName(view, view.children(apply).getFirst()))) return Injection.shell();
        return null;
    }

    /** {@code lua} in {@code /* lua *\/ ''...''}, or {@code json} after {@code # language=json} before the string or its binding. */
    private static <N> String commentedLanguage(SyntaxView<N> view, N string) {
        N parent = view.parent(string);
        List<N> nodes =
                parent != null && view.kind(parent) == NodeKind.BINDING ? List.of(string, parent) : List.of(string);
        for (N node : nodes) {
            String comment = view.commentBefore(node);
            if (comment == null) continue;
            Matcher block = BLOCK_LANGUAGE.matcher(comment);
            if (node == string && block.matches()) return block.group(1);
            Matcher line = LINE_LANGUAGE.matcher(comment);
            if (line.matches()) return line.group(1);
        }
        return null;
    }

    private static <N> N enclosingBinding(SyntaxView<N> view, N binding) {
        N set = view.parent(binding);
        N parent = set == null ? null : view.parent(set);
        return set != null
                        && view.kind(set) == NodeKind.ATTR_SET
                        && parent != null
                        && view.kind(parent) == NodeKind.BINDING
                ? parent
                : null;
    }

    /** The names of a binding's path, null for one that is not constant. */
    private static <N> List<String> names(SyntaxView<N> view, N binding) {
        List<String> names = new ArrayList<>();
        for (N path : view.children(binding)) {
            if (view.kind(path) != NodeKind.ATTR_PATH) continue;
            for (N attr : view.children(path)) {
                if (view.kind(attr) == NodeKind.ATTR_NAME) names.add(view.text(attr));
                else if (view.token(attr) != Lexeme.Kind.DOT) names.add(constant(view, attr));
            }
            break;
        }
        return names;
    }

    /** The text of a {@code "..."} string without interpolation, else null. */
    static <N> String constant(SyntaxView<N> view, N node) {
        if (view.kind(node) != NodeKind.STRING) return null;
        StringBuilder text = new StringBuilder();
        for (N child : view.children(node)) {
            Lexeme.Kind kind = view.token(child);
            if (kind == Lexeme.Kind.STR_TEXT) text.append(view.text(child));
            else if (kind != Lexeme.Kind.STR_OPEN && kind != Lexeme.Kind.STR_CLOSE) return null;
        }
        return text.toString();
    }

    /** The name a function is called by: {@code f} in {@code f} and {@code pkgs.f}. */
    private static <N> String functionName(SyntaxView<N> view, N function) {
        NodeKind kind = view.kind(function);
        if (kind == NodeKind.VAR) return view.text(function);
        if (kind != NodeKind.SELECT) return null;
        for (N path : view.children(function)) {
            if (view.kind(path) != NodeKind.ATTR_PATH) continue;
            List<N> attrs = view.children(path);
            N last = attrs.isEmpty() ? null : attrs.getLast();
            return last != null && view.kind(last) == NodeKind.ATTR_NAME ? view.text(last) : null;
        }
        return null;
    }
}
