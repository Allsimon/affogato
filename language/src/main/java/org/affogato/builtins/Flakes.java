package org.affogato.builtins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.affogato.NixContext;
import org.affogato.runtime.Apply;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.NixString;
import org.affogato.runtime.Thunk;
import org.affogato.runtime.Types;
import org.affogato.store.Hashes;
import org.affogato.store.StorePaths;

/**
 * Flakes as far as an evaluator that does not fetch can take them: locked inputs in the store, or copied there by
 * {@link NixContext#inputs}, and local flakes read in place.
 */
final class Flakes {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** A lock file for a flake without one, which only works when it has no inputs. */
    private static final String EMPTY_LOCK = "{\"nodes\":{\"root\":{}},\"root\":\"root\",\"version\":7}";

    private Flakes() {}

    static void register(Builtins b) {
        b.add("fetchTree", 1, a -> fetchTree(Types.attrs(a[0])));
        b.add("getFlake", 1, a -> getFlake(Types.string(a[0]).value));
    }

    /**
     * A locked input: the store path its {@code narHash} names, which Nix's fetchers give every input, with the
     * metadata of the lock; a local {@code path} input not in the store is read in place.
     */
    private static Object fetchTree(NixAttrs input) {
        String narHash = string(input, "narHash");
        if (narHash == null || !narHash.startsWith("sha256-"))
            throw new NixException(
                    "'builtins.fetchTree' is supported by affogato only for locked inputs, with a" + " sha256 narHash");
        NixContext ctx = NixContext.get();
        String path = StorePaths.fixedOutput(
                ctx.storeDir, "source", "sha256", Hashes.base64Decode(narHash.substring(7)), true);
        TreeMap<String, Object> tree = new TreeMap<>();
        String ref = ctx.inputs == null || Files.exists(Path.of(path)) ? null : reference(input);
        if (ref != null) ctx.inputs.test(ref);
        if (Files.exists(Path.of(path))) {
            tree.put("outPath", new NixString(path, new TreeSet<>(Set.of(path))));
        } else if ("path".equals(string(input, "type"))
                && string(input, "path") != null
                && Files.isDirectory(Path.of(string(input, "path")))) {
            tree.put("outPath", new NixString(string(input, "path")));
        } else {
            throw new NixException("input " + describe(input) + " is not in the Nix store at " + path
                    + "; `nix flake archive` fetches the inputs of a flake");
        }
        tree.put("narHash", new NixString(narHash));
        if (input.get("lastModified") != null && Thunk.force(input.get("lastModified")) instanceof Long modified) {
            tree.put("lastModified", modified);
            tree.put("lastModifiedDate", new NixString(DATE.format(Instant.ofEpochSecond(modified))));
        }
        String rev = string(input, "rev");
        if (rev != null) {
            tree.put("rev", new NixString(rev));
            tree.put("shortRev", new NixString(rev.substring(0, Math.min(7, rev.length()))));
        }
        for (String key : new String[] {"revCount", "submodules"})
            if (input.get(key) != null) tree.put(key, Thunk.force(input.get(key)));
        return NixAttrs.of(tree);
    }

    /**
     * The flake at a local reference, {@code /dir}, {@code path:/dir} or {@code git+file:///dir}, each with an optional
     * {@code ?dir=sub}; its own files are read where they are, rather than from a copy in the store.
     */
    private static Object getFlake(String ref) {
        NixContext ctx = NixContext.get();
        return ctx.flakes
                .computeIfAbsent(ref, r -> new Thunk(() -> callFlake(ctx, r)))
                .force();
    }

    private static Object callFlake(NixContext ctx, String ref) {
        String location = ref;
        String dir = "";
        int query = location.indexOf('?');
        if (query >= 0) {
            for (String param : location.substring(query + 1).split("&"))
                if (param.startsWith("dir=")) dir = param.substring(4);
            location = location.substring(0, query);
        }
        for (String scheme : new String[] {"git+file://", "git+file:", "path:"})
            if (location.startsWith(scheme)) {
                location = location.substring(scheme.length());
                break;
            }
        if (!location.startsWith("/"))
            throw new NixException(
                    "'builtins.getFlake' is supported by affogato only for local flakes, not '" + ref + "'");
        Path root = Path.of(location).normalize();
        Path flake = dir.isEmpty() ? root : root.resolve(dir);
        NixContext.read(flake.resolve("flake.nix").toString());
        NixContext.read(flake.resolve("flake.lock").toString());
        if (!Files.isRegularFile(flake.resolve("flake.nix")))
            throw new NixException("path '" + flake + "' does not contain a 'flake.nix'");
        String lock;
        try {
            Path lockFile = flake.resolve("flake.lock");
            lock = Files.isRegularFile(lockFile) ? Files.readString(lockFile) : EMPTY_LOCK;
        } catch (IOException e) {
            throw new NixException("reading the lock file of '" + flake + "': " + NixContext.describe(e));
        }
        TreeMap<String, Object> sourceInfo = new TreeMap<>();
        sourceInfo.put("outPath", new NixString(root.toString()));
        sourceInfo.put("lastModified", 0L);
        sourceInfo.put("lastModifiedDate", new NixString(DATE.format(Instant.EPOCH)));
        if (Files.exists(root.resolve(".git"))) {
            sourceInfo.put("dirtyRev", new NixString("0".repeat(40) + "-dirty"));
            sourceInfo.put("dirtyShortRev", new NixString("0000000-dirty"));
        }
        NixAttrs overrides = NixAttrs.of(
                Map.of("root", NixAttrs.of(Map.of("sourceInfo", NixAttrs.of(sourceInfo), "dir", new NixString(dir)))));
        Object call = ctx.importFile(Derivations.COREPKGS + "call-flake.nix");
        return Thunk.force(Apply.apply(
                Apply.apply(call, new NixString(lock), overrides),
                ctx.builtins().get("fetchTree")));
    }

    /** The flake reference of a locked input, or null for a type it does not cover. */
    static String reference(NixAttrs input) {
        String type = string(input, "type");
        String rev = string(input, "rev");
        String url = string(input, "url");
        if (type == null) return null;
        return switch (type) {
            case "github", "gitlab", "sourcehut" -> {
                String owner = string(input, "owner");
                String repo = string(input, "repo");
                String host = string(input, "host");
                yield owner == null || repo == null || rev == null
                        ? null
                        : type + ":" + owner + "/" + repo + "/" + rev + (host == null ? "" : "?host=" + host);
            }
            case "git" ->
                url == null || rev == null
                        ? null
                        : (url.startsWith("git+") ? url : "git+" + url) + (url.contains("?") ? "&" : "?") + "rev="
                                + rev;
            case "tarball", "file" -> url == null ? null : type + "+" + url;
            default -> null;
        };
    }

    private static String describe(NixAttrs input) {
        String type = string(input, "type");
        String owner = string(input, "owner");
        String repo = string(input, "repo");
        if (owner != null && repo != null) return "'" + type + ":" + owner + "/" + repo + "'";
        String url = string(input, "url");
        return url != null ? "'" + url + "'" : "of type '" + type + "'";
    }

    private static String string(NixAttrs attrs, String key) {
        Object v = attrs.get(key);
        return v != null && Thunk.force(v) instanceof NixString s ? s.value : null;
    }
}
