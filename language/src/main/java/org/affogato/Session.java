package org.affogato;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.affogato.runtime.Apply;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.NixLambda;
import org.affogato.runtime.NixList;
import org.affogato.runtime.NixNull;
import org.affogato.runtime.NixString;
import org.affogato.runtime.Printer;
import org.affogato.runtime.SourceSection;
import org.affogato.runtime.Thunk;
import org.affogato.runtime.Types;

/**
 * Expressions evaluated in one context, on a thread of its own, and kept between queries, for a long-lived client such
 * as the language server, which resets them when files change. Sharing the context, they share what they evaluate in
 * common, such as the flake that {@code builtins.getFlake} reads for both a configuration's packages and its options.
 */
public final class Session implements AutoCloseable {
    private static final int MAX_QUERIES = 1024;

    /**
     * What an attribute is: its {@code builtins.typeOf}; or {@code derivation}, with {@code meta.longDescription}
     * after the description; or {@code option}, with the description of its type as its {@code value}; or
     * {@code error}, with the message as its {@code value}. Any other field may be null, and {@code line} and
     * {@code column} count from 1.
     */
    public record Description(
            String type,
            String value,
            String name,
            String version,
            String description,
            String homepage,
            String doc,
            String defaultValue,
            String example,
            String file,
            int line,
            int column) {
        public static final Description NONE = of(null, null);

        static Description of(String type, String value) {
            return new Description(type, value, null, null, null, null, null, null, null, null, 0, 0);
        }

        Description at(String file, int line, int column) {
            return new Description(
                    type, value, name, version, description, homepage, doc, defaultValue, example, file, line, column);
        }
    }

    /**
     * An expression to query; with {@code options}, a tree of module system options, as a NixOS configuration's
     * {@code options}, where a path goes through the sub-options of a submodule, and takes any name for each attribute
     * of an {@code attrsOf} one.
     */
    public record Root(String expression, boolean options) {}

    private record Query(String root, boolean describe, List<String> path) {}

    private static final String DEFAULT = "";
    private static final String ARGUMENTS = "args:";

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
    private Map<String, Root> roots = Map.of();
    private String baseDir;
    private volatile NixContext ctx;
    /** The value of each root evaluated in {@link #ctx}, null for one that failed; touched by the thread alone. */
    private final Map<String, Object> values = new HashMap<>();
    /** The root and kind the query under way reads, set by the thread before each. */
    private Object root;

    private boolean options;

    private volatile BiConsumer<String, String> failures = (root, message) -> {};

    private volatile Predicate<String> inputs;

    /** One expression, queried by {@link #names(List)} and {@link #describe(List)}. */
    public Session(String nixPath, String system, String storeDir, String expression, String baseDir) {
        this(nixPath, system, storeDir, expression, baseDir, false);
    }

    /** One expression, a tree of options when {@code options}, as in {@link Root}. */
    public Session(String nixPath, String system, String storeDir, String expression, String baseDir, boolean options) {
        this(nixPath, system, storeDir);
        configure(Map.of(DEFAULT, new Root(expression, options)), baseDir);
    }

    /** No expression until {@link #configure}; empty values take the defaults, as in {@link Interpreter.Options}. */
    public Session(String nixPath, String system, String storeDir) {
        this.nixPath = nixPath;
        this.system = system;
        this.storeDir = storeDir;
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
    public CompletableFuture<List<String>> names(List<String> path) {
        return names(DEFAULT, path);
    }

    /** As {@link #names(List)}, in the root of that name; empty when there is none. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<String>> names(String root, List<String> path) {
        return (CompletableFuture<List<String>>) query(new Query(root, false, List.copyOf(path)), this::namesAt);
    }

    /** The attribute at {@code path}, a non-empty path, not auto-called itself; all null when there is none. */
    public CompletableFuture<Description> describe(List<String> path) {
        return describe(DEFAULT, path);
    }

    /** As {@link #describe(List)}, in the root of that name. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Description> describe(String root, List<String> path) {
        return (CompletableFuture<Description>) query(new Query(root, true, List.copyOf(path)), this::describeAt);
    }

    private synchronized CompletableFuture<?> query(Query q, Function<List<String>, Object> f) {
        CompletableFuture<?> future = queries.get(q);
        if (future == null) {
            future = CompletableFuture.supplyAsync(() -> evaluate(q.root(), q.path(), f), thread);
            queries.put(q, future);
        }
        return future;
    }

    /** Evaluates these roots, by name, relative to {@code baseDir}, from now on. */
    public synchronized void configure(Map<String, Root> roots, String baseDir) {
        this.roots = Map.copyOf(roots);
        this.baseDir = baseDir;
        reset();
    }

    /** Evaluates {@code expression} as the one root, relative to {@code baseDir}, from now on. */
    public void configure(String expression, String baseDir) {
        configure(Map.of(DEFAULT, new Root(expression, false)), baseDir);
    }

    /** Drops every value, stopping the evaluation under way, so that the next query reads the files again. */
    public synchronized void reset() {
        queries.clear();
        NixContext c = ctx;
        if (c != null) c.cancelled = true;
        thread.execute(() -> ctx = null);
    }

    /**
     * Drops every value, as {@link #reset()}, when one may depend on the file at {@code path}, an absolute normalized
     * path, as one the evaluation read or a folder it listed holds; whether it did.
     */
    public synchronized boolean reset(String path) {
        NixContext c = ctx;
        if (c != null && !c.dependsOn(path)) return false;
        reset();
        return true;
    }

    /** Lets {@code inputs} copy into the store the locked flake inputs missing from it, as in {@link NixContext#inputs}. */
    public void inputs(Predicate<String> inputs) {
        this.inputs = inputs;
    }

    /**
     * Tells {@code failures} the name and the error of each root, or view of its arguments, whose evaluation fails,
     * once until the next {@link #reset}; queries of it then answer as if it were empty.
     */
    public void failures(BiConsumer<String, String> failures) {
        this.failures = failures;
    }

    /**
     * The name of the view of an options root as the arguments its modules receive: {@code _module.args} and, over
     * them, {@code _module.specialArgs}, which the module system takes first.
     */
    public static String arguments(String optionsRoot) {
        return ARGUMENTS + optionsRoot;
    }

    private Object evaluate(String name, List<String> path, Function<List<String>, Object> f) {
        boolean arguments = name.startsWith(ARGUMENTS);
        String rootName = arguments ? name.substring(ARGUMENTS.length()) : name;
        Root r;
        String dir;
        synchronized (this) {
            r = roots.get(rootName);
            dir = baseDir;
        }
        if (r == null || arguments && !r.options()) return f.apply(null);
        String failing = null;
        try {
            if (ctx == null) {
                ctx = new NixContext(storeDir, system, nixPath);
                ctx.inputs = inputs;
                values.clear();
            }
            ctx.enter();
            if (!values.containsKey(rootName)) {
                failing = rootName;
                values.put(rootName, null);
                values.put(
                        rootName,
                        Interpreter.autoCall(
                                Thunk.force(ctx.parse(r.expression(), NixContext.STRING_SOURCE, dir, new String[0])
                                        .call(null, null)),
                                Map.of()));
            }
            if (arguments && !values.containsKey(name)) {
                failing = name;
                values.put(name, null);
                values.put(name, moduleArguments(values.get(rootName)));
            }
            failing = null;
            root = values.get(name);
            options = r.options() && !arguments;
            return f.apply(path);
        } catch (NixException | StackOverflowError e) {
            if (failing != null && (ctx == null || !ctx.cancelled))
                failures.accept(failing, e instanceof StackOverflowError ? "stack overflow" : e.getMessage());
            return f.apply(null);
        } finally {
            NixContext.leave();
        }
    }

    private static NixAttrs moduleArguments(Object options) {
        if (!(Thunk.force(options) instanceof NixAttrs o) || !(field(o, "_module") instanceof NixAttrs module))
            return null;
        NixAttrs args = field(module, "args") instanceof NixAttrs a && field(a, "value") instanceof NixAttrs v
                ? v
                : NixAttrs.EMPTY;
        return field(module, "specialArgs") instanceof NixAttrs a && field(a, "value") instanceof NixAttrs v
                ? args.update(v)
                : args;
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
        if (path == null) return List.of();
        if (!options) {
            NixAttrs a = set(path);
            return a == null ? List.of() : List.of(a.keys());
        }
        NixAttrs a = optionAt(path);
        if (a != null && isOption(a)) a = isNamed(a) ? null : subOptions(a);
        return a == null
                ? List.of()
                : List.of(a.keys()).stream().filter(n -> !n.startsWith("_")).toList();
    }

    /** The option or group of options at {@code path}, or null. */
    private NixAttrs optionAt(List<String> path) {
        if (root == null || !(Thunk.force(root) instanceof NixAttrs a)) return null;
        for (String key : path) {
            if (isOption(a)) {
                boolean named = isNamed(a);
                a = subOptions(a);
                if (a == null) return null;
                if (named) continue;
            }
            if (!(field(a, key) instanceof NixAttrs next)) return null;
            a = next;
        }
        return a;
    }

    private static boolean isOption(NixAttrs a) {
        return field(a, "_type") instanceof NixString t && t.value.equals("option");
    }

    /** Whether the option is an {@code attrsOf}, whose attributes each take a name of their own. */
    private static boolean isNamed(NixAttrs option) {
        return field(option, "type") instanceof NixAttrs type
                && field(type, "name") instanceof NixString n
                && (n.value.equals("attrsOf") || n.value.equals("lazyAttrsOf"));
    }

    private static NixAttrs subOptions(NixAttrs option) {
        if (!(field(option, "type") instanceof NixAttrs type) || field(type, "getSubOptions") == null) return null;
        try {
            return Thunk.force(Apply.apply(type.get("getSubOptions"), NixList.EMPTY)) instanceof NixAttrs a
                            && !a.isEmpty()
                    ? a
                    : null;
        } catch (NixException e) {
            return null;
        }
    }

    private Object describeOption(List<String> path) {
        NixAttrs a = optionAt(path);
        if (a == null || !isOption(a)) return Description.NONE;
        String type = field(a, "type") instanceof NixAttrs t ? string(field(t, "description")) : null;
        Description d = new Description(
                "option",
                type,
                null,
                null,
                text(field(a, "description")),
                null,
                null,
                text(field(a, a.has("defaultText") ? "defaultText" : "default")),
                text(field(a, "example")),
                null,
                0,
                0);
        if (field(a, "declarationPositions") instanceof NixList l
                && l.size() > 0
                && Thunk.force(l.get(0)) instanceof NixAttrs p
                && string(field(p, "file")) != null
                && field(p, "line") instanceof Long line
                && field(p, "column") instanceof Long column)
            return d.at(string(field(p, "file")), (int) (long) line, (int) (long) column);
        return d;
    }

    /** A string, the text of a {@code literalExpression} or {@code mdDoc}, a scalar, or an empty set or list. */
    private static String text(Object v) {
        if (v instanceof NixString s) return s.value;
        if (v instanceof NixAttrs a && field(a, "text") instanceof NixString t) return t.value;
        if (v instanceof Long || v instanceof Double || v instanceof Boolean || v == NixNull.INSTANCE)
            return Printer.print(v);
        if (v instanceof NixAttrs a && a.isEmpty()) return "{ }";
        if (v instanceof NixList l && l.size() == 0) return "[ ]";
        return null;
    }

    private Object describeAt(List<String> path) {
        if (path == null || path.isEmpty()) return Description.NONE;
        if (options) return describeOption(path);
        NixAttrs parent = set(path.subList(0, path.size() - 1));
        String key = path.getLast();
        if (parent == null || !parent.has(key)) return Description.NONE;
        SourceSection at = parent.position(key);
        Object v;
        try {
            v = Thunk.force(parent.get(key));
        } catch (NixException e) {
            return position(Description.of("error", e.getMessage()), at);
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
                    null,
                    null,
                    0,
                    0);
            String position = string(field(meta, "position"));
            int colon = position == null ? -1 : position.lastIndexOf(':');
            if (colon > 0 && position.substring(colon + 1).chars().allMatch(Character::isDigit))
                return d.at(position.substring(0, colon), Integer.parseInt(position.substring(colon + 1)), 1);
            return position(d, at);
        }
        String value = v instanceof Long || v instanceof Double || v instanceof Boolean || v instanceof NixString
                ? Printer.print(v)
                : null;
        SourceSection defined = v instanceof NixLambda l && l.info.pos() != null ? l.info.pos() : at;
        String doc = defined == null ? null : doc(defined);
        if (doc == null && at != null && at != defined) doc = doc(at);
        return position(
                new Description(Types.typeOf(v), value, null, null, null, null, doc, null, null, null, 0, 0),
                defined == null ? at : defined);
    }

    private static Description position(Description d, SourceSection at) {
        if (at == null || at.getSource().getName().equals(NixContext.STRING_SOURCE)) return d;
        return d.at(at.getSource().getName(), at.getStartLine(), at.getStartColumn());
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
