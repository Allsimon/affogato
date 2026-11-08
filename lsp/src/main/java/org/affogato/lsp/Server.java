package org.affogato.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.affogato.format.Formatter;
import org.affogato.lint.Diagnostic;
import org.affogato.lint.Edit;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.affogato.lint.Suppressions;
import org.affogato.parser.Bindings;
import org.affogato.parser.Highlighter;
import org.affogato.parser.Injections;
import org.affogato.parser.Lexeme;
import org.affogato.parser.NodeKind;
import org.affogato.parser.Quotes;
import org.affogato.parser.Scopes;
import org.affogato.parser.SyntaxTree;
import org.affogato.parser.SyntaxTree.Node;
import org.affogato.parser.SyntaxView;

/** A language server for Nix, speaking JSON-RPC with {@code Content-Length} framing, as the protocol has it. */
public final class Server {
    private record Document(String text, Lines lines, SyntaxTree tree) {
        static Document of(String text) {
            return new Document(text, new Lines(text), SyntaxTree.parse(text));
        }
    }

    /** The protocol's token type of each kind {@link Highlighter} classifies, by the index of the kind. */
    private static final List<String> TOKEN_TYPES = List.of(
            "keyword",
            "string",
            "regexp",
            "macro",
            "comment",
            "enumMember",
            "function",
            "number",
            "string",
            "operator",
            "property",
            "parameter");

    private static final List<String> KEYWORDS =
            List.of("assert", "else", "if", "in", "inherit", "let", "or", "rec", "then", "with");

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*");

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");

    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\\]+");

    private static final Set<NodeKind> FOLDED =
            Set.of(NodeKind.ATTR_SET, NodeKind.LIST, NodeKind.LET, NodeKind.IND_STRING);

    private static final int MAX_ITEMS = 1000;

    private final Map<String, Document> documents = new HashMap<>();
    private final Evaluator evaluator;
    private final Consumer<Map<String, Object>> send;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private boolean refreshSupport;
    private int requests;
    private int width = Formatter.DEFAULT_WIDTH;
    private boolean strict;
    private Set<Rule> rules = EnumSet.allOf(Rule.class);
    private boolean exited;

    public Server() {
        this(null);
    }

    /** Completes nixpkgs attributes from {@code evaluator}, when not null. */
    public Server(Evaluator evaluator) {
        this(evaluator, null);
    }

    /** Sends the requests the server makes of its own, as {@code workspace/inlayHint/refresh}, with {@code send}. */
    public Server(Evaluator evaluator, Consumer<Map<String, Object>> send) {
        this.evaluator = evaluator;
        this.send = send;
    }

    /** Serves {@code in} until the client says {@code exit}. */
    public static void serve(InputStream in, OutputStream out, Evaluator evaluator) throws IOException {
        Server server = new Server(evaluator, message -> {
            try {
                write(out, message);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        while (!server.exited) {
            String body = read(in);
            if (body == null) return;
            List<Map<String, Object>> replies;
            try {
                replies = server.handle(Json.parse(body));
            } catch (IllegalArgumentException e) {
                replies = List.of(error(null, -32700, e.getMessage()));
            }
            for (Map<String, Object> message : replies) write(out, message);
        }
    }

    /** Writes from the request thread and from the evaluator's, when it sends a refresh. */
    private static void write(OutputStream out, Map<String, Object> message) throws IOException {
        byte[] bytes = Json.write(message).getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }
    }

    /** The body of the next message, or null at the end of the input. */
    static String read(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            String header = line(in);
            if (header == null) return null;
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).equalsIgnoreCase("Content-Length"))
                length = Integer.parseInt(header.substring(colon + 1).strip());
        }
        if (length < 0) throw new IOException("no Content-Length");
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.US_ASCII);
    }

    /** The messages answering {@code message}: a response to a request, and any notification it causes. */
    public List<Map<String, Object>> handle(Object message) {
        if (!(message instanceof Map<?, ?> m) || m.get("method") != null && !(m.get("method") instanceof String))
            return List.of(error(null, -32600, "not a request"));
        String method = (String) m.get("method");
        Object id = m.get("id");
        Map<?, ?> params = m.get("params") instanceof Map<?, ?> p ? p : Map.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Object result = dispatch(method, params, out);
            if (id != null && method != null) out.addFirst(response(id, result));
        } catch (UnsupportedOperationException e) {
            if (id != null) out.add(error(id, -32601, "unsupported method " + method));
        } catch (RuntimeException e) {
            if (id != null) out.add(error(id, -32603, String.valueOf(e)));
        }
        return out;
    }

    private Object dispatch(String method, Map<?, ?> params, List<Map<String, Object>> out) {
        if (method == null) return null;
        return switch (method) {
            case "initialize" -> {
                refreshSupport = params.get("capabilities") instanceof Map<?, ?> c
                        && c.get("workspace") instanceof Map<?, ?> w
                        && w.get("inlayHint") instanceof Map<?, ?> h
                        && Boolean.TRUE.equals(h.get("refreshSupport"));
                configure(params.get("initializationOptions"));
                yield Map.of("capabilities", capabilities(), "serverInfo", Map.of("name", "affogato"));
            }
            case "initialized", "$/cancelRequest", "$/setTrace" -> null;
            case "workspace/didChangeConfiguration" -> {
                if (params.get("settings") instanceof Map<?, ?> settings) configure(settings.get("affogato"));
                for (Map.Entry<String, Document> e : documents.entrySet())
                    open(e.getKey(), e.getValue().text(), out);
                yield null;
            }
            case "shutdown" -> null;
            case "exit" -> {
                exited = true;
                yield null;
            }
            case "textDocument/didOpen" -> {
                Map<?, ?> document = (Map<?, ?>) params.get("textDocument");
                open((String) document.get("uri"), (String) document.get("text"), out);
                yield null;
            }
            case "textDocument/didChange" -> {
                List<?> changes = (List<?>) params.get("contentChanges");
                String text = (String) ((Map<?, ?>) changes.getLast()).get("text");
                open(uri(params), text, out);
                yield null;
            }
            case "textDocument/didSave" -> {
                if (evaluator != null) evaluator.reset();
                yield null;
            }
            case "textDocument/didClose" -> {
                documents.remove(uri(params));
                out.add(notification(
                        "textDocument/publishDiagnostics", Map.of("uri", uri(params), "diagnostics", List.of())));
                yield null;
            }
            case "textDocument/formatting" -> formatting(document(params), params);
            case "textDocument/definition" -> {
                Object location = attributeLocation(params);
                yield location != null ? location : definition(params);
            }
            case "textDocument/references" -> references(params);
            case "textDocument/documentHighlight" -> highlights(params);
            case "textDocument/prepareRename" -> prepareRename(params);
            case "textDocument/rename" -> rename(params);
            case "textDocument/completion" -> completion(params);
            case "completionItem/resolve" -> resolve(params);
            case "textDocument/hover" -> hover(params);
            case "textDocument/inlayHint" -> inlayHints(params);
            case "textDocument/documentSymbol" -> symbols(document(params));
            case "textDocument/foldingRange" -> folding(document(params));
            case "textDocument/selectionRange" -> selections(params);
            case "textDocument/documentLink" -> links(params);
            case "textDocument/semanticTokens/full" -> Map.of("data", semanticTokens(document(params)));
            case "affogato/injections" -> injections(document(params));
            case "textDocument/codeAction" -> codeActions(params);
            default -> {
                if (method.startsWith("$/")) yield null;
                throw new UnsupportedOperationException(method);
            }
        };
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("textDocumentSync", Map.of("openClose", true, "change", 1L, "save", Map.of()));
        capabilities.put("documentFormattingProvider", true);
        capabilities.put("definitionProvider", true);
        capabilities.put("referencesProvider", true);
        capabilities.put("documentHighlightProvider", true);
        capabilities.put("renameProvider", Map.of("prepareProvider", true));
        capabilities.put("completionProvider", Map.of("resolveProvider", evaluator != null));
        if (evaluator != null) {
            capabilities.put("hoverProvider", true);
            capabilities.put("inlayHintProvider", true);
        }
        capabilities.put("documentSymbolProvider", true);
        capabilities.put("foldingRangeProvider", true);
        capabilities.put("selectionRangeProvider", true);
        capabilities.put("documentLinkProvider", Map.of());
        capabilities.put(
                "codeActionProvider",
                Map.of("codeActionKinds", List.of("quickfix", "refactor.rewrite", "source.fixAll")));
        capabilities.put(
                "semanticTokensProvider",
                Map.of(
                        "legend",
                        Map.of("tokenTypes", TOKEN_TYPES, "tokenModifiers", List.of("defaultLibrary")),
                        "full",
                        true));
        return capabilities;
    }

    /**
     * Takes {@code {"width": ..., "strict": ..., "rules": [...], "nixpkgs": {"expr": ...}, "options": {name: {"expr":
     * ...}}}}, each optional, the rules ids of those to lint with, all when absent or empty; the indent is the
     * formatting request's {@code tabSize}.
     */
    private void configure(Object settings) {
        if (!(settings instanceof Map<?, ?> s)) return;
        if (s.get("width") instanceof Long w) width = (int) (long) w;
        if (s.get("strict") instanceof Boolean b) strict = b;
        if (s.get("rules") instanceof List<?> ids) {
            rules = ids.isEmpty() ? EnumSet.allOf(Rule.class) : EnumSet.noneOf(Rule.class);
            for (Object id : ids) rules.add(Rule.byId((String) id));
        }
        if (evaluator != null && s.get("nixpkgs") instanceof Map<?, ?> n && n.get("expr") instanceof String e)
            evaluator.configure(e);
        if (evaluator != null && s.get("options") instanceof Map<?, ?> sets) {
            Map<String, String> expressions = new LinkedHashMap<>();
            for (Map.Entry<?, ?> set : sets.entrySet())
                if (set.getValue() instanceof Map<?, ?> o && o.get("expr") instanceof String e)
                    expressions.put((String) set.getKey(), e);
            evaluator.configureOptions(expressions);
        }
    }

    private void open(String uri, String text, List<Map<String, Object>> out) {
        Document document = Document.of(text);
        documents.put(uri, document);
        List<Object> diagnostics = new ArrayList<>();
        for (Diagnostic d : Linter.lint(text, rules)) {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("range", document.lines().range(d.start(), d.end()));
            diagnostic.put(
                    "severity",
                    switch (d.severity()) {
                        case ERROR -> 1L;
                        case WARNING -> 2L;
                        case HINT -> 4L;
                    });
            diagnostic.put("code", d.rule().id());
            diagnostic.put("source", "affogato");
            diagnostic.put("message", d.message());
            diagnostics.add(diagnostic);
        }
        out.add(notification("textDocument/publishDiagnostics", Map.of("uri", uri, "diagnostics", diagnostics)));
    }

    private Object formatting(Document document, Map<?, ?> params) {
        int indent = params.get("options") instanceof Map<?, ?> o && o.get("tabSize") instanceof Long t
                ? (int) (long) t
                : Formatter.DEFAULT_INDENT;
        String formatted;
        try {
            formatted = Formatter.format(document.text(), width, indent, strict);
        } catch (RuntimeException e) {
            return null;
        }
        if (formatted.equals(document.text())) return List.of();
        return List.of(Map.of("range", document.lines().range(0, document.text().length()), "newText", formatted));
    }

    private Object definition(Map<?, ?> params) {
        Document document = document(params);
        Node path = document.tree().at(offset(document, params)).parent();
        String target = path == null ? null : pathTarget(document.tree(), path, URI.create(uri(params)));
        if (target != null) {
            Map<String, Object> start = Map.of("line", 0L, "character", 0L);
            return Map.of("uri", file(target), "range", Map.of("start", start, "end", start));
        }
        Node definition = definitionAt(document, offset(document, params));
        return definition == null ? null : location(uri(params), document, nameToken(definition));
    }

    private Object references(Map<?, ?> params) {
        Document document = document(params);
        Node definition = definitionAt(document, offset(document, params));
        if (definition == null) return List.of();
        boolean declaration =
                params.get("context") instanceof Map<?, ?> c && Boolean.TRUE.equals(c.get("includeDeclaration"));
        List<Object> locations = new ArrayList<>();
        for (Node use : uses(document, definition, declaration)) locations.add(location(uri(params), document, use));
        return locations;
    }

    /** The definition as a write, its uses as reads. */
    private Object highlights(Map<?, ?> params) {
        Document document = document(params);
        Node definition = definitionAt(document, offset(document, params));
        if (definition == null) return List.of();
        Node name = nameToken(definition);
        List<Object> highlights = new ArrayList<>();
        for (Node use : uses(document, definition, true))
            highlights.add(
                    Map.of("range", document.lines().range(use.start(), use.end()), "kind", use == name ? 3L : 2L));
        return highlights;
    }

    /** The name under the cursor, or null where there is nothing to rename, as on a builtin. */
    private Object prepareRename(Map<?, ?> params) {
        Document document = document(params);
        int offset = offset(document, params);
        if (definitionAt(document, offset) == null) return null;
        Node token = document.tree().at(offset);
        return Map.of(
                "range",
                document.lines().range(token.start(), token.end()),
                "placeholder",
                document.tree().text(token));
    }

    private Object rename(Map<?, ?> params) {
        Document document = document(params);
        Node definition = definitionAt(document, offset(document, params));
        if (definition == null) return null;
        String name = (String) params.get("newName");
        List<Object> edits = new ArrayList<>();
        for (Node use : uses(document, definition, true))
            edits.add(Map.of("range", document.lines().range(use.start(), use.end()), "newText", name));
        return Map.of("changes", Map.of(uri(params), edits));
    }

    /**
     * The attributes of {@code builtins} after {@code builtins.}, of nixpkgs after {@code pkgs.} and {@code lib.} unless
     * bound otherwise than as a parameter, else the names in scope, globals and keywords.
     */
    private Object completion(Map<?, ?> params) {
        Document document = document(params);
        SyntaxView<Node> view = document.tree().view();
        int offset = offset(document, params);
        List<Object> items = new ArrayList<>();
        Map<String, Node> local = new HashMap<>();
        List<Object> scope = new ArrayList<>();
        for (Node definition : Scopes.visible(view, document.tree().at(offset))) {
            String name = Scopes.name(view, definition);
            local.putIfAbsent(name, definition);
            scope.add(Map.of("label", name, "kind", 6L));
        }
        List<String> option = evaluator == null ? null : typedOption(document, offset);
        if (option != null) {
            List<String> parent = option.subList(0, option.size() - 1);
            Map<String, Object> found = attributes(evaluator.optionNames(parent), parent, option.getLast());
            for (Object item : (List<?>) found.get("items")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> i = new LinkedHashMap<>((Map<String, Object>) item);
                i.put("kind", 10L);
                i.put("data", Map.of("path", ((Map<?, ?>) i.get("data")).get("path"), "option", true));
                items.add(i);
            }
            return Map.of("isIncomplete", found.get("isIncomplete"), "items", items);
        }
        List<String> selection = selection(document.text(), offset);
        if (evaluator != null
                && selection != null
                && isModuleArgument(document.tree(), local.get(selection.getFirst()))) {
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
        if (!local.containsKey("builtins") && afterBuiltins(document.text(), offset)) {
            for (String name : new TreeSet<>(Linter.BUILTINS)) items.add(builtin(name));
            return items;
        }
        items.addAll(scope);
        for (String name : new TreeSet<>(Linter.GLOBALS)) if (!local.containsKey(name)) items.add(builtin(name));
        for (String keyword : KEYWORDS) items.add(Map.of("label", keyword, "kind", 14L));
        List<List<String>> scopes = evaluator == null
                ? List.of()
                : withScopes(document.tree(), document.tree().at(Math.max(offset - 1, 0)));
        if (scopes.isEmpty()) return items;
        int start = offset;
        while (start > 0 && isNameChar(document.text().charAt(start - 1))) start--;
        String prefix = document.text().substring(start, offset);
        boolean incomplete = false;
        Set<String> seen = new HashSet<>(local.keySet());
        seen.addAll(Linter.GLOBALS);
        for (List<String> path : scopes) {
            Map<String, Object> found = attributes(evaluator.names(path), path, prefix);
            incomplete |= (Boolean) found.get("isIncomplete");
            for (Object item : (List<?>) found.get("items"))
                if (seen.add((String) ((Map<?, ?>) item).get("label"))) items.add(item);
        }
        return Map.of("isIncomplete", incomplete, "items", items);
    }

    private static Map<String, Object> builtin(String name) {
        return Map.of("label", name, "kind", CONSTANTS.contains(name) ? 21L : 3L);
    }

    /** The item with the version or type as its detail, and the description or doc comment as its documentation. */
    private Object resolve(Map<?, ?> item) {
        Map<Object, Object> resolved = new LinkedHashMap<>(item);
        if (evaluator == null
                || !(item.get("data") instanceof Map<?, ?> data)
                || !(data.get("path") instanceof List<?> path)) return resolved;
        List<String> names = path.stream().map(String.class::cast).toList();
        Evaluator.Attribute a =
                Boolean.TRUE.equals(data.get("option")) ? evaluator.describeOption(names) : evaluator.describe(names);
        if (a == null || a.type() == null) return resolved;
        resolved.put("detail", a.version() != null ? a.version() : a.type().equals("option") ? a.value() : a.type());
        String documentation = a.description() != null ? a.description() : a.doc();
        if (documentation != null) resolved.put("documentation", Map.of("kind", "markdown", "value", documentation));
        return resolved;
    }

    private Object hover(Map<?, ?> params) {
        Document document = document(params);
        int offset = offset(document, params);
        Selected selected = selected(document, offset);
        Evaluator.Attribute a;
        if (selected != null) {
            a = evaluator.describe(selected.path());
        } else {
            selected = boundOption(document, offset);
            if (selected == null) return null;
            a = evaluator.describeOption(selected.path());
        }
        if (a == null || a.type() == null) return null;
        StringBuilder text = new StringBuilder();
        if (a.type().equals("option")) {
            text.append("`");
            for (String name : selected.path()) {
                if (text.length() > 1) text.append('.');
                boolean plain = !name.isEmpty()
                        && isNameStart(name.charAt(0))
                        && name.chars().allMatch(c -> isNameChar((char) c));
                text.append(plain ? name : Json.write(name));
            }
            text.append("`");
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
        return Map.of(
                "contents",
                Map.of("kind", "markdown", "value", text.toString()),
                "range",
                document.lines().range(selected.name().start(), selected.name().end()));
    }

    private static String code(String text) {
        return text.contains("\n") ? "\n```nix\n" + text.strip() + "\n```" : "`" + text + "`";
    }

    /** Where the nixpkgs attribute or the option at the position is defined, or null. */
    private Object attributeLocation(Map<?, ?> params) {
        Document document = document(params);
        int offset = offset(document, params);
        Selected selected = selected(document, offset);
        Evaluator.Attribute a;
        if (selected != null) {
            a = evaluator.describe(selected.path());
        } else {
            selected = boundOption(document, offset);
            if (selected == null) return null;
            a = evaluator.describeOption(selected.path());
        }
        if (a == null || a.file() == null || !a.file().startsWith("/")) return null;
        Map<String, Object> at = Map.of("line", (long) a.line() - 1, "character", (long) Math.max(a.column() - 1, 0));
        return Map.of("uri", file(a.file()), "range", Map.of("start", at, "end", at));
    }

    /** The binding name at {@code offset} in a module, and the path of the option it sets, or null. */
    private Selected boundOption(Document document, int offset) {
        if (evaluator == null) return null;
        SyntaxTree tree = document.tree();
        Node name = tree.at(offset).parent();
        if (name == null || name.kind != NodeKind.ATTR_NAME) return null;
        Node attrPath = name.parent();
        if (attrPath == null || attrPath.kind != NodeKind.ATTR_PATH) return null;
        Node binding = attrPath.parent();
        if (binding == null || binding.kind != NodeKind.BINDING) return null;
        List<String> path = optionPrefix(tree, binding.parent());
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

    /** The option path being typed at {@code offset} as a binding name in a module, its last name partial, or null. */
    private List<String> typedOption(Document document, int offset) {
        String text = document.text();
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
        Node set = document.tree().at(before - 1);
        while (set != null && set.kind != NodeKind.ATTR_SET) {
            if (set.kind == NodeKind.LET) return null;
            set = set.parent();
        }
        List<String> path = optionPrefix(document.tree(), set);
        if (path == null) return null;
        path.addAll(typed);
        path = options(path);
        return path == null || path.isEmpty() ? null : path;
    }

    /**
     * The option path that the bindings of {@code set} start from, when it is the body of a module, a function of
     * {@code config}, {@code lib}, {@code pkgs} or {@code modulesPath}, or a set bound in one, possibly through
     * parentheses, lists, function arguments as of {@code mkIf} or {@code mkMerge}, and {@code let}; else null.
     */
    private static List<String> optionPrefix(SyntaxTree tree, Node set) {
        if (set == null || set.kind != NodeKind.ATTR_SET || tree.text(set).startsWith("rec")) return null;
        List<String> path = new ArrayList<>();
        Node node = set;
        while (true) {
            Node parent = node.parent();
            if (parent == null || parent.kind == null) return null;
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
                    return parts.getLast() == node && isModule(tree, parent) ? path : null;
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
    private Selected selected(Document document, int offset) {
        if (evaluator == null) return null;
        SyntaxTree tree = document.tree();
        Node token = tree.at(offset);
        Node name = token.parent();
        if (name != null && name.kind == NodeKind.VAR) return first(name, candidates(tree, name));
        if (name == null || name.kind != NodeKind.ATTR_NAME) return null;
        Node attrPath = name.parent();
        if (attrPath == null || attrPath.kind != NodeKind.ATTR_PATH) return null;
        List<Node> children = attrPath.children();
        int after = 0;
        for (int i = children.size() - 1; children.get(i) != name; i--) if (children.get(i).token == null) after++;
        List<List<String>> candidates = new ArrayList<>();
        for (List<String> path : selectionCandidates(tree, attrPath.parent(), new ArrayList<>()))
            candidates.add(path.subList(0, path.size() - after));
        return candidates.size() == 1 ? new Selected(name, candidates.getFirst()) : first(name, candidates);
    }

    /** The first of {@code candidates} that nixpkgs has, or null when there is none or one is still evaluated. */
    private Selected first(Node name, List<List<String>> candidates) {
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
    private List<List<String>> selectionCandidates(SyntaxTree tree, Node select, List<List<String>> pending) {
        List<String> own = nixpkgsPath(tree, select);
        if (own != null) return List.of(own);
        if (select == null || select.kind != NodeKind.SELECT) return List.of();
        Node base = select.children().getFirst();
        Node attrPath = attrPath(select);
        if (base.kind != NodeKind.VAR || attrPath == null) return List.of();
        List<String> names = new ArrayList<>();
        for (Node child : attrPath.children()) {
            if (child.token != null) continue;
            String text =
                    child.kind == NodeKind.DYNAMIC_ATTR ? computedName(tree, child, pending) : attrName(tree, child);
            if (text == null) return List.of();
            names.add(text);
        }
        List<List<String>> bases = isModuleArgument(tree, Scopes.resolve(tree.view(), tree.text(base), base))
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
    private String computedName(SyntaxTree tree, Node dynamic, List<List<String>> pending) {
        Node expr = dynamic.children().stream()
                .filter(c -> c.token == null)
                .findFirst()
                .orElse(null);
        List<List<String>> candidates = selectionCandidates(tree, expr, pending);
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
    private static boolean isModuleArgument(SyntaxTree tree, Node definition) {
        if (definition == null || definition.kind != NodeKind.FORMAL) return false;
        if (Set.of("pkgs", "lib", "config", "options").contains(Scopes.name(tree.view(), definition))) return false;
        Node lambda = definition.parent() == null ? null : definition.parent().parent();
        return lambda != null && lambda.kind == NodeKind.LAMBDA && isModule(tree, lambda);
    }

    /** Whether {@code lambda} is the file, a function of {@code config}, {@code lib}, {@code pkgs} or {@code modulesPath}. */
    private static boolean isModule(SyntaxTree tree, Node lambda) {
        if (lambda.parent() == null || lambda.parent().kind != null) return false;
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

    /** The version of each package selected from nixpkgs in the range, after its selection. */
    private Object inlayHints(Map<?, ?> params) {
        Document document = document(params);
        if (evaluator == null) return List.of();
        Map<?, ?> range = (Map<?, ?>) params.get("range");
        int start = document.lines().offset((Map<?, ?>) range.get("start"));
        int end = document.lines().offset((Map<?, ?>) range.get("end"));
        SyntaxTree tree = document.tree();
        List<Integer> ends = new ArrayList<>();
        List<List<String>> pending = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<List<String>> paths = new ArrayList<>();
        collect(tree.root(), node -> {
            if (node.end() < start || node.start() > end) return;
            if (node.kind == NodeKind.SELECT) {
                List<List<String>> candidates = selectionCandidates(tree, node, pending).stream()
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
        pending.addAll(paths);
        if ((attributes.contains(null) || pending.size() > paths.size())
                && send != null
                && refreshSupport
                && refreshing.compareAndSet(false, true))
            evaluator.whenDescribed(pending, () -> {
                refreshing.set(false);
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("jsonrpc", "2.0");
                request.put("id", "affogato-" + ++requests);
                request.put("method", "workspace/inlayHint/refresh");
                send.accept(request);
            });
        List<Object> hints = new ArrayList<>();
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
            hints.add(Map.of(
                    "position", document.lines().position(ends.get(i)), "label", found.version(), "paddingLeft", true));
        }
        return hints;
    }

    /** The names starting with {@code prefix}, incomplete while being evaluated or when there are too many. */
    private static Map<String, Object> attributes(List<String> names, List<String> path, String prefix) {
        List<Object> items = new ArrayList<>();
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
                items.add(Map.of("label", name, "kind", 5L, "data", Map.of("path", item)));
            }
        return Map.of("isIncomplete", incomplete, "items", items);
    }

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

    private Object symbols(Document document) {
        return symbols(document, document.tree().root());
    }

    private List<Object> symbols(Document document, Node node) {
        List<Object> symbols = new ArrayList<>();
        for (Node child : node.children()) {
            if (child.kind != NodeKind.BINDING) {
                symbols.addAll(symbols(document, child));
                continue;
            }
            Node path = child.children().getFirst();
            Map<String, Object> symbol = new LinkedHashMap<>();
            symbol.put("name", document.tree().text(path));
            symbol.put("kind", 8L);
            symbol.put("range", document.lines().range(child.start(), child.end()));
            symbol.put("selectionRange", document.lines().range(path.start(), path.end()));
            symbol.put("children", symbols(document, child));
            symbols.add(symbol);
        }
        return symbols;
    }

    private Object folding(Document document) {
        List<Object> ranges = new ArrayList<>();
        fold(document, document.tree().root(), ranges);
        return ranges;
    }

    private void fold(Document document, Node node, List<Object> ranges) {
        if (node.kind != null && FOLDED.contains(node.kind)) {
            int start = document.lines().line(node.start());
            int end = document.lines().line(node.end());
            if (start < end) ranges.add(Map.of("startLine", (long) start, "endLine", (long) end));
        }
        for (Node child : node.children()) fold(document, child, ranges);
    }

    /** For each position, the token there inside each node that holds it and spans more. */
    private Object selections(Map<?, ?> params) {
        Document document = document(params);
        List<Object> selections = new ArrayList<>();
        for (Object position : (List<?>) params.get("positions")) {
            Map<String, Object> selection = null;
            int start = -1;
            int end = -1;
            List<Node> nodes = new ArrayList<>();
            for (Node node = document.tree().at(document.lines().offset((Map<?, ?>) position));
                    node != null;
                    node = node.parent()) nodes.add(node);
            for (Node node : nodes.reversed()) {
                if (node.start() == start && node.end() == end) continue;
                start = node.start();
                end = node.end();
                Map<String, Object> inner = new LinkedHashMap<>();
                inner.put("range", document.lines().range(start, end));
                if (selection != null) inner.put("parent", selection);
                selection = inner;
            }
            selections.add(selection);
        }
        return selections;
    }

    /**
     * The paths without interpolation, resolved against a {@code file:} document, the URIs, and the strings that are
     * an http or https URL and nothing else.
     */
    private Object links(Map<?, ?> params) {
        Document document = document(params);
        SyntaxTree tree = document.tree();
        URI base = URI.create(uri(params));
        List<Object> links = new ArrayList<>();
        collect(tree.root(), node -> {
            List<Node> children = node.children();
            String target = pathTarget(tree, node, base);
            Node range = node;
            if (target != null) {
                target = file(target);
            } else if (node.kind == NodeKind.URI) {
                target = tree.text(node);
            } else if (node.kind == NodeKind.STRING
                    && children.size() == 3
                    && children.get(1).token == Lexeme.Kind.STR_TEXT
                    && URL.matcher(tree.text(children.get(1))).matches()) {
                range = children.get(1);
                target = tree.text(range);
            }
            if (target != null)
                links.add(Map.of("range", document.lines().range(range.start(), range.end()), "target", target));
        });
        return links;
    }

    /**
     * The file a path without interpolation names, resolved against a {@code file:} document, the {@code default.nix}
     * in it when it is a directory, as {@code import} has it; else null.
     */
    private static String pathTarget(SyntaxTree tree, Node node, URI base) {
        List<Node> children = node.children();
        if (node.kind != NodeKind.PATH
                || !"file".equals(base.getScheme())
                || children.size() != 1
                || children.getFirst().token != Lexeme.Kind.PATH) return null;
        String path = base.resolve(tree.text(node)).getPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return new File(path).isDirectory() ? path + "/default.nix" : path;
    }

    /** {@code file:///path}, where {@link URI#resolve} gives {@code file:/path}, which not every client reads. */
    private static String file(String path) {
        try {
            return new URI("file", "", path, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * For each finding the range touches: its fix, when it has one, and the comments that switch its rule off on its
     * line or in the file; and, when the client asks for {@code source.fixAll}, all the fixes.
     */
    private Object codeActions(Map<?, ?> params) {
        Document document = document(params);
        Lines lines = document.lines();
        Map<?, ?> range = (Map<?, ?>) params.get("range");
        int start = lines.offset((Map<?, ?>) range.get("start"));
        int end = lines.offset((Map<?, ?>) range.get("end"));
        String uri = uri(params);
        List<Object> actions = new ArrayList<>();
        if (params.get("context") instanceof Map<?, ?> c
                && c.get("only") instanceof List<?> only
                && (only.contains("source") || only.contains("source.fixAll"))) {
            String fixed;
            try {
                fixed = Linter.fix(document.text(), rules);
            } catch (RuntimeException e) {
                return actions;
            }
            if (!fixed.equals(document.text())) {
                Map<String, Object> action = action(
                        "Fix all",
                        uri,
                        List.of(Map.of("range", lines.range(0, document.text().length()), "newText", fixed)));
                action.put("kind", "source.fixAll");
                actions.add(action);
            }
            return actions;
        }
        rewrite(actions, uri, lines, "Expand the binding", Bindings.expand(document.text(), start));
        rewrite(actions, uri, lines, "Collapse the binding", Bindings.collapse(document.text(), start));
        Node string = document.tree().at(start);
        while (string != null
                && string.kind != NodeKind.STRING
                && string.kind != NodeKind.IND_STRING
                && string.kind != NodeKind.ATTR_NAME) string = string.parent();
        if (string != null && isAttributeName(string)) {
            String name = document.tree().text(string);
            String content = name.length() < 2 ? "" : name.substring(1, name.length() - 1);
            if (string.kind == NodeKind.ATTR_NAME)
                rewrite(
                        actions,
                        uri,
                        lines,
                        "Quote the attribute name",
                        new Bindings.Rewrite(string.start(), string.end(), "\"" + name + "\""));
            else if (IDENTIFIER.matcher(content).matches() && !KEYWORDS.contains(content))
                rewrite(
                        actions,
                        uri,
                        lines,
                        "Unquote the attribute name",
                        new Bindings.Rewrite(string.start(), string.end(), content));
        } else if (string != null) {
            String toggled = Quotes.toggle(document.tree().text(string));
            if (toggled != null)
                rewrite(
                        actions,
                        uri,
                        lines,
                        string.kind == NodeKind.STRING ? "Use ''...'' quotes" : "Use \"...\" quotes",
                        new Bindings.Rewrite(string.start(), string.end(), toggled));
        }
        for (Diagnostic d : Linter.lint(document.text(), rules)) {
            if (d.end() < start || d.start() > end) continue;
            String id = d.rule().id();
            if (!d.fix().isEmpty()) {
                List<Object> edits = new ArrayList<>();
                for (Edit edit : d.fix())
                    edits.add(Map.of("range", lines.range(edit.start(), edit.end()), "newText", edit.replacement()));
                actions.add(action("Fix " + id, uri, edits));
            }
            if (d.rule() == Rule.SYNTAX_ERROR) continue;
            int line = lines.line(d.start());
            int lineStart = lines.offset(Map.of("line", (long) line, "character", 0L));
            String text = document.text();
            int indent = lineStart;
            while (indent < text.length() && (text.charAt(indent) == ' ' || text.charAt(indent) == '\t')) indent++;
            String comment = text.substring(lineStart, indent) + Suppressions.ignore(d.rule()) + "\n";
            actions.add(action(
                    "Suppress " + id + " on this line",
                    uri,
                    List.of(Map.of("range", lines.range(lineStart, lineStart), "newText", comment))));
            actions.add(action(
                    "Suppress " + id + " in this file",
                    uri,
                    List.of(Map.of("range", lines.range(0, 0), "newText", Suppressions.ignoreFile(d.rule()) + "\n"))));
        }
        return actions;
    }

    private static boolean isAttributeName(Node node) {
        Node parent = node.parent();
        return node.kind == NodeKind.ATTR_NAME
                || parent != null && (parent.kind == NodeKind.ATTR_PATH || parent.kind == NodeKind.INHERIT);
    }

    private static void rewrite(List<Object> actions, String uri, Lines lines, String title, Bindings.Rewrite rewrite) {
        if (rewrite == null) return;
        Map<String, Object> action = action(
                title,
                uri,
                List.of(Map.of("range", lines.range(rewrite.start(), rewrite.end()), "newText", rewrite.text())));
        action.put("kind", "refactor.rewrite");
        actions.add(action);
    }

    private static Map<String, Object> action(String title, String uri, List<Object> edits) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("title", title);
        action.put("kind", "quickfix");
        action.put("edit", Map.of("changes", Map.of(uri, edits)));
        return action;
    }

    /** The highlighter's spans, a span over several lines cut at each line end, encoded as the protocol has it. */
    private static List<Long> semanticTokens(Document document) {
        List<Long> data = new ArrayList<>();
        String text = document.text();
        int previousLine = 0;
        int previousStart = 0;
        for (Highlighter.Span span : Highlighter.highlight(text)) {
            int start = span.start();
            while (start < span.end()) {
                int lineEnd = text.indexOf('\n', start);
                int end = lineEnd < 0 || lineEnd >= span.end() ? span.end() : lineEnd;
                if (end > start) {
                    int line = document.lines().line(start);
                    int column =
                            (int) (long) (Long) document.lines().position(start).get("character");
                    data.add((long) (line - previousLine));
                    data.add((long) (line == previousLine ? column - previousStart : column));
                    data.add((long) (end - start));
                    data.add((long) span.kind().ordinal());
                    data.add(span.kind() == Highlighter.Kind.BUILTIN ? 1L : 0L);
                    previousLine = line;
                    previousStart = column;
                }
                start = end + 1;
            }
        }
        return data;
    }

    /** Each string another language is written in: the range between its quotes, and what {@link Injections} found. */
    private static List<Object> injections(Document document) {
        List<Object> injections = new ArrayList<>();
        SyntaxView<Node> view = document.tree().view();
        collect(document.tree().root(), node -> {
            if (node.kind != NodeKind.STRING && node.kind != NodeKind.IND_STRING) return;
            Injections.Injection injection = Injections.of(view, node, Injections.Rules.DEFAULT);
            List<Node> children = node.children();
            if (injection == null || children.size() < 2) return;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(
                    "range",
                    document.lines()
                            .range(children.getFirst().end(), children.getLast().start()));
            entry.put("kind", injection.kind().name().toLowerCase(java.util.Locale.ROOT));
            entry.put("value", injection.value());
            injections.add(entry);
        });
        return injections;
    }

    /** The definition the name at {@code offset} refers to, or is. */
    static Node definitionAt(Document document, int offset) {
        SyntaxTree tree = document.tree();
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
    private static List<Node> uses(Document document, Node definition, boolean declaration) {
        SyntaxTree tree = document.tree();
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

    private static void collect(Node node, java.util.function.Consumer<Node> visit) {
        visit.accept(node);
        for (Node child : node.children()) collect(child, visit);
    }

    private static Node nameToken(Node node) {
        return node.children().isEmpty() ? node : node.children().getFirst();
    }

    private Document document(Map<?, ?> params) {
        Document document = documents.get(uri(params));
        if (document == null) throw new IllegalStateException("not open: " + uri(params));
        return document;
    }

    private static String uri(Map<?, ?> params) {
        return (String) ((Map<?, ?>) params.get("textDocument")).get("uri");
    }

    private static int offset(Document document, Map<?, ?> params) {
        return document.lines().offset((Map<?, ?>) params.get("position"));
    }

    private static Map<String, Object> location(String uri, Document document, Node node) {
        return Map.of("uri", uri, "range", document.lines().range(node.start(), node.end()));
    }

    private static Map<String, Object> response(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", (long) code, "message", message));
        return response;
    }

    private static Map<String, Object> notification(String method, Object params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        return notification;
    }
}
