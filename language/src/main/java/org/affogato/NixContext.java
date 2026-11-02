package org.affogato;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.source.Source;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.affogato.builtins.Builtins;
import org.affogato.builtins.Derivations;
import org.affogato.parser.Ast;
import org.affogato.parser.ParseException;
import org.affogato.parser.Parser;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.Thunk;
import org.affogato.store.Nar;
import org.affogato.store.StorePaths;

public final class NixContext {
    private static final ContextReference<NixContext> REF = ContextReference.create(NixLanguage.class);

    /** UTF-8 bytes of {@code «string»}, the file name Nix reports for expressions given on the command line. */
    public static final String STRING_SOURCE = "\u00c2\u00abstring\u00c2\u00bb";

    public static final int MAX_CALL_DEPTH = 10000;

    public final NixLanguage language;
    public int callDepth;
    public final Env env;
    public final String storeDir;
    public final String system;
    /** Search path entries as {@code [prefix, path]}; the prefix is empty for unprefixed entries. */
    public final List<String[]> nixPath = new ArrayList<>();

    public final Map<String, Object> globals = new HashMap<>();
    private final Map<String, Thunk> imports = new HashMap<>();
    private final Map<String, String> copiedPaths = new HashMap<>();

    NixContext(NixLanguage language, Env env) {
        this.language = language;
        this.env = env;
        String sd = env.getOptions().get(NixLanguage.StoreDir);
        this.storeDir = sd.isEmpty() ? System.getenv().getOrDefault("NIX_STORE_DIR", "/nix/store") : sd;
        String sys = env.getOptions().get(NixLanguage.System);
        this.system = sys.isEmpty() ? defaultSystem() : sys;
        String np = env.getOptions().get(NixLanguage.NixPath);
        if (np.isEmpty()) np = System.getenv().getOrDefault("NIX_PATH", "");
        for (String entry : np.split(":")) {
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            nixPath.add(
                    eq < 0 ? new String[] {"", entry} : new String[] {entry.substring(0, eq), entry.substring(eq + 1)});
        }
        Builtins.install(this);
    }

    private static String defaultSystem() {
        String arch = switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> System.getProperty("os.arch");
        };
        String os = System.getProperty("os.name").toLowerCase().contains("mac") ? "darwin" : "linux";
        return arch + "-" + os;
    }

    public static NixContext get() {
        return REF.get(null);
    }

    public NixAttrs builtins() {
        return (NixAttrs) globals.get("builtins");
    }

    @TruffleBoundary
    public RootCallTarget parse(String content, String fileName, String baseDir, String[] scopeNames) {
        Source source = Source.newBuilder(NixLanguage.ID, content, fileName).build();
        Ast.Expr expr;
        try {
            expr = Parser.parse(content);
            return Translator.translate(language, source, expr, fileName, baseDir, globals, scopeNames);
        } catch (ParseException e) {
            var sec = source.createSection(Math.min(e.offset, source.getLength()), 0);
            throw new NixException(
                    e.getMessage() + "\n       at " + fileName + ":" + sec.getStartLine() + ":" + sec.getStartColumn());
        }
    }

    /** Resolves {@code import} targets: directories mean their {@code default.nix}. */
    public static String resolveImport(String path) {
        if (path.startsWith(Derivations.COREPKGS)) return path;
        Path p = Path.of(path);
        try {
            // As resolveExprPath: follow the last component's links, relative to the unresolved parent.
            for (int i = 0; i < 1024 && Files.isSymbolicLink(p); i++) {
                p = p.resolveSibling(Files.readSymbolicLink(p)).normalize();
            }
        } catch (java.io.IOException e) {
            throw new NixException("cannot resolve '" + path + "': " + e.getMessage());
        }
        return Files.isDirectory(p) ? p + "/default.nix" : p.toString();
    }

    @TruffleBoundary
    public Object importFile(String path) {
        String file = resolveImport(path);
        Thunk t = imports.get(file);
        if (t == null) {
            RootCallTarget target = parse(readFile(file), file, parentDir(file), new String[0]);
            t = new Thunk(target, null);
            imports.put(file, t);
        }
        return t.force();
    }

    @TruffleBoundary
    public Object scopedImport(NixAttrs scope, String path) {
        String file = resolveImport(path);
        RootCallTarget target = parse(readFile(file), file, parentDir(file), scope.keys());
        return Thunk.force(target.call(new Object[] {null, scope}));
    }

    public static String parentDir(String file) {
        int i = file.lastIndexOf('/');
        return i <= 0 ? "/" : file.substring(0, i);
    }

    @TruffleBoundary
    public static String readFile(String path) {
        if (path.startsWith(Derivations.COREPKGS)) return readResource(path.substring(Derivations.COREPKGS.length()));
        try {
            return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new NixException("opening file '" + path + "': " + describe(e));
        }
    }

    private static String readResource(String name) {
        try (var in = NixContext.class.getResourceAsStream("corepkgs/" + name)) {
            if (in == null) throw new NixException("file '" + Derivations.COREPKGS + name + "' does not exist");
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String describe(IOException e) {
        return e instanceof java.nio.file.NoSuchFileException
                ? "No such file or directory"
                : String.valueOf(e.getMessage());
    }

    /** Computes the store path {@code path} would be copied to, without copying it. */
    @TruffleBoundary
    public String copyPathToStore(String path) {
        String sp = copiedPaths.get(path);
        if (sp != null) return sp;
        Path p = Path.of(path);
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new NixException("path '" + path + "' does not exist");
        }
        String name = org.affogato.runtime.Coerce.baseName(path);
        try {
            StorePaths.checkName(name);
            sp = StorePaths.source(storeDir, name, Nar.hash("sha256", p), List.of());
        } catch (IOException e) {
            throw new NixException("cannot read '" + path + "': " + describe(e));
        } catch (IllegalArgumentException e) {
            throw new NixException("invalid store path name: " + e.getMessage());
        }
        copiedPaths.put(path, sp);
        return sp;
    }
}
