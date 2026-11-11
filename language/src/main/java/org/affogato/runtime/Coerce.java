package org.affogato.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.affogato.NixContext;

public final class Coerce {
    private Coerce() {}

    /** Port of {@code EvalState::coerceToString}. */
    public static NixString toNixString(Object v, boolean coerceMore, boolean copyToStore) {
        v = Thunk.force(v);
        if (v instanceof NixString s) return s;
        if (v instanceof NixPath p) {
            if (!copyToStore) return new NixString(p.path());
            String sp = NixContext.get().copyPathToStore(p.path());
            return NixString.withContext(sp, sp);
        }
        if (v instanceof NixAttrs a) {
            Object f = a.getRaw("__toString");
            if (f != null) return toNixString(Apply.apply(f, a), coerceMore, copyToStore);
            Object out = a.getRaw("outPath");
            if (out != null) return toNixString(out, coerceMore, copyToStore);
        }
        if (coerceMore) {
            if (v == NixNull.INSTANCE || Boolean.FALSE.equals(v)) return NixString.EMPTY;
            if (Boolean.TRUE.equals(v)) return new NixString("1");
            if (v instanceof Long l) return new NixString(Long.toString(l));
            if (v instanceof Double d) return new NixString(cToString(d));
            if (v instanceof NixList l) {
                StringBuilder sb = new StringBuilder();
                String[] ctx = null;
                for (int i = 0; i < l.size(); i++) {
                    Object item = l.get(i);
                    NixString s = toNixString(item, true, copyToStore);
                    sb.append(s.value);
                    ctx = NixString.merge(ctx, s.context);
                    if (i < l.size() - 1 && !(item instanceof NixList il && il.size() == 0)) sb.append(' ');
                }
                return new NixString(sb.toString(), ctx);
            }
        }
        throw new NixException("cannot coerce " + Types.describe(v) + " to a string");
    }

    /** C++ {@code std::to_string(double)}. */
    public static String cToString(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        return String.format(Locale.ROOT, "%f", d);
    }

    /** Lexical canonicalisation of an absolute path, as {@code CanonPath} does. */
    public static String canonicalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(seg);
            }
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    public static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
