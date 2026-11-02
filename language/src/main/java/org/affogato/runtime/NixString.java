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
