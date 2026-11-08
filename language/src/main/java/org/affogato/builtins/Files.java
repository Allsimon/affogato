package org.affogato.builtins;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiPredicate;
import org.affogato.NixContext;
import org.affogato.runtime.*;
import org.affogato.store.Hashes;
import org.affogato.store.Nar;
import org.affogato.store.NixBase32;
import org.affogato.store.StorePaths;

final class Files {
    private Files() {}

    /** Coerces a path or absolute path string to a filesystem path, as {@code realisePath} does. */
    static String realPath(Object v) {
        v = Thunk.force(v);
        if (v instanceof NixPath p) return p.path();
        String s = Coerce.toNixString(v, false, false).value;
        if (!s.startsWith("/")) throw new NixException("string '" + s + "' doesn't represent an absolute path");
        return Coerce.canonicalize(s);
    }

    static void register(Builtins b) {
        b.add("pathExists", 1, a -> {
            Object v = Thunk.force(a[0]);
            String s = realPath(v);
            boolean mustBeDir = v instanceof NixString str && (str.value.endsWith("/") || str.value.endsWith("/."));
            Path p = Path.of(s);
            return mustBeDir
                    ? java.nio.file.Files.isDirectory(p)
                    : java.nio.file.Files.exists(p, LinkOption.NOFOLLOW_LINKS);
        });
        b.add("readFile", 1, a -> new NixString(NixContext.readFile(realPath(a[0]))));
        b.add("readFileType", 1, a -> new NixString(Nar.typeOf(Path.of(realPath(a[0])))));
        b.add("readDir", 1, a -> {
            Path dir = Path.of(realPath(a[0]));
            try {
                return DirCache.read(dir);
            } catch (IOException e) {
                throw new NixException("reading directory '" + dir + "': " + NixContext.describe(e));
            }
        });
        b.add("hashFile", 2, a -> {
            String algo = Types.string(a[0]).value;
            return new NixString(Hashes.hex(Hashes.hash(algo, NixContext.readFile(realPath(a[1])))));
        });
        b.add("toPath", 1, a -> new NixString(realPath(a[0])));
        b.add("storePath", 1, a -> {
            String p = realPath(a[0]);
            NixContext ctx = NixContext.get();
            if (!p.startsWith(ctx.storeDir + "/")) throw new NixException("path '" + p + "' is not in the Nix store");
            String root = storePathRoot(ctx, p);
            return new NixString(p, Set.of(root));
        });
        b.add("getEnv", 1, a -> new NixString(System.getenv().getOrDefault(Types.string(a[0]).value, "")));
        b.add("placeholder", 1, a -> {
            String out = Types.string(a[0]).value;
            return new NixString("/" + NixBase32.encode(Hashes.sha256("nix-output:" + out)));
        });
        b.add("findFile", 2, a -> findFile(NixContext.get(), Types.list(a[0]), Types.string(a[1]).value));
        b.add("toFile", 2, a -> {
            String name = Types.string(a[0]).value;
            NixString contents = Types.string(a[1]);
            java.util.List<String> refs = new java.util.ArrayList<>();
            if (contents.context != null) {
                for (String c : contents.context) {
                    if (c.startsWith("!")) {
                        throw new NixException("files created by builtins.toFile may not reference derivations, but '"
                                + name + "' references '" + c + "'");
                    }
                    refs.add(c.startsWith("=") ? c.substring(1) : c);
                }
            }
            checkName(name);
            String p = StorePaths.text(NixContext.get().storeDir, name, contents.value, refs);
            return new NixString(p, Set.of(p));
        });
        b.add("filterSource", 2, a -> addPath(NixContext.get(), realPath(a[1]), null, a[0], true, null));
        b.add("path", 1, a -> {
            NixAttrs args = Types.attrs(a[0]);
            Object pathV = args.get("path");
            if (pathV == null)
                throw new NixException("missing required 'path' attribute in the first argument to builtins.path");
            Object name = args.get("name");
            Object filter = args.getRaw("filter");
            Object rec = args.get("recursive");
            Object sha = args.get("sha256");
            return addPath(
                    NixContext.get(),
                    realPath(pathV),
                    name == null ? null : Types.string(name).value,
                    filter,
                    rec == null || Types.bool(rec),
                    sha == null ? null : Types.string(sha).value);
        });
    }

    static String utf8ToBytes(String s) {
        return new String(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    static void checkName(String name) {
        try {
            StorePaths.checkName(name);
        } catch (IllegalArgumentException e) {
            throw new NixException("invalid store path name: " + e.getMessage());
        }
    }

    static String storePathRoot(NixContext ctx, String p) {
        int slash = p.indexOf('/', ctx.storeDir.length() + 1);
        return slash < 0 ? p : p.substring(0, slash);
    }

    private static Object addPath(
            NixContext ctx, String path, String name, Object filter, boolean recursive, String expected) {
        if (name == null) name = Coerce.baseName(path);
        checkName(name);
        Path p = Path.of(path);
        if (!java.nio.file.Files.exists(p, LinkOption.NOFOLLOW_LINKS))
            throw new NixException("path '" + path + "' does not exist");
        String sp;
        try {
            if (expected != null) {
                byte[] h = Derivations.parseHash(expected, "sha256");
                sp = StorePaths.fixedOutput(ctx.storeDir, name, "sha256", h, recursive);
            } else if (!recursive) {
                byte[] h = Hashes.sha256(NixContext.readFile(path));
                sp = StorePaths.fixedOutput(ctx.storeDir, name, "sha256", h, false);
            } else {
                BiPredicate<Path, String> pred = filter == null
                        ? null
                        : (child, type) -> Types.bool(Apply.apply(
                                Apply.apply(filter, new NixString(utf8ToBytes(child.toString()))),
                                new NixString(type)));
                sp = StorePaths.source(ctx.storeDir, name, Nar.hash("sha256", p, pred), java.util.List.of());
            }
        } catch (IOException e) {
            throw new NixException("cannot read '" + path + "': " + NixContext.describe(e));
        }
        return new NixString(sp, Set.of(sp));
    }

    private static Object findFile(NixContext ctx, NixList searchPath, String path) {
        if (path.equals("nix/fetchurl.nix")) return new NixPath(Derivations.FETCHURL_PATH);
        for (Object entry : searchPath.items) {
            NixAttrs e = Types.attrs(entry);
            Object prefixV = e.get("prefix");
            String prefix = prefixV == null ? "" : Types.string(prefixV).value;
            Object pv = e.get("path");
            if (pv == null) throw new NixException("attribute 'path' missing");
            String base = Coerce.toNixString(pv, false, false).value;
            String rest;
            if (prefix.isEmpty()) {
                rest = path;
            } else if (path.equals(prefix)) {
                rest = "";
            } else if (path.startsWith(prefix + "/")) {
                rest = path.substring(prefix.length() + 1);
            } else {
                continue;
            }
            if (base.contains("://")) continue;
            String candidate = Coerce.canonicalize(rest.isEmpty() ? base : base + "/" + rest);
            if (java.nio.file.Files.exists(Path.of(candidate))) return new NixPath(candidate);
        }
        throw Builtins.catchable(
                "file '" + path + "' was not found in the Nix search path (add it using $NIX_PATH or -I)");
    }
}
