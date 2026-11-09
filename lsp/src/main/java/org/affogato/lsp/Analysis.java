package org.affogato.lsp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.affogato.format.Formatter;
import org.affogato.lint.Linter;
import org.affogato.parser.Bindings;
import org.affogato.parser.Lexeme;
import org.affogato.parser.NodeKind;
import org.affogato.parser.Quotes;
import org.affogato.parser.Scopes;
import org.affogato.parser.SyntaxTree;
import org.affogato.parser.SyntaxTree.Node;
import org.affogato.parser.SyntaxView;
import org.affogato.parser.Tokenizer;

/**
 * What the language server answers about one file, in offsets and plain records rather than the protocol's JSON, so
 * that an editor can ask without speaking the protocol.
 */
public final class Analysis {
    /** The files around the one analysed: to follow {@code import}s and to find the modules of a devenv project. */
    public interface Workspace {
        /** The tree of the file at {@code path}, as the editor has it when open, or null when unreadable. */
        SyntaxTree tree(String path);

        /** The {@code .nix} files of the workspace by path. */
        Map<String, SyntaxTree> files();
    }

    public enum Kind {
        VARIABLE,
        ATTRIBUTE,
        PROPERTY,
        BUILTIN,
        CONSTANT,
        KEYWORD,
        SNIPPET
    }

    /**
     * A completion: {@code snippet} the body to insert, with the protocol's {@code ${1:name}} placeholders, for a
     * {@link Kind#SNIPPET}; {@code path} the evaluated attribute or option it names, for {@link #details}.
     */
    public record Completion(
            String label, Kind kind, String detail, String snippet, List<String> path, boolean option) {
        public static Completion of(String label, Kind kind) {
            return new Completion(label, kind, null, null, null, false);
        }
    }

    /**
     * The completions at an offset; {@code incomplete} while evaluated or cut short, and {@code evaluated} when they
     * come from the evaluator, which a client should ask again for as more is typed.
     */
    public record Completions(List<Completion> items, boolean incomplete, boolean evaluated) {}

    /** A range of the file at {@code path}, or of the analysed one when {@code path} is null. */
    public record Span(String path, int start, int end) {}

    /** A position in the file at {@code path}, its line and column counted from 0. */
    public record Location(String path, int line, int column) {}

    /** Markdown about the name between {@code start} and {@code end}. */
    public record Hover(int start, int end, String markdown) {}

    /** A label to show at {@code offset}. */
    public record Hint(int offset, String label) {}

    /** The hints of a range, and the paths whose evaluation would change them, empty when none is under way. */
    public record Hints(List<Hint> hints, List<List<String>> pending) {}

    /** A link from a range: a file path when {@code file}, else a URL. */
    public record Link(int start, int end, String target, boolean file) {}

    /** A change of the text between {@code start} and {@code end}, described by {@code title}. */
    public record Rewrite(String title, int start, int end, String text) {}

    static final List<String> KEYWORDS =
            List.of("assert", "else", "if", "in", "inherit", "let", "or", "rec", "then", "with");

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*");

    private static final Map<String, String> SNIPPETS = snippets(
            "let … in", "let\n  ${1:name} = ${2:value};\nin\n${0}",
            "if … then … else", "if ${1:condition} then ${2} else ${0}",
            "with …;", "with ${1:pkgs}; ${0}",
            "inherit (…) …;", "inherit (${1:set}) ${0};",
            "assert …;", "assert ${1:condition}; ${0}",
            "rec { … }", "rec {\n  ${0}\n}");

    private static final int GROUP_NAMES = 50;

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");

    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\\]+");

    private static final int MAX_ITEMS = 1000;

    private final String path;
    private final String text;
    private final SyntaxTree tree;
    private final Evaluator evaluator;
    private final Workspace workspace;
    private String root;
    private boolean rootFound;
    private Evaluator scoped;

    /**
     * Analyses {@code text}, parsed as {@code tree}, of the file at {@code path}, null when it is not a file; with
     * {@code evaluator} for what takes evaluating, when not null.
     */
    public Analysis(String path, String text, SyntaxTree tree, Evaluator evaluator, Workspace workspace) {
        this.path = path;
        this.text = text;
        this.tree = tree;
        this.evaluator = evaluator;
        this.workspace = workspace;
    }

    /** The root of the devenv project the file at {@code path} is a module of, or null. */
    public static String devenvRoot(String path, Workspace workspace) {
        return path == null ? null : Devenv.root(path, workspace::tree);
    }

    /** {@code evaluator}, or that of the devenv project the file at {@code path} is a module of. */
    public static Evaluator scoped(Evaluator evaluator, String path, Workspace workspace) {
        String root = evaluator == null ? null : devenvRoot(path, workspace);
        return root == null ? evaluator : project(evaluator, root);
    }

    private static Evaluator project(Evaluator evaluator, String root) {
        String options = evaluator.devenvOptions(root);
        return evaluator.project("devenv:" + root, options == null ? Map.of() : Map.of(Devenv.OPTIONS, options));
    }

    private String root() {
        if (!rootFound) {
            root = evaluator == null ? null : devenvRoot(path, workspace);
            rootFound = true;
        }
        return root;
    }

    private boolean module() {
        return root() != null;
    }

    /** The evaluator of this file: that of its devenv project when it is one of its modules, or null. */
    public Evaluator evaluator() {
        if (scoped == null && evaluator != null) scoped = root() == null ? evaluator : project(evaluator, root());
        return scoped;
    }

    // Completion

    /**
     * The attributes of {@code builtins} after {@code builtins.}, of nixpkgs after {@code pkgs.} and {@code lib.} unless
     * bound otherwise than as a parameter, else the names in scope, globals and keywords, and the snippets if asked.
     */
    public Completions completion(int offset, boolean snippets) {
        Evaluator evaluator = evaluator();
        SyntaxView<Node> view = tree.view();
        List<Completion> items = new ArrayList<>();
        Map<String, Node> local = new HashMap<>();
        List<Completion> scope = new ArrayList<>();
        for (Node definition : Scopes.visible(view, tree.at(offset))) {
            String name = Scopes.name(view, definition);
            local.putIfAbsent(name, definition);
            scope.add(Completion.of(name, Kind.VARIABLE));
        }
        if (path != null && Flakes.isFlake(path)) {
            List<Completion> flake = flakeCompletion(offset, local);
            if (flake != null) return new Completions(flake, false, false);
        }
        boolean module = evaluator != null && module();
        List<String> option = evaluator == null ? null : typedOption(text, tree, offset, module);
        if (evaluator != null && option == null) {
            String closed = closed(text, offset);
            if (closed != null) option = typedOption(closed, SyntaxTree.parse(closed), offset, module);
        }
        if (option != null) {
            List<String> parent = option.subList(0, option.size() - 1);
            Completions found = attributes(evaluator.optionNames(parent), parent, option.getLast());
            for (Completion item : found.items())
                items.add(new Completion(item.label(), Kind.PROPERTY, null, null, item.path(), true));
            return new Completions(items, found.incomplete(), true);
        }
        List<String> selection = selection(text, offset);
        if (selection != null) {
            Map<String, Node> attributes = new LocalSets(tree)
                    .attributes(local.get(selection.getFirst()), selection.subList(1, selection.size() - 1));
            if (attributes != null) {
                for (String name : attributes.keySet()) items.add(Completion.of(name, Kind.ATTRIBUTE));
                return new Completions(items, false, false);
            }
        }
        if (evaluator != null && selection != null && isModuleArgument(tree, local.get(selection.getFirst()), module)) {
            List<String> path = new ArrayList<>(selection.subList(0, selection.size() - 1));
            path.addFirst(Evaluator.MODULE_ARGUMENTS);
            return attributes(evaluator.names(path), path, selection.getLast());
        }
        if (evaluator != null && selection != null) {
            String root = selection.getFirst();
            Node definition = local.get(root);
            if ((root.equals("pkgs") || root.equals("lib"))
                    && (definition == null
                            || definition.kind == NodeKind.FORMAL
                            || definition.kind == NodeKind.PARAM)) {
                List<String> path = new ArrayList<>(selection.subList(1, selection.size() - 1));
                if (root.equals("lib")) path.addFirst("lib");
                return attributes(evaluator.names(path), path, selection.getLast());
            }
        }
        if (!local.containsKey("builtins") && afterBuiltins(text, offset)) {
            for (String name : new TreeSet<>(Linter.BUILTINS)) items.add(builtin(name));
            return new Completions(items, false, false);
        }
        items.addAll(scope);
        for (String name : new TreeSet<>(Linter.GLOBALS)) if (!local.containsKey(name)) items.add(builtin(name));
        for (String keyword : KEYWORDS) items.add(Completion.of(keyword, Kind.KEYWORD));
        if (snippets)
            SNIPPETS.forEach((label, body) -> items.add(new Completion(label, Kind.SNIPPET, null, body, null, false)));

        Set<String> seen = new HashSet<>(local.keySet());
        seen.addAll(Linter.GLOBALS);
        LocalSets sets = new LocalSets(tree);
        for (Node with : LocalSets.withs(tree.at(Math.max(offset - 1, 0)))) {
            Map<String, Node> names = sets.attributes(with.children().stream()
                    .filter(c -> c.token == null)
                    .findFirst()
                    .orElseThrow());
            if (names != null)
                for (String name : names.keySet()) if (seen.add(name)) items.add(Completion.of(name, Kind.VARIABLE));
        }
        List<List<String>> scopes = evaluator == null ? List.of() : withScopes(tree, tree.at(Math.max(offset - 1, 0)));
        if (scopes.isEmpty()) return new Completions(items, false, false);
        int start = offset;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, offset);
        boolean incomplete = false;
        for (List<String> path : scopes) {
            Completions found = attributes(evaluator.names(path), path, prefix);
            incomplete |= found.incomplete();
            for (Completion item : found.items()) if (seen.add(item.label())) items.add(item);
        }
        return new Completions(items, incomplete, true);
    }

    /** The evaluated attribute or option a completion names, or null when not evaluated in time or not one. */
    public Evaluator.Attribute details(Completion completion) {
        Evaluator evaluator = evaluator();
        if (evaluator == null || completion.path() == null) return null;
        return completion.option()
                ? evaluator.describeOption(completion.path())
                : evaluator.describe(completion.path());
    }

    /**
     * In a flake: the names its schema has for a binding typed there, and the inputs as formals of {@code outputs}
     * or after {@code inputs.}, the name {@code outputs} binds its argument to; else null.
     */
    private List<Completion> flakeCompletion(int offset, Map<String, Node> local) {
        Typed typed = typedBinding(text, tree, offset);
        List<String> names = typed == null ? null : Flakes.names(tree, typed.set(), typed.names());
        if (names != null) {
            List<Completion> items = new ArrayList<>();
            for (String name : names) items.add(Completion.of(name, Kind.PROPERTY));
            return items;
        }
        Node outputs = Flakes.outputs(tree);
        if (outputs == null) return null;
        List<String> inputs = new ArrayList<>(List.of("self"));
        inputs.addAll(Flakes.inputs(tree));
        Node formals = tree.at(Math.max(offset - 1, 0));
        for (int i = 0; i < 2 && formals != null && formals.kind != NodeKind.FORMALS; i++) formals = formals.parent();
        if (formals != null && formals.kind == NodeKind.FORMALS && formals.parent() == outputs) {
            Set<String> present = new HashSet<>();
            for (Node formal : formals.children())
                if (formal.kind == NodeKind.FORMAL && formal.end() < offset)
                    present.add(Scopes.name(tree.view(), formal));
            List<Completion> items = new ArrayList<>();
            for (String input : inputs) if (!present.contains(input)) items.add(Completion.of(input, Kind.VARIABLE));
            return items;
        }
        List<String> selection = selection(text, offset);
        Node definition = selection == null || selection.size() != 2 ? null : local.get(selection.getFirst());
        if (definition == null
                || definition.kind != NodeKind.PARAM && definition.kind != NodeKind.FORMAL
                || !selection.getFirst().equals("inputs") && definition.kind == NodeKind.FORMAL) return null;
        Node lambda = definition.parent();
        if (lambda != null && lambda.kind == NodeKind.FORMALS) lambda = lambda.parent();
        if (lambda != outputs) return null;
        List<Completion> items = new ArrayList<>();
        for (String input : inputs) items.add(Completion.of(input, Kind.PROPERTY));
        return items;
    }

    private static Completion builtin(String name) {
        return new Completion(
                name, CONSTANTS.contains(name) ? Kind.CONSTANT : Kind.BUILTIN, "builtin", null, null, false);
    }

    private static Map<String, String> snippets(String... labelsAndBodies) {
        Map<String, String> snippets = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndBodies.length; i += 2) snippets.put(labelsAndBodies[i], labelsAndBodies[i + 1]);
        return snippets;
    }

    /** The names starting with {@code prefix}, incomplete while being evaluated or when there are too many. */
    private static Completions attributes(List<String> names, List<String> path, String prefix) {
        List<Completion> items = new ArrayList<>();
        boolean incomplete = names == null;
        if (names != null)
            for (String name : names) {
                if (!name.startsWith(prefix)) continue;
                if (items.size() == MAX_ITEMS) {
                    incomplete = true;
                    break;
                }
                List<String> item = new ArrayList<>(path);
                item.add(name);
                items.add(new Completion(name, Kind.ATTRIBUTE, null, null, item, false));
            }
        return new Completions(items, incomplete, true);
    }

    // Hover and evaluated locations

    /** What the evaluator knows of the nixpkgs attribute or the option at {@code offset}, or null. */
    public Hover hover(int offset) {
        Evaluator evaluator = evaluator();
        boolean module = evaluator != null && module();
        Selected selected = selected(evaluator, module, tree, offset);
        Evaluator.Attribute a;
        boolean option = selected == null;
        if (selected != null) {
            a = evaluator.describe(selected.path());
        } else {
            selected = boundOption(evaluator, tree, offset, module);
            if (selected == null) return null;
            a = evaluator.describeOption(selected.path());
        }
        StringBuilder text = new StringBuilder();
        if (a != null && a.type() == null && option) {
            List<String> names = evaluator.optionNames(selected.path());
            if (names == null || names.isEmpty()) return null;
            text.append(optionPath(selected.path())).append("\n\n");
            for (int i = 0; i < Math.min(names.size(), GROUP_NAMES); i++)
                text.append(i == 0 ? "" : ", ").append(optionPath(List.of(names.get(i))));
            if (names.size() > GROUP_NAMES)
                text.append(" and ").append(names.size() - GROUP_NAMES).append(" more");
            return new Hover(selected.name().start(), selected.name().end(), text.toString());
        }
        if (a == null || a.type() == null) return null;
        if (a.type().equals("option")) {
            text.append(optionPath(selected.path()));
            if (a.value() != null) text.append(" ").append(a.value());
            if (a.description() != null) text.append("\n\n").append(a.description());
            if (a.defaultValue() != null) text.append("\n\n*Default:* ").append(code(a.defaultValue()));
            if (a.example() != null) text.append("\n\n*Example:* ").append(code(a.example()));
        } else if (a.type().equals("derivation")) {
            text.append("`")
                    .append(a.name() != null ? a.name() : selected.path().getLast())
                    .append("`");
            if (a.version() != null) text.append(" ").append(a.version());
            if (a.description() != null) text.append("\n\n").append(a.description());
            if (a.homepage() != null) text.append("\n\n").append(a.homepage());
        } else if (a.type().equals("error")) {
            text.append("```\n").append(a.value()).append("\n```");
        } else {
            text.append("`").append(a.type()).append("`");
            if (a.value() != null) text.append(" `").append(a.value()).append("`");
            if (a.doc() != null) text.append("\n\n").append(a.doc());
        }
        return new Hover(selected.name().start(), selected.name().end(), text.toString());
    }

    /** Where the nixpkgs attribute or the option at {@code offset} is defined, or null. */
    public Location declaration(int offset) {
        Evaluator evaluator = evaluator();
        boolean module = evaluator != null && module();
        Selected selected = selected(evaluator, module, tree, offset);
        Evaluator.Attribute a;
        if (selected != null) {
            a = evaluator.describe(selected.path());
        } else {
            selected = boundOption(evaluator, tree, offset, module);
            if (selected == null) return null;
            a = evaluator.describeOption(selected.path());
        }
        if (a == null || a.file() == null || !a.file().startsWith("/")) return null;
        return new Location(a.file(), a.line() - 1, Math.max(a.column() - 1, 0));
    }

    /**
     * Whether the nixpkgs attribute or the option at {@code offset} is still being evaluated, so that
     * {@link #declaration} and {@link #hover} answer once it is.
     */
    public boolean evaluating(int offset) {
        Evaluator evaluator = evaluator();
        if (evaluator == null) return false;
        boolean module = module();
        Selected selected = selected(evaluator, module, tree, offset);
        if (selected != null) return evaluator.describe(selected.path()) == null;
        Selected option = boundOption(evaluator, tree, offset, module);
        return option != null && evaluator.describeOption(option.path()) == null;
    }

    private static String optionPath(List<String> path) {
        StringBuilder text = new StringBuilder("`");
        for (String name : path) {
            if (text.length() > 1) text.append('.');
            boolean plain = !name.isEmpty()
                    && isNameStart(name.charAt(0))
                    && name.chars().allMatch(c -> isNameChar((char) c));
            text.append(plain ? name : Json.write(name));
        }
        return text.append("`").toString();
    }

    private static String code(String text) {
        return text.contains("\n") ? "\n```nix\n" + text.strip() + "\n```" : "`" + text + "`";
    }

    /** The binding name at {@code offset} in a module, and the path of the option it sets, or null. */
    private static Selected boundOption(Evaluator evaluator, SyntaxTree tree, int offset, boolean module) {
        if (evaluator == null) return null;
        Node name = tree.at(offset).parent();
        if (name == null || name.kind != NodeKind.ATTR_NAME) return null;
        Node attrPath = name.parent();
        if (attrPath == null || attrPath.kind != NodeKind.ATTR_PATH) return null;
        Node binding = attrPath.parent();
        if (binding == null || binding.kind != NodeKind.BINDING) return null;
        List<String> path = optionPrefix(tree, binding.parent(), module);
        if (path == null) return null;
        for (Node child : attrPath.children()) {
            if (child.token != null) continue;
            String text = attrName(tree, child);
            if (text == null) return null;
            path.add(text);
            if (child == name) return new Selected(name, options(path));
        }
        return null;
    }

    /** The names of a binding typed up to {@code offset}, and the set it is typed in. */
    private record Typed(List<String> names, Node set) {}

    private static Typed typedBinding(String text, SyntaxTree tree, int offset) {
        List<String> typed = new ArrayList<>();
        int end = offset;
        while (true) {
            int start = end;
            while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
            if (start < end && !isNameStart(text.charAt(start))) return null;
            typed.addFirst(text.substring(start, end));
            if (start == 0 || text.charAt(start - 1) != '.') {
                end = start;
                break;
            }
            end = start - 1;
        }
        int before = end;
        while (before > 0 && Character.isWhitespace(text.charAt(before - 1))) before--;
        if (before == 0 || text.charAt(before - 1) != '{' && text.charAt(before - 1) != ';') return null;
        Node set = tree.at(before - 1);
        while (set != null && set.kind != NodeKind.ATTR_SET) {
            if (set.kind == NodeKind.LET) return null;
            set = set.parent();
        }
        return set == null ? null : new Typed(typed, set);
    }

    /** The text up to {@code offset} with the brackets still open there closed, or null when none is. */
    private static String closed(String text, int offset) {
        String prefix = text.substring(0, offset);
        StringBuilder open = new StringBuilder();
        for (Lexeme l : Tokenizer.tokenize(prefix)) {
            switch (l.kind()) {
                case LBRACE -> open.append('{');
                case DOLLAR_CURLY -> open.append('$');
                case LBRACK -> open.append('[');
                case LPAREN -> open.append('(');
                case RBRACE, RBRACK, RPAREN -> {
                    if (!open.isEmpty()) open.setLength(open.length() - 1);
                }
                default -> {}
            }
        }
        if (open.isEmpty()) return null;
        StringBuilder closed = new StringBuilder(prefix).append(' ');
        for (int i = open.length() - 1; i >= 0; i--) {
            closed.append(
                    switch (open.charAt(i)) {
                        case '[' -> ']';
                        case '(' -> ')';
                        default -> '}';
                    });
            if (i > 0 && open.charAt(i - 1) == '{') closed.append(';');
        }
        return closed.toString();
    }

    /** The option path being typed at {@code offset} as a binding name in a module, its last name partial, or null. */
    private static List<String> typedOption(String text, SyntaxTree tree, int offset, boolean module) {
        Typed typed = typedBinding(text, tree, offset);
        if (typed == null) return null;
        List<String> path = optionPrefix(tree, typed.set(), module);
        if (path == null) return null;
        path.addAll(typed.names());
        path = options(path);
        return path == null || path.isEmpty() ? null : path;
    }

    /**
     * The option path that the bindings of {@code set} start from, when it is the body of a module, a function of
     * {@code config}, {@code lib}, {@code pkgs} or {@code modulesPath}, or the file itself or its function when
     * {@code module}, or a set bound in one, possibly through
     * parentheses, lists, function arguments as of {@code mkIf} or {@code mkMerge}, and {@code let}; else null.
     */
    private static List<String> optionPrefix(SyntaxTree tree, Node set, boolean module) {
        if (set == null || set.kind != NodeKind.ATTR_SET || tree.text(set).startsWith("rec")) return null;
        List<String> path = new ArrayList<>();
        Node node = set;
        while (true) {
            Node parent = node.parent();
            if (parent == null || parent.kind == null) return module ? path : null;
            List<Node> parts =
                    parent.children().stream().filter(c -> c.token == null).toList();
            switch (parent.kind) {
                case BINDING -> {
                    if (parts.getLast() != node || parts.getFirst().kind != NodeKind.ATTR_PATH) return null;
                    List<String> names = new ArrayList<>();
                    for (Node child : parts.getFirst().children()) {
                        if (child.token != null) continue;
                        String text = attrName(tree, child);
                        if (text == null) return null;
                        names.add(text);
                    }
                    path.addAll(0, names);
                    node = parent.parent();
                    if (node == null
                            || node.kind != NodeKind.ATTR_SET
                            || tree.text(node).startsWith("rec")) return null;
                }
                case PAREN, LIST -> node = parent;
                case APPLY, LET -> {
                    if (parts.getFirst() == node && parent.kind == NodeKind.APPLY
                            || parent.kind == NodeKind.LET && parts.getLast() != node) return null;
                    node = parent;
                }
                case LAMBDA -> {
                    return parts.getLast() == node && isModule(tree, parent, module) ? path : null;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    /** The name of an attribute path element, a name or a string without interpolation nor escapes, or null. */
    private static String attrName(SyntaxTree tree, Node element) {
        if (element.kind == NodeKind.ATTR_NAME) return tree.text(element);
        if (element.kind != NodeKind.STRING) return null;
        List<Node> parts = element.children();
        if (parts.size() == 2) return "";
        if (parts.size() != 3 || parts.get(1).token != Lexeme.Kind.STR_TEXT) return null;
        String text = tree.text(parts.get(1));
        return text.indexOf('\\') < 0 ? text : null;
    }

    /** {@code path} without a leading {@code config}, or null where it declares or imports rather than sets. */
    private static List<String> options(List<String> path) {
        if (!path.isEmpty() && path.getFirst().equals("config")) path = new ArrayList<>(path.subList(1, path.size()));
        if (!path.isEmpty()
                && Set.of("options", "imports", "disabledModules", "_module").contains(path.getFirst())) return null;
        return path;
    }

    /** A name in a selection from nixpkgs, and the path from nixpkgs to it. */
    private record Selected(Node name, List<String> path) {}

    /**
     * The attribute name at {@code offset} in {@code pkgs.a.b} or {@code lib.a.b}, or the variable there that a
     * {@code with} of nixpkgs brings into scope, or null elsewhere.
     */
    private static Selected selected(Evaluator evaluator, boolean module, SyntaxTree tree, int offset) {
        if (evaluator == null) return null;
        Node token = tree.at(offset);
        Node name = token.parent();
        if (name != null && name.kind == NodeKind.VAR) return first(evaluator, name, candidates(tree, name));
        if (name == null || name.kind != NodeKind.ATTR_NAME) return null;
        Node attrPath = name.parent();
        if (attrPath == null || attrPath.kind != NodeKind.ATTR_PATH) return null;
        List<Node> children = attrPath.children();
        int after = 0;
        for (int i = children.size() - 1; children.get(i) != name; i--) if (children.get(i).token == null) after++;
        List<List<String>> candidates = new ArrayList<>();
        for (List<String> path : selectionCandidates(evaluator, module, tree, attrPath.parent(), new ArrayList<>()))
            candidates.add(path.subList(0, path.size() - after));
        return candidates.size() == 1 ? new Selected(name, candidates.getFirst()) : first(evaluator, name, candidates);
    }

    /** The first of {@code candidates} that nixpkgs has, or null when there is none or one is still evaluated. */
    private static Selected first(Evaluator evaluator, Node name, List<List<String>> candidates) {
        if (candidates.isEmpty()) return null;
        List<Evaluator.Attribute> found = evaluator.describeAll(candidates);
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i) == null) return null;
            if (found.get(i).type() != null) return new Selected(name, candidates.get(i));
        }
        return null;
    }

    /**
     * The paths from nixpkgs that {@code select} may stand for: its own when it starts from {@code pkgs} or {@code lib},
     * else those of a variable a {@code with} of nixpkgs brings into scope, as {@code unstable.hello} under
     * {@code with pkgs;}, followed by its attribute names.
     */
    private static List<List<String>> selectionCandidates(
            Evaluator evaluator, boolean module, SyntaxTree tree, Node select, List<List<String>> pending) {
        List<String> own = nixpkgsPath(tree, select);
        if (own != null) return List.of(own);
        if (select == null || select.kind != NodeKind.SELECT) return List.of();
        Node base = select.children().getFirst();
        Node attrPath = attrPath(select);
        if (base.kind != NodeKind.VAR || attrPath == null) return List.of();
        List<String> names = new ArrayList<>();
        for (Node child : attrPath.children()) {
            if (child.token != null) continue;
            String text = child.kind == NodeKind.DYNAMIC_ATTR
                    ? computedName(evaluator, module, tree, child, pending)
                    : attrName(tree, child);
            if (text == null) return List.of();
            names.add(text);
        }
        List<List<String>> bases = isModuleArgument(tree, Scopes.resolve(tree.view(), tree.text(base), base), module)
                ? List.of(List.of(Evaluator.MODULE_ARGUMENTS, tree.text(base)))
                : candidates(tree, base);
        List<List<String>> candidates = new ArrayList<>();
        for (List<String> path : bases) {
            List<String> full = new ArrayList<>(path);
            full.addAll(names);
            candidates.add(full);
        }
        return candidates;
    }

    /**
     * The string a {@code ${...}} name evaluates to, when it is a selection from nixpkgs, as
     * {@code ${pkgs.stdenv.hostPlatform.system}}, and a plain one; else null, and its paths in {@code pending} while
     * they are evaluated.
     */
    private static String computedName(
            Evaluator evaluator, boolean module, SyntaxTree tree, Node dynamic, List<List<String>> pending) {
        Node expr = dynamic.children().stream()
                .filter(c -> c.token == null)
                .findFirst()
                .orElse(null);
        List<List<String>> candidates = selectionCandidates(evaluator, module, tree, expr, pending);
        if (candidates.isEmpty()) return null;
        for (Evaluator.Attribute a : evaluator.describeAll(candidates)) {
            if (a == null) {
                pending.addAll(candidates);
                return null;
            }
            if (a.type() == null) continue;
            String v = a.value();
            boolean plain = "string".equals(a.type())
                    && v != null
                    && v.length() >= 2
                    && v.startsWith("\"")
                    && v.endsWith("\"")
                    && v.indexOf('\\') < 0;
            return plain ? v.substring(1, v.length() - 1) : null;
        }
        return null;
    }

    /**
     * Whether {@code definition} is an argument of a module's function that the evaluator looks up among the arguments
     * its modules receive: one other than {@code pkgs} and {@code lib}, taken from nixpkgs, and {@code config} and
     * {@code options}.
     */
    private static boolean isModuleArgument(SyntaxTree tree, Node definition, boolean module) {
        if (definition == null || definition.kind != NodeKind.FORMAL) return false;
        if (Set.of("pkgs", "lib", "config", "options").contains(Scopes.name(tree.view(), definition))) return false;
        Node lambda = definition.parent() == null ? null : definition.parent().parent();
        return lambda != null && lambda.kind == NodeKind.LAMBDA && isModule(tree, lambda, module);
    }

    /**
     * Whether {@code lambda} is the file, a function of {@code config}, {@code lib}, {@code pkgs} or {@code modulesPath},
     * or of anything when {@code module}.
     */
    private static boolean isModule(SyntaxTree tree, Node lambda, boolean module) {
        if (lambda.parent() == null || lambda.parent().kind != null) return false;
        if (module) return true;
        for (Node formal : Scopes.definitions(tree.view(), lambda))
            if (Set.of("config", "lib", "pkgs", "modulesPath").contains(Scopes.name(tree.view(), formal))) return true;
        return false;
    }

    /**
     * The paths from nixpkgs where the variable {@code var} may come from, those of the enclosing {@code with}s of
     * nixpkgs from the innermost out, up to the first {@code with} of something else; none when it is bound otherwise.
     */
    private static List<List<String>> candidates(SyntaxTree tree, Node var) {
        String name = tree.text(var);
        if (Scopes.resolve(tree.view(), name, var) != null || Linter.GLOBALS.contains(name)) return List.of();
        List<List<String>> candidates = new ArrayList<>();
        for (List<String> scope : withScopes(tree, var)) {
            List<String> path = new ArrayList<>(scope);
            path.add(name);
            candidates.add(path);
        }
        return candidates;
    }

    /** The nixpkgs paths of the {@code with}s whose body holds {@code node}, from the innermost out, up to another. */
    private static List<List<String>> withScopes(SyntaxTree tree, Node node) {
        List<List<String>> scopes = new ArrayList<>();
        for (Node child = node, parent = node.parent(); parent != null; child = parent, parent = parent.parent()) {
            if (parent.kind != NodeKind.WITH) continue;
            List<Node> parts =
                    parent.children().stream().filter(c -> c.token == null).toList();
            if (parts.size() < 2 || parts.getLast() != child) continue;
            List<String> path = nixpkgsPath(tree, parts.getFirst());
            if (path == null) break;
            scopes.add(path);
        }
        return scopes;
    }

    private static Node attrPath(Node select) {
        for (Node child : select.children()) if (child.kind == NodeKind.ATTR_PATH) return child;
        return null;
    }

    /**
     * The path from nixpkgs of {@code expr} when it is {@code pkgs}, {@code lib}, or a selection from them such as
     * {@code pkgs.a.b}, unless the file binds them otherwise than as a parameter; else null.
     */
    private static List<String> nixpkgsPath(SyntaxTree tree, Node expr) {
        if (expr == null) return null;
        if (expr.kind == NodeKind.PAREN)
            for (Node child : expr.children()) if (child.token == null) return nixpkgsPath(tree, child);
        if (expr.kind == NodeKind.VAR) {
            String root = tree.text(expr);
            if (!root.equals("pkgs") && !root.equals("lib")) return null;
            Node definition = Scopes.resolve(tree.view(), root, expr);
            if (definition != null && definition.kind != NodeKind.FORMAL && definition.kind != NodeKind.PARAM)
                return null;
            return root.equals("lib") ? new ArrayList<>(List.of("lib")) : new ArrayList<>();
        }
        if (expr.kind != NodeKind.SELECT) return null;
        Node base = expr.children().getFirst();
        if (base.kind != NodeKind.VAR) return null;
        List<String> path = nixpkgsPath(tree, base);
        if (path == null) return null;
        Node attrPath = attrPath(expr);
        if (attrPath == null) return null;
        for (Node child : attrPath.children()) {
            if (child.token != null) continue;
            String text = attrName(tree, child);
            if (text == null) return null;
            path.add(text);
        }
        return path;
    }

    // Inlay hints

    /** The version of each package selected from nixpkgs between {@code start} and {@code end}, after its selection. */
    public Hints inlayHints(int start, int end) {
        Evaluator evaluator = evaluator();
        if (evaluator == null) return new Hints(List.of(), List.of());
        boolean module = module();
        List<Integer> ends = new ArrayList<>();
        List<List<String>> pending = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<List<String>> paths = new ArrayList<>();
        collect(tree.root(), node -> {
            if (node.end() < start || node.start() > end) return;
            if (node.kind == NodeKind.SELECT) {
                List<List<String>> candidates = selectionCandidates(evaluator, module, tree, node, pending).stream()
                        .filter(path -> !path.isEmpty() && !path.getFirst().equals("lib"))
                        .toList();
                if (candidates.isEmpty()) return;
                ends.add(attrPath(node).end());
                counts.add(candidates.size());
                paths.addAll(candidates);
            } else if (node.kind == NodeKind.VAR && !(node.parent() != null && node.parent().kind == NodeKind.SELECT)) {
                List<List<String>> candidates = candidates(tree, node);
                if (candidates.isEmpty()) return;
                ends.add(node.end());
                counts.add(candidates.size());
                paths.addAll(candidates);
            }
        });
        List<Evaluator.Attribute> attributes = evaluator.describeAll(paths);
        boolean waiting = attributes.contains(null) || !pending.isEmpty();
        pending.addAll(paths);
        List<Hint> hints = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < ends.size(); i++) {
            Evaluator.Attribute found = null;
            for (int j = next; j < next + counts.get(i); j++) {
                Evaluator.Attribute a = attributes.get(j);
                if (a == null) break;
                if (a.type() != null) {
                    found = a;
                    break;
                }
            }
            next += counts.get(i);
            if (found == null || !"derivation".equals(found.type()) || found.version() == null) continue;
            hints.add(new Hint(ends.get(i), found.version()));
        }
        return new Hints(hints, waiting ? pending : List.of());
    }

    // Navigation

    /**
     * Where the name at {@code offset} is defined: the file a path names, a binding of this file, an attribute of a
     * local set or of a {@code with} of one, or an attribute of a file selected through {@code import}; else null.
     */
    public Span definition(int offset) {
        Node node = tree.at(offset).parent();
        String target = node == null || path == null ? null : pathTarget(tree, node, path);
        if (target != null) return new Span(target, 0, 0);
        Node definition = definitionAt(tree, offset);
        if (definition != null) return span(null, nameToken(definition));
        Node attribute = selectedAttribute(tree, offset);
        if (attribute == null) attribute = withAttribute(tree, offset);
        if (attribute != null) return span(null, attribute);
        Imports.Attribute imported = importedAt(offset);
        if (imported == null || imported.file().equals(path)) return null;
        SyntaxTree declared = workspace.tree(imported.file());
        List<Node> declarations = declared == null ? List.of() : Imports.declarations(declared, imported.name());
        return declarations.isEmpty() ? null : span(imported.file(), declarations.getFirst());
    }

    /** The uses of the name at {@code offset}, in this file and through {@code import}s, with its declarations if asked. */
    public List<Span> references(int offset, boolean declaration) {
        List<Span> spans = new ArrayList<>();
        Node definition = definitionAt(tree, offset);
        if (definition != null) for (Node use : uses(tree, definition, declaration)) spans.add(span(null, use));
        Imports.Attribute attribute = importedAt(offset);
        if (attribute != null)
            importedUses(attribute, declaration).forEach((file, uses) -> {
                for (Node use : uses) spans.add(span(file, use));
            });
        return spans;
    }

    /** The token at {@code offset} when it is a name to rename, or null, as on a builtin. */
    public Span renamed(int offset) {
        if (definitionAt(tree, offset) == null && importedAt(offset) == null) return null;
        return span(null, tree.at(offset));
    }

    /** The name tokens to rewrite to rename the name at {@code offset}, or null where there is nothing to rename. */
    public List<Span> renames(int offset) {
        Node definition = definitionAt(tree, offset);
        Imports.Attribute attribute = importedAt(offset);
        if (definition == null && attribute == null) return null;
        List<Span> spans = new ArrayList<>();
        if (definition != null) for (Node use : uses(tree, definition, true)) spans.add(span(null, use));
        if (attribute != null)
            importedUses(attribute, true).forEach((file, uses) -> {
                for (Node use : uses) spans.add(span(file, use));
            });
        return spans;
    }

    /** The definition the name at {@code offset} refers to, or is, and the name tokens of it and its uses. */
    record Uses(Node definition, List<Node> uses) {}

    Uses uses(int offset) {
        Node definition = definitionAt(tree, offset);
        return definition == null ? null : new Uses(nameToken(definition), uses(tree, definition, true));
    }

    private static Span span(String path, Node node) {
        return new Span(path, node.start(), node.end());
    }

    /** The attribute of a file that the name at {@code offset} declares or selects through an {@code import}. */
    private Imports.Attribute importedAt(int offset) {
        Node token = tree.at(offset);
        if (path == null || token.token != Lexeme.Kind.ID) return null;
        return Imports.at(tree, path, token);
    }

    /** Each file of the workspace with its selections of {@code attribute}, and its declarations if asked. */
    private Map<String, List<Node>> importedUses(Imports.Attribute attribute, boolean declaration) {
        Map<String, List<Node>> uses = new LinkedHashMap<>();
        if (declaration) {
            SyntaxTree target = workspace.tree(attribute.file());
            if (target != null)
                uses.put(attribute.file(), new ArrayList<>(Imports.declarations(target, attribute.name())));
        }
        for (Map.Entry<String, SyntaxTree> file : workspace.files().entrySet()) {
            SyntaxTree tree = file.getValue();
            if (!tree.text(tree.root()).contains("import")) continue;
            List<Node> found = Imports.uses(tree, file.getKey(), attribute);
            if (!found.isEmpty())
                uses.computeIfAbsent(file.getKey(), k -> new ArrayList<>()).addAll(found);
        }
        return uses;
    }

    /** Where the set literal of the innermost {@code with} that has it binds the variable at {@code offset}. */
    private static Node withAttribute(SyntaxTree tree, int offset) {
        Node var = tree.at(offset).parent();
        if (var == null || var.kind != NodeKind.VAR) return null;
        String name = tree.text(var);
        if (Linter.GLOBALS.contains(name) || Scopes.resolve(tree.view(), name, var) != null) return null;
        LocalSets sets = new LocalSets(tree);
        for (Node with : LocalSets.withs(var)) {
            Map<String, Node> names = sets.attributes(with.children().stream()
                    .filter(c -> c.token == null)
                    .findFirst()
                    .orElseThrow());
            if (names != null && names.containsKey(name)) return names.get(name);
        }
        return null;
    }

    /** Where a local set literal binds the attribute a selection names at {@code offset}, as {@code a} in {@code x.a}. */
    private static Node selectedAttribute(SyntaxTree tree, int offset) {
        Node segment = tree.at(offset);
        while (segment != null && segment.kind != NodeKind.ATTR_NAME && segment.kind != NodeKind.STRING)
            segment = segment.parent();
        Node path = segment == null ? null : segment.parent();
        Node select = path == null ? null : path.parent();
        if (select == null
                || path.kind != NodeKind.ATTR_PATH
                || select.kind != NodeKind.SELECT && select.kind != NodeKind.HAS_ATTR) return null;
        Node expr = select.children().getFirst();
        if (expr.kind != NodeKind.VAR) return null;
        LocalSets sets = new LocalSets(tree);
        List<String> names = new ArrayList<>();
        for (Node child : path.children()) {
            if (child == segment) break;
            if (child.kind == null) continue;
            String name = sets.name(child);
            if (name == null) return null;
            names.add(name);
        }
        Map<String, Node> attributes = sets.attributes(Scopes.resolve(tree.view(), tree.text(expr), expr), names);
        String name = sets.name(segment);
        return attributes == null || name == null ? null : attributes.get(name);
    }

    /** The definition the name at {@code offset} refers to, or is. */
    static Node definitionAt(SyntaxTree tree, int offset) {
        SyntaxView<Node> view = tree.view();
        Node token = tree.at(offset);
        if (token.token != Lexeme.Kind.ID && token.token != Lexeme.Kind.OR_KW) return null;
        Node name = token.parent();
        if (name == null || name.kind == null) return null;
        return switch (name.kind) {
            case VAR -> Scopes.resolve(view, tree.text(token), name);
            case PARAM, FORMAL -> name;
            case ATTR_NAME -> {
                Node from = Scopes.inheritedFrom(view, name);
                if (from != null) yield Scopes.resolve(view, tree.text(token), from);
                Node scope = name.parent() == null ? null : name.parent().parent();
                while (scope != null && scope.kind != NodeKind.LET && scope.kind != NodeKind.ATTR_SET)
                    scope = scope.parent();
                yield scope != null && Scopes.definitions(view, scope).contains(name) ? name : null;
            }
            default -> null;
        };
    }

    /** The name tokens of {@code definition} and of what refers to it. */
    private static List<Node> uses(SyntaxTree tree, Node definition, boolean declaration) {
        SyntaxView<Node> view = tree.view();
        String name = tree.text(nameToken(definition));
        List<Node> uses = new ArrayList<>();
        if (declaration) uses.add(nameToken(definition));
        collect(tree.root(), node -> {
            Node from = node.kind == NodeKind.VAR
                    ? node
                    : node.kind == NodeKind.ATTR_NAME ? Scopes.inheritedFrom(view, node) : null;
            if (from != null
                    && tree.text(nameToken(node)).equals(name)
                    && Scopes.resolve(view, name, from) == definition) uses.add(nameToken(node));
        });
        return uses;
    }

    static void collect(Node node, Consumer<Node> visit) {
        visit.accept(node);
        for (Node child : node.children()) collect(child, visit);
    }

    private static Node nameToken(Node node) {
        return node.children().isEmpty() ? node : node.children().getFirst();
    }

    // Links

    /**
     * The paths without interpolation, resolved against the file, the URIs, and the strings that are an http or https
     * URL and nothing else.
     */
    public List<Link> links() {
        List<Link> links = new ArrayList<>();
        collect(tree.root(), node -> {
            List<Node> children = node.children();
            String target = path == null ? null : pathTarget(tree, node, path);
            if (target != null) {
                links.add(new Link(node.start(), node.end(), target, true));
            } else if (node.kind == NodeKind.URI) {
                links.add(new Link(node.start(), node.end(), tree.text(node), false));
            } else if (node.kind == NodeKind.STRING
                    && children.size() == 3
                    && children.get(1).token == Lexeme.Kind.STR_TEXT
                    && URL.matcher(tree.text(children.get(1))).matches()) {
                Node range = children.get(1);
                links.add(new Link(range.start(), range.end(), tree.text(range), false));
            }
        });
        return links;
    }

    /**
     * The file a path without interpolation names, resolved against the file at {@code base}, the {@code default.nix}
     * in it when it is a directory, as {@code import} has it; else null.
     */
    private static String pathTarget(SyntaxTree tree, Node node, String base) {
        List<Node> children = node.children();
        if (node.kind != NodeKind.PATH || children.size() != 1 || children.getFirst().token != Lexeme.Kind.PATH)
            return null;
        String path = new File(base).toURI().resolve(tree.text(node)).getPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return new File(path).isDirectory() ? path + "/default.nix" : path;
    }

    // Rewrites

    /** Quoting or unquoting the attribute name at {@code offset}, or null where there is none or it must stay quoted. */
    public Rewrite attributeQuotes(int offset) {
        Node string = quotable(offset);
        if (string == null || !isAttributeName(string)) return null;
        String name = tree.text(string);
        String content = name.length() < 2 ? "" : name.substring(1, name.length() - 1);
        if (string.kind == NodeKind.ATTR_NAME)
            return new Rewrite("Quote the attribute name", string.start(), string.end(), "\"" + name + "\"");
        if (IDENTIFIER.matcher(content).matches() && !KEYWORDS.contains(content))
            return new Rewrite("Unquote the attribute name", string.start(), string.end(), content);
        return null;
    }

    /** Switching the string at {@code offset} between {@code "..."} and {@code ''...''}, or null. */
    public Rewrite stringQuotes(int offset) {
        Node string = quotable(offset);
        if (string == null || isAttributeName(string)) return null;
        String toggled = Quotes.toggle(tree.text(string));
        if (toggled == null) return null;
        return new Rewrite(
                string.kind == NodeKind.STRING ? "Use ''...'' quotes" : "Use \"...\" quotes",
                string.start(),
                string.end(),
                toggled);
    }

    /** Expanding then collapsing the binding at {@code offset}, those that apply. */
    public List<Rewrite> bindings(int offset) {
        List<Rewrite> rewrites = new ArrayList<>();
        Bindings.Rewrite expand = Bindings.expand(text, offset);
        if (expand != null)
            rewrites.add(new Rewrite("Expand the binding", expand.start(), expand.end(), expand.text()));
        Bindings.Rewrite collapse = Bindings.collapse(text, offset);
        if (collapse != null)
            rewrites.add(new Rewrite("Collapse the binding", collapse.start(), collapse.end(), collapse.text()));
        return rewrites;
    }

    private Node quotable(int offset) {
        Node string = tree.at(offset);
        while (string != null
                && string.kind != NodeKind.STRING
                && string.kind != NodeKind.IND_STRING
                && string.kind != NodeKind.ATTR_NAME) string = string.parent();
        return string;
    }

    private static boolean isAttributeName(Node node) {
        Node parent = node.parent();
        return node.kind == NodeKind.ATTR_NAME
                || parent != null && (parent.kind == NodeKind.ATTR_PATH || parent.kind == NodeKind.INHERIT);
    }

    // Range formatting

    /**
     * The formatting of the bindings between {@code start} and {@code end}, or of the whole text outside any; null when
     * it does not format. The text is formatted whole, and the bindings found again in it by the position of their
     * first and last tokens.
     */
    public static Rewrite formatRange(String text, int start, int end, int width, int indent, boolean strict) {
        String formatted;
        try {
            formatted = Formatter.format(text, width, indent, strict);
        } catch (RuntimeException e) {
            return null;
        }
        end = Math.max(start, end);
        SyntaxTree tree = SyntaxTree.parse(text);
        Node node = tree.at(start);
        while (node.parent() != null && node.end() < end) node = node.parent();
        Node binding = node;
        while (binding != null && binding.kind != NodeKind.BINDING && binding.kind != NodeKind.INHERIT)
            binding = binding.parent();
        int from = 0;
        int to = text.length();
        if (binding != null) {
            from = binding.start();
            to = binding.end();
        } else {
            int s = start;
            int e = end;
            List<Node> touched = node.children().stream()
                    .filter(c ->
                            (c.kind == NodeKind.BINDING || c.kind == NodeKind.INHERIT) && c.end() > s && c.start() < e)
                    .toList();
            if (!touched.isEmpty()) {
                from = touched.getFirst().start();
                to = touched.getLast().end();
            }
        }
        if (from == 0 && to == text.length()) return new Rewrite("Format", 0, to, formatted);
        List<Node> before = tokens(tree);
        List<Node> after = tokens(SyntaxTree.parse(formatted));
        if (before.size() != after.size()) return null;
        int first = -1;
        int last = -1;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).token != after.get(i).token) return null;
            if (before.get(i).start() == from) first = i;
            if (before.get(i).end() == to) last = i;
        }
        if (first < 0 || last < 0) return null;
        from = lineStart(text, from);
        int newFrom = lineStart(formatted, after.get(first).start());
        if ((from == before.get(first).start()) != (newFrom == after.get(first).start())) {
            from = before.get(first).start();
            newFrom = after.get(first).start();
        }
        return new Rewrite(
                "Format", from, to, formatted.substring(newFrom, after.get(last).end()));
    }

    /** {@code offset}'s line start when only blanks precede it on its line, else {@code offset}. */
    private static int lineStart(String text, int offset) {
        int i = offset;
        while (i > 0 && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) i--;
        return i == 0 || text.charAt(i - 1) == '\n' ? i : offset;
    }

    private static List<Node> tokens(SyntaxTree tree) {
        List<Node> tokens = new ArrayList<>();
        collect(tree.root(), n -> {
            if (n.token != null && n.end() > n.start()) tokens.add(n);
        });
        return tokens;
    }

    // Typed text

    /** The names of {@code a.b.c} as the {@code c} at {@code offset} is typed, or null outside such a selection. */
    private static List<String> selection(String text, int offset) {
        List<String> names = new ArrayList<>();
        int end = offset;
        while (true) {
            int start = end;
            while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
            if (start == end && !names.isEmpty() || start < end && !isNameStart(text.charAt(start))) return null;
            names.addFirst(text.substring(start, end));
            if (start == 0 || text.charAt(start - 1) != '.') break;
            end = start - 1;
        }
        return names.size() < 2 ? null : names;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    /** Whether the name being typed at {@code offset} follows {@code builtins.}. */
    private static boolean afterBuiltins(String text, int offset) {
        int start = offset;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        int at = start - "builtins.".length();
        if (at < 0 || !text.startsWith("builtins.", at)) return false;
        return at == 0 || !isNameChar(text.charAt(at - 1)) && text.charAt(at - 1) != '.';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-';
    }
}
