package org.affogato.runtime;

import java.util.Set;
import java.util.TreeSet;

/** Nix strings are byte strings: {@code value} holds one char per byte (ISO-8859-1). */
public final class NixString {
    public static final NixString EMPTY = new NixString("");

    public final String value;
    /** Sorted context elements in Nix's encoding (path, {@code =drv}, {@code !out!drv}); null when empty. */
    public final Set<String> context;

    public NixString(String value) {
        this(value, null);
    }

    public NixString(String value, Set<String> context) {
        this.value = value;
        this.context = context == null || context.isEmpty() ? null : context;
    }

    /** Nix's message for a string it cannot hold, or null; {@code ␀} as UTF-8 bytes stands for each NUL. */
    public static String nulError(String s) {
        if (s.indexOf('\0') < 0) return null;
        return "input string '" + s.replace("\0", "\u00e2\u0090\u0080")
                + "' cannot be represented as Nix string because it contains null bytes";
    }

    public static String checkNoNul(String s) {
        String error = nulError(s);
        if (error != null) throw new NixException(error);
        return s;
    }

    public boolean hasContext() {
        return context != null;
    }

    public static Set<String> merge(Set<String> a, Set<String> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.containsAll(b)) return a;
        if (b.containsAll(a)) return b;
        TreeSet<String> r = new TreeSet<>(a);
        r.addAll(b);
        return r;
    }

    @Override
    public String toString() {
        return value;
    }
}
