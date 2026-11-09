package org.affogato.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.affogato.format.Formatter;
import org.affogato.lint.Diagnostic;
import org.affogato.lint.Edit;
import org.affogato.lint.Linter;
import org.affogato.lint.Rule;
import org.affogato.lint.Suppressions;
import org.affogato.parser.Highlighter;
import org.affogato.parser.Injections;
import org.affogato.parser.NodeKind;
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

    private static final int BATCH = 64;

    private static final int WORKSPACE_FILES = 20_000;

    private static final Set<String> SKIPPED = Set.of("node_modules", "result", "target");

    private static final Set<NodeKind> FOLDED =
            Set.of(NodeKind.ATTR_SET, NodeKind.LIST, NodeKind.LET, NodeKind.IND_STRING);

    private static final Map<String, Object> ORIGIN = Map.of("line", 0L, "character", 0L);

    private final Map<String, Document> documents = new HashMap<>();
    private final Evaluator evaluator;
    private final Consumer<Map<String, Object>> send;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicInteger progressTokens = new AtomicInteger();
    private boolean refreshSupport;
    private boolean progressSupport;
    private boolean snippetSupport;

    private boolean configurationSupport;
    private Object configurationRequest;
    private int requests;
    private int width = Formatter.DEFAULT_WIDTH;
    private boolean strict;
    private Set<Rule> rules = EnumSet.allOf(Rule.class);
    private boolean exited;
    private boolean changedAgain;

    private final List<String> roots = new ArrayList<>();
    private final Map<String, Read> read = new HashMap<>();

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
        boolean end = false;
        while (!server.exited && !end) {
            List<String> bodies = new ArrayList<>();
            do {
                String body = read(in);
                if (body == null) end = true;
                else bodies.add(body);
            } while (!end && bodies.size() < BATCH && in.available() > 0);
            List<Object> messages = new ArrayList<>();
            Set<Object> cancelled = new HashSet<>();
            for (String body : bodies) {
                Object message;
                try {
                    message = Json.parse(body);
                } catch (IllegalArgumentException e) {
                    message = e;
                }
                messages.add(message);
                if (message instanceof Map<?, ?> m
                        && "$/cancelRequest".equals(m.get("method"))
                        && m.get("params") instanceof Map<?, ?> p) cancelled.add(p.get("id"));
            }
            for (int i = 0; i < messages.size() && !server.exited; i++) {
                List<Map<String, Object>> replies;
                if (messages.get(i) instanceof IllegalArgumentException e)
                    replies = List.of(error(null, -32700, e.getMessage()));
                else if (messages.get(i) instanceof Map<?, ?> m
                        && m.get("method") != null
                        && m.get("id") != null
                        && cancelled.contains(m.get("id"))) replies = List.of(error(m.get("id"), -32800, "cancelled"));
                else replies = server.handle(messages.get(i), changedAgain(messages, i));
                for (Map<String, Object> message : replies) write(out, message);
            }
        }
    }

    /** Whether the message at {@code i} changes a document that a later one changes again. */
    private static boolean changedAgain(List<Object> messages, int i) {
        String uri = changed(messages.get(i));
        if (uri == null) return false;
        for (int j = i + 1; j < messages.size(); j++) if (uri.equals(changed(messages.get(j)))) return true;
        return false;
    }

    private static String changed(Object message) {
        return message instanceof Map<?, ?> m
                        && "textDocument/didChange".equals(m.get("method"))
                        && m.get("params") instanceof Map<?, ?> p
                ? uri(p)
                : null;
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
        return handle(message, false);
    }

    /**
     * Answers {@code message}, a change of a document it does not lint when {@code changedAgain}, as a later change
     * of that document waits.
     */
    public List<Map<String, Object>> handle(Object message, boolean changedAgain) {
        this.changedAgain = changedAgain;
        if (!(message instanceof Map<?, ?> m) || m.get("method") != null && !(m.get("method") instanceof String))
            return List.of(error(null, -32600, "not a request"));
        String method = (String) m.get("method");
        Object id = m.get("id");
        Map<?, ?> params = m.get("params") instanceof Map<?, ?> p ? p : Map.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            if (method == null && id != null && id.equals(configurationRequest)) {
                configurationRequest = null;
                if (m.get("result") instanceof List<?> sections && !sections.isEmpty())
                    reconfigure(sections.getFirst(), out);
                return out;
            }
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
                configurationSupport = params.get("capabilities") instanceof Map<?, ?> c
                        && c.get("workspace") instanceof Map<?, ?> w
                        && Boolean.TRUE.equals(w.get("configuration"));
                refreshSupport = params.get("capabilities") instanceof Map<?, ?> c
                        && c.get("workspace") instanceof Map<?, ?> w
                        && w.get("inlayHint") instanceof Map<?, ?> h
                        && Boolean.TRUE.equals(h.get("refreshSupport"));
                progressSupport = params.get("capabilities") instanceof Map<?, ?> c
                        && c.get("window") instanceof Map<?, ?> w
                        && Boolean.TRUE.equals(w.get("workDoneProgress"));
                snippetSupport = params.get("capabilities") instanceof Map<?, ?> c
                        && c.get("textDocument") instanceof Map<?, ?> t
                        && t.get("completion") instanceof Map<?, ?> completion
                        && completion.get("completionItem") instanceof Map<?, ?> item
                        && Boolean.TRUE.equals(item.get("snippetSupport"));

                roots.clear();
                if (params.get("workspaceFolders") instanceof List<?> folders) {
                    for (Object folder : folders)
                        if (folder instanceof Map<?, ?> f && f.get("uri") instanceof String u && path(u) != null)
                            roots.add(path(u));
                } else if (params.get("rootUri") instanceof String u && path(u) != null) roots.add(path(u));
                configure(params.get("initializationOptions"));
                yield Map.of("capabilities", capabilities(), "serverInfo", Map.of("name", "affogato"));
            }
            case "initialized" -> {
                if (evaluator != null && send != null && progressSupport) evaluator.progress(this::beginProgress);
                if (evaluator != null && send != null)
                    evaluator.failures((title, message) -> send.accept(notification(
                            "window/showMessage", Map.of("type", 2L, "message", title + " failed: " + message))));
                pullConfiguration(out);
                yield null;
            }
            case "$/cancelRequest", "$/setTrace" -> null;
            case "workspace/didChangeConfiguration" -> {
                if (params.get("settings") instanceof Map<?, ?> settings && settings.get("affogato") != null)
                    reconfigure(settings.get("affogato"), out);
                else if (!pullConfiguration(out)) reconfigure(null, out);
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
                if (changedAgain) documents.put(uri(params), Document.of(text));
                else open(uri(params), text, out);
                yield null;
            }
            case "textDocument/didSave" -> {
                String saved = path(uri(params));
                if (evaluator != null && saved != null) evaluator.saved(saved);
                else if (evaluator != null) evaluator.reset();
                yield null;
            }
            case "textDocument/didClose" -> {
                documents.remove(uri(params));
                out.add(notification(
                        "textDocument/publishDiagnostics", Map.of("uri", uri(params), "diagnostics", List.of())));
                yield null;
            }
            case "textDocument/formatting" -> formatting(document(params), params);
            case "textDocument/rangeFormatting" -> rangeFormatting(document(params), params);
            case "textDocument/definition" -> {
                Object location = attributeLocation(params);
                yield location != null ? location : definition(params);
            }
            case "textDocument/references" -> references(params);
            case "textDocument/documentHighlight" -> highlights(params);
            case "textDocument/prepareRename" -> prepareRename(params);
            case "textDocument/rename" -> rename(params);
            case "textDocument/completion" -> {
                Analysis analysis = analysis(params);
                Object completion = completion(analysis.completion(offset(document(params), params), snippetSupport));
                yield analysis.evaluator() == evaluator ? completion : withUri(completion, uri(params));
            }
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
        capabilities.put("documentRangeFormattingProvider", true);
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
    /** Shows {@code title} in the client until the returned end runs, from any thread. */
    private Runnable beginProgress(String title) {
        String token = "affogato-progress-" + progressTokens.incrementAndGet();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("jsonrpc", "2.0");
        create.put("id", token);
        create.put("method", "window/workDoneProgress/create");
        create.put("params", Map.of("token", token));
        send.accept(create);
        send.accept(progress(token, Map.of("kind", "begin", "title", title)));
        return () -> send.accept(progress(token, Map.of("kind", "end")));
    }

    private static Map<String, Object> progress(String token, Map<String, Object> value) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "$/progress");
        notification.put("params", Map.of("token", token, "value", value));
        return notification;
    }

    /** Asks the client for the {@code affogato} settings, when it takes the request; whether it was asked. */
    private boolean pullConfiguration(List<Map<String, Object>> out) {
        if (!configurationSupport) return false;
        configurationRequest = "affogato-" + ++requests;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", configurationRequest);
        request.put("method", "workspace/configuration");
        request.put("params", Map.of("items", List.of(Map.of("section", "affogato"))));
        out.add(request);
        return true;
    }

    private void reconfigure(Object settings, List<Map<String, Object>> out) {
        configure(settings);
        for (Map.Entry<String, Document> e : documents.entrySet())
            open(e.getKey(), e.getValue().text(), out);
    }

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

    private String formatted(Document document, Map<?, ?> params) {
        try {
            return Formatter.format(document.text(), width, indent(params), strict);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Object formatting(Document document, Map<?, ?> params) {
        String formatted = formatted(document, params);
        if (formatted == null) return null;
        if (formatted.equals(document.text())) return List.of();
        return List.of(Map.of("range", document.lines().range(0, document.text().length()), "newText", formatted));
    }

    private static int indent(Map<?, ?> params) {
        return params.get("options") instanceof Map<?, ?> o && o.get("tabSize") instanceof Long t
                ? (int) (long) t
                : Formatter.DEFAULT_INDENT;
    }

    /** The formatting of the bindings the range touches, or of the whole file outside any. */
    private Object rangeFormatting(Document document, Map<?, ?> params) {
        Map<?, ?> range = (Map<?, ?>) params.get("range");
        int start = document.lines().offset((Map<?, ?>) range.get("start"));
        int end = document.lines().offset((Map<?, ?>) range.get("end"));
        Analysis.Rewrite rewrite = Analysis.formatRange(document.text(), start, end, width, indent(params), strict);
        if (rewrite == null) return null;
        if (rewrite.text().equals(document.text().substring(rewrite.start(), rewrite.end()))) return List.of();
        return List.of(
                Map.of("range", document.lines().range(rewrite.start(), rewrite.end()), "newText", rewrite.text()));
    }

    private Analysis analysis(Map<?, ?> params) {
        Document document = document(params);
        return new Analysis(path(uri(params)), document.text(), document.tree(), evaluator, trees);
    }

    /** The files of the workspace, read from the editor when open. */
    private final Analysis.Workspace trees = new Analysis.Workspace() {
        @Override
        public SyntaxTree tree(String path) {
            Document document = workspaceDocument(path);
            return document == null ? null : document.tree();
        }

        @Override
        public Map<String, SyntaxTree> files() {
            Map<String, SyntaxTree> files = new LinkedHashMap<>();
            workspace().forEach((path, document) -> files.put(path, document.tree()));
            return files;
        }
    };

    /** The location of {@code span}, in the document at {@code uri} when it names no file. */
    private Map<String, Object> location(String uri, Document document, Analysis.Span span) {
        if (span.path() == null)
            return Map.of("uri", uri, "range", document.lines().range(span.start(), span.end()));
        Document target = span.end() == 0 ? null : workspaceDocument(span.path());
        Map<String, Object> range = target != null
                ? target.lines().range(span.start(), span.end())
                : Map.of("start", ORIGIN, "end", ORIGIN);
        return Map.of("uri", file(span.path()), "range", range);
    }

    private Object definition(Map<?, ?> params) {
        Document document = document(params);
        Analysis.Span span = analysis(params).definition(offset(document, params));
        return span == null ? null : location(uri(params), document, span);
    }

    private Object references(Map<?, ?> params) {
        Document document = document(params);
        boolean declaration =
                params.get("context") instanceof Map<?, ?> c && Boolean.TRUE.equals(c.get("includeDeclaration"));
        Set<Object> locations = new LinkedHashSet<>();
        for (Analysis.Span span : analysis(params).references(offset(document, params), declaration))
            locations.add(location(uri(params), document, span));
        return List.copyOf(locations);
    }

    /** The evaluator for the document at {@code uri}: that of its devenv project when it is one of its modules. */
    private Evaluator evaluator(String uri) {
        return Analysis.scoped(evaluator, path(uri), trees);
    }

    /** The completion items with the {@code uri} of their project's document in their {@code data}. */
    private static Object withUri(Object completion, String uri) {
        List<?> items = completion instanceof Map<?, ?> list ? (List<?>) list.get("items") : (List<?>) completion;
        if (items == null || items.stream().noneMatch(i -> ((Map<?, ?>) i).get("data") instanceof Map<?, ?>))
            return completion;
        List<Object> withUri = new ArrayList<>();
        for (Object item : items) {
            Map<Object, Object> i = new LinkedHashMap<>((Map<?, ?>) item);
            if (i.get("data") instanceof Map<?, ?> data) {
                Map<Object, Object> d = new LinkedHashMap<>(data);
                d.put("uri", uri);
                i.put("data", d);
            }
            withUri.add(i);
        }
        if (!(completion instanceof Map<?, ?> list)) return withUri;
        return Map.of("isIncomplete", list.get("isIncomplete"), "items", withUri);
    }

    /** A file read from the editor when open, else from the disk once for each change; null if unreadable. */
    private Document workspaceDocument(String path) {
        Document open = documents.get(file(path));
        if (open != null) return open;
        File file = new File(path);
        Read cached = read.get(path);
        if (cached != null && cached.modified() == file.lastModified() && cached.length() == file.length())
            return cached.document();
        try (InputStream in = new FileInputStream(file)) {
            Document document = Document.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            read.put(path, new Read(file.lastModified(), file.length(), document));
            return document;
        } catch (IOException e) {
            return null;
        }
    }

    private record Read(long modified, long length, Document document) {}

    /** The {@code .nix} files of the open documents and under the workspace folders, hidden folders aside. */
    private Map<String, Document> workspace() {
        Map<String, Document> files = new LinkedHashMap<>();
        for (String uri : documents.keySet()) {
            String path = path(uri);
            if (path != null) files.put(path, documents.get(uri));
        }
        Deque<File> folders = new ArrayDeque<>();
        for (String root : roots) folders.add(new File(root));
        Set<String> seen = new HashSet<>();
        int count = 0;
        while (!folders.isEmpty() && count < WORKSPACE_FILES) {
            File folder = folders.poll();
            try {
                if (!seen.add(folder.getCanonicalPath())) continue;
            } catch (IOException e) {
                continue;
            }
            File[] entries = folder.listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!name.startsWith(".") && !SKIPPED.contains(name)) folders.add(entry);
                } else if (name.endsWith(".nix") && count++ < WORKSPACE_FILES) {
                    String path = entry.getPath();
                    if (files.containsKey(path)) continue;
                    Document document = workspaceDocument(path);
                    if (document != null) files.put(path, document);
                }
            }
        }
        return files;
    }

    /** The path of a {@code file:} URI, else null. */
    private static String path(String uri) {
        if (!uri.startsWith("file:")) return null;
        try {
            return new URI(uri).getPath();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** The definition as a write, its uses as reads. */
    private Object highlights(Map<?, ?> params) {
        Document document = document(params);
        Analysis.Uses uses = analysis(params).uses(offset(document, params));
        if (uses == null) return List.of();
        List<Object> highlights = new ArrayList<>();
        for (Node use : uses.uses())
            highlights.add(Map.of(
                    "range",
                    document.lines().range(use.start(), use.end()),
                    "kind",
                    use == uses.definition() ? 3L : 2L));
        return highlights;
    }

    /** The name under the cursor, or null where there is nothing to rename, as on a builtin. */
    private Object prepareRename(Map<?, ?> params) {
        Document document = document(params);
        Analysis.Span span = analysis(params).renamed(offset(document, params));
        if (span == null) return null;
        return Map.of(
                "range",
                document.lines().range(span.start(), span.end()),
                "placeholder",
                document.text().substring(span.start(), span.end()));
    }

    private Object rename(Map<?, ?> params) {
        Document document = document(params);
        List<Analysis.Span> spans = analysis(params).renames(offset(document, params));
        if (spans == null) return null;
        String name = (String) params.get("newName");
        Map<String, Set<Object>> changes = new LinkedHashMap<>();
        for (Analysis.Span span : spans) {
            Map<String, Object> location = location(uri(params), document, span);
            changes.computeIfAbsent((String) location.get("uri"), k -> new LinkedHashSet<>())
                    .add(Map.of("range", location.get("range"), "newText", name));
        }
        Map<String, Object> edits = new LinkedHashMap<>();
        changes.forEach((uri, set) -> edits.put(uri, List.copyOf(set)));
        return Map.of("changes", edits);
    }

    /** The completions as the protocol has them: a list, or with {@code isIncomplete} when evaluated. */
    private static Object completion(Analysis.Completions completions) {
        List<Object> items = new ArrayList<>();
        for (Analysis.Completion c : completions.items()) items.add(item(c));
        return completions.evaluated() ? Map.of("isIncomplete", completions.incomplete(), "items", items) : items;
    }

    private static Map<String, Object> item(Analysis.Completion c) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", c.label());
        item.put(
                "kind",
                switch (c.kind()) {
                    case VARIABLE -> 6L;
                    case ATTRIBUTE -> 5L;
                    case PROPERTY -> 10L;
                    case BUILTIN -> 3L;
                    case CONSTANT -> 21L;
                    case KEYWORD -> 14L;
                    case SNIPPET -> 15L;
                });
        if (c.detail() != null) item.put("detail", c.detail());
        if (c.snippet() != null) {
            item.put("filterText", c.label().substring(0, c.label().indexOf(' ')));
            item.put("insertText", c.snippet());
            item.put("insertTextFormat", 2L);
        }
        if (c.path() != null)
            item.put("data", c.option() ? Map.of("path", c.path(), "option", true) : Map.of("path", c.path()));
        return item;
    }

    /** The item with the version or type as its detail, and the description or doc comment as its documentation. */
    private Object resolve(Map<?, ?> item) {
        Map<Object, Object> resolved = new LinkedHashMap<>(item);
        if (evaluator == null
                || !(item.get("data") instanceof Map<?, ?> data)
                || !(data.get("path") instanceof List<?> path)) return resolved;
        List<String> names = path.stream().map(String.class::cast).toList();
        Evaluator evaluator = data.get("uri") instanceof String uri ? evaluator(uri) : this.evaluator;
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
        Analysis.Hover hover = analysis(params).hover(offset(document, params));
        if (hover == null) return null;
        return Map.of(
                "contents",
                Map.of("kind", "markdown", "value", hover.markdown()),
                "range",
                document.lines().range(hover.start(), hover.end()));
    }

    /** Where the nixpkgs attribute or the option at the position is defined, or null. */
    private Object attributeLocation(Map<?, ?> params) {
        Analysis.Location at = analysis(params).declaration(offset(document(params), params));
        if (at == null) return null;
        Map<String, Object> position = Map.of("line", (long) at.line(), "character", (long) at.column());
        return Map.of("uri", file(at.path()), "range", Map.of("start", position, "end", position));
    }

    /** The version of each package selected from nixpkgs in the range, after its selection. */
    private Object inlayHints(Map<?, ?> params) {
        Document document = document(params);
        Analysis analysis = analysis(params);
        Map<?, ?> range = (Map<?, ?>) params.get("range");
        int start = document.lines().offset((Map<?, ?>) range.get("start"));
        int end = document.lines().offset((Map<?, ?>) range.get("end"));
        Analysis.Hints hints = analysis.inlayHints(start, end);
        if (!hints.pending().isEmpty() && send != null && refreshSupport && refreshing.compareAndSet(false, true))
            analysis.evaluator().whenDescribed(hints.pending(), () -> {
                refreshing.set(false);
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("jsonrpc", "2.0");
                request.put("id", "affogato-" + ++requests);
                request.put("method", "workspace/inlayHint/refresh");
                send.accept(request);
            });
        List<Object> labels = new ArrayList<>();
        for (Analysis.Hint hint : hints.hints())
            labels.add(Map.of(
                    "position", document.lines().position(hint.offset()), "label", hint.label(), "paddingLeft", true));
        return labels;
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

    /** The paths, URIs and URL strings of the document. */
    private Object links(Map<?, ?> params) {
        Document document = document(params);
        List<Object> links = new ArrayList<>();
        for (Analysis.Link link : analysis(params).links())
            links.add(Map.of(
                    "range",
                    document.lines().range(link.start(), link.end()),
                    "target",
                    link.file() ? file(link.target()) : link.target()));
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
        Analysis analysis = analysis(params);
        for (Analysis.Rewrite rewrite : analysis.bindings(start)) rewrite(actions, uri, lines, rewrite);
        Analysis.Rewrite quotes = analysis.attributeQuotes(start);
        if (quotes == null) quotes = analysis.stringQuotes(start);
        if (quotes != null) rewrite(actions, uri, lines, quotes);
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

    private static void rewrite(List<Object> actions, String uri, Lines lines, Analysis.Rewrite rewrite) {
        Map<String, Object> action = action(
                rewrite.title(),
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
        Analysis.collect(document.tree().root(), node -> {
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
