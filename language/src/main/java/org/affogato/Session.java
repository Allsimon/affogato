package org.affogato;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.NixLambda;
import org.affogato.runtime.NixList;
import org.affogato.runtime.NixString;
import org.affogato.runtime.Printer;
import org.affogato.runtime.SourceSection;
import org.affogato.runtime.Thunk;
import org.affogato.runtime.Types;

/**
 * An expression evaluated on a thread of its own and kept between queries, for a long-lived client such as the
 * language server, which resets it when files change.
 */
public final class Session implements AutoCloseable {
    private static final int MAX_QUERIES = 1024;

    /**
     * What an attribute is: its {@code builtins.typeOf}, or {@code derivation} with {@code meta.longDescription} after
     * the description, or {@code error} with the message as its {@code value}; any other field may be null, and
     * {@code line} and {@code column} count from 1.
     */
    public record Description(
            String type,
            String value,
            String name,
            String version,
            String description,
            String homepage,
            String doc,
            String file,
            int line,
            int column) {
        public static final Description NONE = new Description(null, null, null, null, null, null, null, null, 0, 0);
    }

    private record Query(boolean describe, List<String> path) {}

    private final String nixPath;
    private final String system;
    private final String storeDir;
    private final ExecutorService thread;
    private final Map<Query, CompletableFuture<?>> queries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Query, CompletableFuture<?>> eldest) {
            return size() > MAX_QUERIES;
        }
    };
    private String expression;
    private String baseDir;
    private volatile NixContext ctx;
    private Object root;

    /** Empty values take the defaults, as in {@link Interpreter.Options}. */
    public Session(String nixPath, String system, String storeDir, String expression, String baseDir) {
        this.nixPath = nixPath;
        this.system = system;
        this.storeDir = storeDir;
        this.expression = expression;
        this.baseDir = baseDir;
        thread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(null, r, "nix-session", Interpreter.STACK_SIZE);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The sorted attribute names of the value at {@code path} in the expression, auto-called at each step as
     * {@code nix-instantiate} does; empty when it is no attribute set or fails to evaluate.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<String>> names(List<String> path) {
        return (CompletableFuture<List<String>>) query(new Query(false, List.copyOf(path)), this::namesAt);
    }

    /** The attribute at {@code path}, a non-empty path, not auto-called itself; all null when there is none. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Description> describe(List<String> path) {
        return (CompletableFuture<Description>) query(new Query(true, List.copyOf(path)), this::describeAt);
    }

    private synchronized CompletableFuture<?> query(Query q, Function<List<String>, Object> f) {
        CompletableFuture<?> future = queries.get(q);
        if (future == null) {
            future = CompletableFuture.supplyAsync(() -> evaluate(q.path(), f), thread);
            queries.put(q, future);
        }
        return future;
    }

    /** Evaluates {@code expression}, relative to {@code baseDir}, from now on. */
    public synchronized void configure(String expression, String baseDir) {
        this.expression = expression;
        this.baseDir = baseDir;
        reset();
    }

    /** Drops every value, stopping the evaluation under way, so that the next query reads the files again. */
    public synchronized void reset() {
        queries.clear();
        NixContext c = ctx;
        if (c != null) c.cancelled = true;
        thread.execute(() -> ctx = null);
    }

    private Object evaluate(List<String> path, Function<List<String>, Object> f) {
        String source;
        String dir;
        synchronized (this) {
            source = expression;
            dir = baseDir;
        }
        try {
            if (ctx == null) {
                NixContext c = new NixContext(storeDir, system, nixPath);
                ctx = c;
                c.enter();
                root = null;
                root = Interpreter.autoCall(
                        Thunk.force(c.parse(source, NixContext.STRING_SOURCE, dir, new String[0])
                                .call(null, null)),
                        Map.of());
            } else {
                ctx.enter();
            }
            return f.apply(path);
        } catch (NixException | StackOverflowError e) {
            return f.apply(null);
        } finally {
            NixContext.leave();
        }
    }

    /** The attribute set at {@code path}, or null. */
    private NixAttrs set(List<String> path) {
        if (root == null) return null;
        Object v = root;
        for (String key : path) {
            if (!(Thunk.force(v) instanceof NixAttrs a) || (v = a.get(key)) == null) return null;
            v = Interpreter.autoCall(v, Map.of());
        }
        return Thunk.force(v) instanceof NixAttrs a ? a : null;
    }

    private Object namesAt(List<String> path) {
        NixAttrs a = path == null ? null : set(path);
        return a == null ? List.of() : List.of(a.keys());
    }

    private Object describeAt(List<String> path) {
        if (path == null || path.isEmpty()) return Description.NONE;
        NixAttrs parent = set(path.subList(0, path.size() - 1));
        String key = path.getLast();
        if (parent == null || !parent.has(key)) return Description.NONE;
        SourceSection at = parent.position(key);
        Object v;
        try {
            v = Thunk.force(parent.get(key));
        } catch (NixException e) {
            return position(new Description("error", e.getMessage(), null, null, null, null, null, null, 0, 0), at);
        }
        if (v instanceof NixAttrs a && a.isDerivation()) {
            NixAttrs meta = field(a, "meta") instanceof NixAttrs m ? m : NixAttrs.EMPTY;
            Object homepage = field(meta, "homepage");
            if (homepage instanceof NixList l && l.size() > 0) homepage = Thunk.force(l.get(0));
            Description d = new Description(
                    "derivation",
                    null,
                    string(field(a, "pname")) != null ? string(field(a, "pname")) : string(field(a, "name")),
                    string(field(a, "version")),
                    join(string(field(meta, "description")), string(field(meta, "longDescription"))),
                    string(homepage),
                    null,
                    null,
                    0,
                    0);
            String position = string(field(meta, "position"));
            int colon = position == null ? -1 : position.lastIndexOf(':');
            if (colon > 0 && position.substring(colon + 1).chars().allMatch(Character::isDigit))
                return new Description(
                        d.type(),
                        null,
                        d.name(),
                        d.version(),
                        d.description(),
                        d.homepage(),
                        null,
                        position.substring(0, colon),
                        Integer.parseInt(position.substring(colon + 1)),
                        1);
            return position(d, at);
        }
        String value = v instanceof Long || v instanceof Double || v instanceof Boolean || v instanceof NixString
                ? Printer.print(v)
                : null;
        SourceSection defined = v instanceof NixLambda l && l.info.pos() != null ? l.info.pos() : at;
        String doc = defined == null ? null : doc(defined);
        if (doc == null && at != null && at != defined) doc = doc(at);
        return position(
                new Description(Types.typeOf(v), value, null, null, null, null, doc, null, 0, 0),
                defined == null ? at : defined);
    }

    private static Description position(Description d, SourceSection at) {
        if (at == null || at.getSource().getName().equals(NixContext.STRING_SOURCE)) return d;
        return new Description(
                d.type(),
                d.value(),
                d.name(),
                d.version(),
                d.description(),
                d.homepage(),
                d.doc(),
                at.getSource().getName(),
                at.getStartLine(),
                at.getStartColumn());
    }

    private static Object field(NixAttrs a, String key) {
        try {
            Object v = a.get(key);
            return v == null ? null : Thunk.force(v);
        } catch (NixException e) {
            return null;
        }
    }

    private static String join(String description, String longDescription) {
        if (longDescription == null || longDescription.isBlank()) return description;
        return description == null ? longDescription.strip() : description + "\n\n" + longDescription.strip();
    }

    private static String string(Object v) {
        return v instanceof NixString s ? s.value : null;
    }

    /**
     * The {@code /** ... *}{@code /} comment just before {@code at}, or before the {@code name =} that binds what starts
     * at {@code at}, without its common indentation.
     */
    static String doc(SourceSection at) {
        CharSequence text = at.getSource().getCharacters();
        int i = back(text, at.getCharIndex());
        if (i > 0 && text.charAt(i - 1) == '=') {
            i = back(text, i - 1);
            while (i > 0 && isAttrChar(text.charAt(i - 1))) i--;
            i = back(text, i);
        }
        if (i < 4 || text.charAt(i - 1) != '/' || text.charAt(i - 2) != '*') return null;
        String before = text.subSequence(0, i - 2).toString();
        int open = before.lastIndexOf("/**");
        if (open < 0 || before.indexOf("*/", open) >= 0) return null;
        return dedent(before.substring(open + 3));
    }

    private static int back(CharSequence text, int i) {
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    private static boolean isAttrChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-' || c == '.' || c == '"';
    }

    private static String dedent(String s) {
        List<String> lines = List.of(s.split("\n", -1));
        int indent = Integer.MAX_VALUE;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ') n++;
            indent = Math.min(indent, n);
        }
        StringBuilder out = new StringBuilder(lines.getFirst().strip());
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            out.append('\n').append(line.isBlank() ? "" : line.substring(Math.min(indent, line.length())));
        }
        return out.toString().strip();
    }

    @Override
    public void close() {
        NixContext c = ctx;
        if (c != null) c.cancelled = true;
        thread.shutdownNow();
    }
}
