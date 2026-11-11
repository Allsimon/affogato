package org.affogato.builtins;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.affogato.NixContext;
import org.affogato.runtime.*;
import org.affogato.store.Hashes;
import org.affogato.store.NixBase32;
import org.affogato.store.StorePaths;

/** {@code derivationStrict}: builds the store derivation in memory and computes its paths without writing them. */
public final class Derivations {
    public static final String COREPKGS = "/__corepkgs__/";
    public static final String FETCHURL_PATH = COREPKGS + "fetchurl.nix";

    private Derivations() {}

    record Output(String path, String hashAlgo, String hash) {}

    static final class Drv {
        String name;
        final TreeMap<String, Output> outputs = new TreeMap<>();
        final TreeMap<String, TreeSet<String>> inputDrvs = new TreeMap<>();
        final TreeSet<String> inputSrcs = new TreeSet<>();
        String platform;
        String builder;
        final List<String> args = new ArrayList<>();
        final TreeMap<String, String> env = new TreeMap<>();
        boolean fixed;
    }

    /** What a derivation's dependents need of it, kept instead of the derivation since its env can be large. */
    private record Closure(String[] outputs, String[] inputSrcs, String[] inputDrvs) {}

    /** Derivations produced so far, by .drv path; stands in for reading them back from the store. */
    private static final Map<String, Closure> DRVS = new ConcurrentHashMap<>();

    /** Written before {@link #DRVS}, so that a thread seeing a derivation there sees its hash. */
    private static final Map<String, Map<String, byte[]>> HASH_MODULO = new ConcurrentHashMap<>();

    private static final ThreadLocal<AtermWriter> ATERM =
            ThreadLocal.withInitial(() -> new AtermWriter(Hashes.digest("sha256")));

    /**
     * drvPaths by masked hash, store and input derivations: the full ATerm differs from the masked one only by the
     * output paths, derived from those, and the input derivations' paths, which fixed-output inputs keep from the
     * masked hash.
     */
    private static final LinkedHashMap<String, String> DRV_PATHS = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 1 << 16;
        }
    };

    static void register(Builtins b) {
        b.add("derivationStrict", 1, a -> derivationStrict(NixContext.get(), Types.attrs(a[0])));
        b.add("convertHash", 1, a -> {
            NixAttrs args = Types.attrs(a[0]);
            Object hashV = args.get("hash");
            if (hashV == null) throw new NixException("attribute 'hash' missing");
            Object algoV = args.get("hashAlgo");
            Object formatV = args.get("toHashFormat");
            if (formatV == null) throw new NixException("attribute 'toHashFormat' missing");
            String[] h = parseAnyHash(Types.string(hashV).value, algoV == null ? null : Types.string(algoV).value);
            byte[] bytes = HexFormat.of().parseHex(h[1]);
            return new NixString(
                    switch (Types.string(formatV).value) {
                        case "base16" -> h[1];
                        case "nix32", "base32" -> NixBase32.encode(bytes);
                        case "base64" -> Base64.getEncoder().encodeToString(bytes);
                        case "sri" -> h[0] + "-" + Base64.getEncoder().encodeToString(bytes);
                        default -> throw new NixException("unknown hash format '" + Types.string(formatV).value + "'");
                    });
        });
    }

    private static NixException error(String drvName, String msg) {
        return new NixException(msg + (drvName == null ? "" : " (while evaluating the derivation '" + drvName + "')"));
    }

    private static Object derivationStrict(NixContext ctx, NixAttrs attrs) {
        Object nameV = attrs.get("name");
        if (nameV == null) throw new NixException("required attribute 'name' missing");
        String drvName = Coerce.toNixString(nameV, false, false).value;
        try {
            StorePaths.checkName(drvName);
        } catch (IllegalArgumentException e) {
            throw new NixException("invalid derivation name: " + e.getMessage());
        }

        Object ign = attrs.get("__ignoreNulls");
        boolean ignoreNulls = ign != null && Types.bool(ign);
        Object sa = attrs.get("__structuredAttrs");
        boolean structured = sa != null && Types.bool(sa);

        Drv drv = new Drv();
        drv.name = drvName;
        TreeSet<String> context = new TreeSet<>();
        TreeSet<String> outputs = new TreeSet<>(List.of("out"));
        String outputHash = null;
        String outputHashAlgo = null;
        String outputHashMode = null;
        StringBuilder json = structured ? new StringBuilder("{") : null;

        for (int i = 0; i < attrs.size(); i++) {
            String key = attrs.key(i);
            if (key.equals("__ignoreNulls")) continue;
            Object v = Thunk.force(attrs.values()[i]);
            if (ignoreNulls && v == NixNull.INSTANCE) continue;
            if (key.equals("__contentAddressed") && Types.bool(v))
                throw error(drvName, "content-addressed derivations are not supported");
            if (key.equals("__impure") && Types.bool(v)) throw error(drvName, "impure derivations are not supported");
            if (key.equals("args")) {
                for (Object x : Types.list(v).items) {
                    NixString s = Coerce.toNixString(x, true, true);
                    if (s.context != null) Collections.addAll(context, s.context);
                    drv.args.add(s.value);
                }
                continue;
            }
            if (structured) {
                if (key.equals("__structuredAttrs")) continue;
                if (json.length() > 1) json.append(',');
                Json.quote(json, key);
                json.append(':');
                Json.write(json, context, v, true);
                switch (key) {
                    case "builder" -> drv.builder = stringOf(v, context);
                    case "system" -> drv.platform = stringOf(v, context);
                    case "outputHash" -> outputHash = stringOf(v, context);
                    case "outputHashAlgo" -> outputHashAlgo = stringOf(v, context);
                    case "outputHashMode" -> outputHashMode = stringOf(v, context);
                    case "outputs" -> outputs = outputNames(drvName, v);
                    default -> {}
                }
            } else {
                NixString s = Coerce.toNixString(v, true, true);
                if (s.context != null) Collections.addAll(context, s.context);
                drv.env.put(key, s.value);
                switch (key) {
                    case "builder" -> drv.builder = s.value;
                    case "system" -> drv.platform = s.value;
                    case "outputHash" -> outputHash = s.value;
                    case "outputHashAlgo" -> outputHashAlgo = s.value;
                    case "outputHashMode" -> outputHashMode = s.value;
                    case "outputs" -> outputs = outputNames(drvName, v);
                    default -> {}
                }
            }
        }
        if (structured) drv.env.put("__json", json.append('}').toString());
        if (drv.builder == null) throw error(drvName, "required attribute 'builder' missing");
        if (drv.platform == null) throw error(drvName, "required attribute 'system' missing");

        for (String c : context) {
            if (c.startsWith("=")) {
                addDeepClosure(c.substring(1), drv);
            } else if (c.startsWith("!")) {
                int sep = c.indexOf('!', 1);
                drv.inputDrvs
                        .computeIfAbsent(c.substring(sep + 1), k -> new TreeSet<>())
                        .add(c.substring(1, sep));
            } else {
                drv.inputSrcs.add(c);
            }
        }

        String memoKey = null;
        if (outputHash != null) {
            if (!outputs.equals(Set.of("out")))
                throw error(drvName, "multiple outputs are not supported in fixed-output derivations");
            String[] algoAndHash = parseAnyHash(
                    outputHash, outputHashAlgo == null || outputHashAlgo.isEmpty() ? null : outputHashAlgo);
            String algo = algoAndHash[0];
            byte[] hash = HexFormat.of().parseHex(algoAndHash[1]);
            String method = outputHashMode == null ? "flat" : outputHashMode;
            String prefix = switch (method) {
                case "flat" -> "";
                case "recursive", "nar" -> "r:";
                case "text" -> "text:";
                default -> throw error(drvName, "invalid value '" + method + "' for 'outputHashMode' attribute");
            };
            String path = prefix.equals("text:")
                    ? StorePaths.make(ctx.storeDir, "text", hash, drvName)
                    : StorePaths.fixedOutput(ctx.storeDir, drvName, algo, hash, prefix.equals("r:"));
            drv.env.put("out", path);
            drv.outputs.put("out", new Output(path, prefix + algo, Hashes.hex(hash)));
            drv.fixed = true;
        } else {
            for (String o : outputs) {
                drv.env.put(o, "");
                drv.outputs.put(o, new Output("", "", ""));
            }
            Map<String, byte[]> hashes = hashModulo(drv, true);
            for (String o : outputs) {
                String path = StorePaths.output(ctx.storeDir, drvName, o, hashes.get(o));
                drv.env.put(o, path);
                drv.outputs.put(o, new Output(path, "", ""));
            }
            StringBuilder k = new StringBuilder(Hashes.hex(hashes.get(outputs.first())))
                    .append('\0')
                    .append(ctx.storeDir);
            for (var e : drv.inputDrvs.entrySet()) {
                k.append('\0').append(e.getKey());
                for (String o : e.getValue()) k.append('\1').append(o);
            }
            memoKey = k.toString();
        }

        String drvPath;
        synchronized (DRV_PATHS) {
            drvPath = memoKey == null ? null : DRV_PATHS.get(memoKey);
        }
        if (drvPath == null) {
            TreeSet<String> refs = new TreeSet<>(drv.inputSrcs);
            refs.addAll(drv.inputDrvs.keySet());
            drvPath = StorePaths.text(ctx.storeDir, drvName + ".drv", atermHash(drv, false, null), refs);
            if (memoKey != null) {
                synchronized (DRV_PATHS) {
                    DRV_PATHS.put(memoKey, drvPath);
                }
            }
        }
        if (!DRVS.containsKey(drvPath)) {
            HASH_MODULO.put(drvPath, hashModulo(drv, false));
            DRVS.put(
                    drvPath,
                    new Closure(
                            strings(drv.outputs.keySet()), strings(drv.inputSrcs), strings(drv.inputDrvs.keySet())));
        }

        TreeMap<String, Object> result = new TreeMap<>();
        result.put("drvPath", NixString.withContext(drvPath, "=" + drvPath));
        for (var o : drv.outputs.entrySet()) {
            result.put(o.getKey(), NixString.withContext(o.getValue().path(), "!" + o.getKey() + "!" + drvPath));
        }
        return NixAttrs.of(result);
    }

    private static String[] strings(Collection<String> c) {
        return c.toArray(new String[0]);
    }

    private static String stringOf(Object v, Set<String> context) {
        NixString s = Types.string(v);
        if (s.context != null) Collections.addAll(context, s.context);
        return s.value;
    }

    private static TreeSet<String> outputNames(String drvName, Object v) {
        TreeSet<String> outs = new TreeSet<>();
        for (Object x : Types.list(v).items) {
            String o = Types.string(x).value;
            if (o.equals("drv")) throw error(drvName, "invalid derivation output name 'drv'");
            if (!outs.add(o)) throw error(drvName, "duplicate derivation output '" + o + "'");
        }
        if (outs.isEmpty()) throw error(drvName, "derivation cannot have an empty set of outputs");
        return outs;
    }

    private static void addDeepClosure(String drvPath, Drv drv) {
        Closure dep = DRVS.get(drvPath);
        if (dep == null) throw new NixException("derivation '" + drvPath + "' is not known to affogato");
        drv.inputSrcs.add(drvPath);
        Collections.addAll(drv.inputDrvs.computeIfAbsent(drvPath, k -> new TreeSet<>()), dep.outputs());
        Collections.addAll(drv.inputSrcs, dep.inputSrcs());
        for (String d : dep.inputDrvs()) addDeepClosure(d, drv);
    }

    /** Port of {@code hashDerivationModulo}. */
    private static Map<String, byte[]> hashModulo(Drv drv, boolean mask) {
        Map<String, byte[]> out = new TreeMap<>();
        if (drv.fixed) {
            Output o = drv.outputs.get("out");
            out.put("out", Hashes.sha256("fixed:out:" + o.hashAlgo() + ":" + o.hash() + ":" + o.path()));
            return out;
        }
        TreeMap<String, TreeSet<String>> inputs = new TreeMap<>();
        for (var e : drv.inputDrvs.entrySet()) {
            Map<String, byte[]> h = pathHashModulo(e.getKey());
            for (String o : e.getValue()) {
                byte[] oh = h.get(o);
                if (oh == null)
                    throw new NixException("no hash for output '" + o + "' of derivation '" + e.getKey() + "'");
                inputs.computeIfAbsent(Hashes.hex(oh), k -> new TreeSet<>()).add(o);
            }
        }
        byte[] hash = atermHash(drv, mask, inputs);
        for (String o : drv.outputs.keySet()) out.put(o, hash);
        return out;
    }

    private static Map<String, byte[]> pathHashModulo(String drvPath) {
        Map<String, byte[]> h = HASH_MODULO.get(drvPath);
        if (h == null) throw new NixException("derivation '" + drvPath + "' is not known to affogato");
        return h;
    }

    /** ATerm serialisation, as {@code Derivation::unparse}. */
    static void unparse(AtermWriter w, Drv drv, boolean mask, TreeMap<String, TreeSet<String>> actualInputs) {
        for (char c : "Derive([".toCharArray()) w.put(c);
        boolean first = true;
        for (var e : drv.outputs.entrySet()) {
            if (!first) w.put(',');
            first = false;
            Output o = e.getValue();
            w.put('(').raw(e.getKey()).put(',');
            w.raw(mask ? "" : o.path()).put(',');
            w.raw(o.hashAlgo()).put(',');
            w.raw(o.hash()).put(')');
        }
        w.put(']').put(',').put('[');
        first = true;
        for (var e : (actualInputs != null ? actualInputs : drv.inputDrvs).entrySet()) {
            if (!first) w.put(',');
            first = false;
            w.put('(').raw(e.getKey()).put(',').put('[');
            boolean f2 = true;
            for (String o : e.getValue()) {
                if (!f2) w.put(',');
                f2 = false;
                w.raw(o);
            }
            w.put(']').put(')');
        }
        w.put(']').put(',').put('[');
        first = true;
        for (String src : drv.inputSrcs) {
            if (!first) w.put(',');
            first = false;
            w.raw(src);
        }
        w.put(']').put(',');
        w.quoted(drv.platform).put(',');
        w.quoted(drv.builder).put(',').put('[');
        first = true;
        for (String arg : drv.args) {
            if (!first) w.put(',');
            first = false;
            w.quoted(arg);
        }
        w.put(']').put(',').put('[');
        first = true;
        for (var e : drv.env.entrySet()) {
            if (!first) w.put(',');
            first = false;
            w.put('(').quoted(e.getKey()).put(',');
            w.quoted(e.getValue()).put(')');
        }
        w.put(']').put(')');
    }

    private static byte[] atermHash(Drv drv, boolean mask, TreeMap<String, TreeSet<String>> actualInputs) {
        AtermWriter w = ATERM.get();
        w.reset();
        unparse(w, drv, mask, actualInputs);
        return w.digest();
    }

    static byte[] parseHash(String s, String algo) {
        return HexFormat.of().parseHex(parseAnyHash(s, algo)[1]);
    }

    /** Port of {@code newHashAllowEmpty}: returns the algorithm and the hash in hex. */
    static String[] parseAnyHash(String s, String algo) {
        if (s.isEmpty()) {
            if (algo == null) throw new NixException("empty hash requires explicit hash algorithm");
            return new String[] {algo, Hashes.hex(new byte[Hashes.size(algo)])};
        }
        String rest = s;
        boolean sri = false;
        int colon = s.indexOf(':');
        int dash = s.indexOf('-');
        if (colon > 0 && isAlgo(s.substring(0, colon))) {
            algo = s.substring(0, colon);
            rest = s.substring(colon + 1);
        } else if (dash > 0 && isAlgo(s.substring(0, dash))) {
            algo = s.substring(0, dash);
            rest = s.substring(dash + 1);
            sri = true;
        }
        if (algo == null)
            throw new NixException(
                    "hash '" + s + "' does not include a type, nor is the type otherwise known from context");
        int size = Hashes.size(algo);
        byte[] bytes;
        try {
            if (sri) {
                bytes = Hashes.base64Decode(rest);
            } else if (rest.length() == size * 2) {
                bytes = HexFormat.of().parseHex(rest);
            } else if (rest.length() == (size * 8 - 1) / 5 + 1) {
                bytes = NixBase32.decode(rest, size);
            } else if (rest.length() == (size + 2) / 3 * 4) {
                bytes = Hashes.base64Decode(rest);
            } else {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new NixException("hash '" + s + "' has wrong length for hash algorithm '" + algo + "'");
        }
        if (bytes.length != size)
            throw new NixException("hash '" + s + "' has wrong length for hash algorithm '" + algo + "'");
        return new String[] {algo, Hashes.hex(bytes)};
    }

    private static boolean isAlgo(String a) {
        return a.equals("md5") || a.equals("sha1") || a.equals("sha256") || a.equals("sha512");
    }
}
