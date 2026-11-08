package org.affogato.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.regex.Pattern;
import org.affogato.format.Formatter;
import org.affogato.lint.Diagnostic;
import org.affogato.lint.Edit;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.affogato.lint.Suppressions;
import org.affogato.parser.Highlighter;
import org.affogato.parser.Injections;
import org.affogato.parser.Lexeme;
import org.affogato.parser.NodeKind;
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

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");

    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\\]+");

    private static final Set<NodeKind> FOLDED =
            Set.of(NodeKind.ATTR_SET, NodeKind.LIST, NodeKind.LET, NodeKind.IND_STRING);

    private final Map<String, Document> documents = new HashMap<>();
    private int width = Formatter.DEFAULT_WIDTH;
    private boolean strict;
    private Set<Rule> rules = EnumSet.allOf(Rule.class);
    private boolean exited;

    /** Serves {@code in} until the client says {@code exit}. */
    public static void serve(InputStream in, OutputStream out) throws IOException {
        Server server = new Server();
        while (!server.exited) {
            String body = read(in);
            if (body == null) return;
            List<Map<String, Object>> replies;
            try {
                replies = server.handle(Json.parse(body));
            } catch (IllegalArgumentException e) {
                replies = List.of(error(null, -32700, e.getMessage()));
            }
            for (Map<String, Object> message : replies) {
                byte[] bytes = Json.write(message).getBytes(StandardCharsets.UTF_8);
                out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.flush();
            }
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
            case "textDocument/didClose" -> {
                documents.remove(uri(params));
                out.add(notification(
                        "textDocument/publishDiagnostics", Map.of("uri", uri(params), "diagnostics", List.of())));
                yield null;
            }
            case "textDocument/formatting" -> formatting(document(params), params);
            case "textDocument/definition" -> definition(params);
            case "textDocument/references" -> references(params);
            case "textDocument/documentHighlight" -> highlights(params);
            case "textDocument/prepareRename" -> prepareRename(params);
            case "textDocument/rename" -> rename(params);
            case "textDocument/completion" -> completion(params);
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

    private static Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("textDocumentSync", 1L);
        capabilities.put("documentFormattingProvider", true);
        capabilities.put("definitionProvider", true);
        capabilities.put("referencesProvider", true);
        capabilities.put("documentHighlightProvider", true);
        capabilities.put("renameProvider", Map.of("prepareProvider", true));
        capabilities.put("completionProvider", Map.of());
        capabilities.put("documentSymbolProvider", true);
        capabilities.put("foldingRangeProvider", true);
        capabilities.put("selectionRangeProvider", true);
        capabilities.put("documentLinkProvider", Map.of());
        capabilities.put("codeActionProvider", Map.of("codeActionKinds", List.of("quickfix", "source.fixAll")));
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
     * Takes {@code {"width": ..., "strict": ..., "rules": [...]}}, each optional, the rules ids of those to lint with,
     * all when absent or empty; the indent is the formatting request's {@code tabSize}.
     */
    private void configure(Object settings) {
        if (!(settings instanceof Map<?, ?> s)) return;
        if (s.get("width") instanceof Long w) width = (int) (long) w;
        if (s.get("strict") instanceof Boolean b) strict = b;
        if (s.get("rules") instanceof List<?> ids) {
            rules = ids.isEmpty() ? EnumSet.allOf(Rule.class) : EnumSet.noneOf(Rule.class);
            for (Object id : ids) rules.add(Rule.byId((String) id));
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

    /** The attributes of {@code builtins} after {@code builtins.}, else the names in scope, globals and keywords. */
    private Object completion(Map<?, ?> params) {
        Document document = document(params);
        SyntaxView<Node> view = document.tree().view();
        int offset = offset(document, params);
        List<Object> items = new ArrayList<>();
        Set<String> local = new HashSet<>();
        List<Object> scope = new ArrayList<>();
        for (Node definition : Scopes.visible(view, document.tree().at(offset))) {
            String name = Scopes.name(view, definition);
            local.add(name);
            scope.add(Map.of("label", name, "kind", 6L));
        }
        if (!local.contains("builtins") && afterBuiltins(document.text(), offset)) {
            for (String name : new TreeSet<>(Linter.BUILTINS)) items.add(builtin(name));
            return items;
        }
        items.addAll(scope);
        for (String name : new TreeSet<>(Linter.GLOBALS)) if (!local.contains(name)) items.add(builtin(name));
        for (String keyword : KEYWORDS) items.add(Map.of("label", keyword, "kind", 14L));
        return items;
    }

    private static Map<String, Object> builtin(String name) {
        return Map.of("label", name, "kind", CONSTANTS.contains(name) ? 21L : 3L);
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
            String target = null;
            Node range = node;
            if (node.kind == NodeKind.PATH
                    && "file".equals(base.getScheme())
                    && children.size() == 1
                    && children.getFirst().token == Lexeme.Kind.PATH) {
                target = file(base.resolve(tree.text(node)).getPath());
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
