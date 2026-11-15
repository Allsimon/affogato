package org.affogato.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
    public record Rules(Predicate<String> shellAttributes, Set<String> shellBuilders) {
        public static final String SHELL_ATTRIBUTES =
                "(pre|post)[A-Z]\\w*|\\w+Phase|shellHook|buildCommand|script|enterShell|enterTest|exec";
        public static final Set<String> SHELL_BUILDERS = Set.of(
                "runCommand",
                "runCommandLocal",
                "runCommandCC",
                "runCommandNoCC",
                "writeShellScript",
                "writeShellScriptBin");
        /** Matches as {@link #SHELL_ATTRIBUTES} does, without the regex engine TeaVM would compile into the playground. */
        public static final Rules DEFAULT = new Rules(Rules::isShellAttribute, SHELL_BUILDERS);

        private static boolean isShellAttribute(String name) {
            return switch (name) {
                case "shellHook", "buildCommand", "script", "enterShell", "enterTest", "exec" -> true;
                default -> {
                    int prefix = name.startsWith("pre") ? 3 : name.startsWith("post") ? 4 : 0;
                    if (prefix > 0
                            && name.length() > prefix
                            && name.charAt(prefix) >= 'A'
                            && name.charAt(prefix) <= 'Z'
                            && allWord(name, prefix, name.length())) yield true;
                    int phase = name.length() - "Phase".length();
                    yield phase > 0 && name.endsWith("Phase") && allWord(name, 0, phase);
                }
            };
        }

        private static boolean allWord(String s, int from, int to) {
            for (int i = from; i < to; i++) if (!isWord(s.charAt(i))) return false;
            return true;
        }
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
            if (rules.shellAttributes().test(last)) return Injection.shell();
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
            String language = node == string ? blockLanguage(comment) : null;
            if (language == null) language = lineLanguage(comment);
            if (language != null) return language;
        }
        return null;
    }

    /** {@code lua} in {@code /* lua *\/}, as {@code /\*\s*([\w+#.-]+)\s*\*\/} matches it. */
    public static String blockLanguage(String comment) {
        if (comment.length() < 4 || !comment.startsWith("/*") || !comment.endsWith("*/")) return null;
        return language(comment, 2, comment.length() - 2);
    }

    /** {@code json} in {@code # language=json}, as {@code #\s*language\s*=\s*([\w+#.-]+)\s*} matches it. */
    private static String lineLanguage(String comment) {
        if (!comment.startsWith("#")) return null;
        int i = skipSpace(comment, 1, comment.length());
        if (!comment.startsWith("language", i)) return null;
        i = skipSpace(comment, i + "language".length(), comment.length());
        if (i == comment.length() || comment.charAt(i) != '=') return null;
        return language(comment, i + 1, comment.length());
    }

    /** The language name between {@code from} and {@code to}, around which only whitespace may stand. */
    private static String language(String s, int from, int to) {
        from = skipSpace(s, from, to);
        while (to > from && isSpace(s.charAt(to - 1))) to--;
        if (from == to) return null;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!isWord(c) && c != '+' && c != '#' && c != '.' && c != '-') return null;
        }
        return s.substring(from, to);
    }

    private static int skipSpace(String s, int i, int to) {
        while (i < to && isSpace(s.charAt(i))) i++;
        return i;
    }

    /** {@code \s} of a regex. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r';
    }

    /** {@code \w} of a regex. */
    private static boolean isWord(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
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
