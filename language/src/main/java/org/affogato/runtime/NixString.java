package org.affogato.runtime;

import java.util.Arrays;
import java.util.SortedSet;

/** Nix strings are byte strings: {@code value} holds one char per byte (ISO-8859-1). */
public final class NixString {
    public static final NixString EMPTY = new NixString("");

    public final String value;
    /**
     * Sorted context elements in Nix's encoding (path, {@code =drv}, {@code !out!drv}); null when empty. Shared
     * between strings: never written.
     */
    public final String[] context;

    public NixString(String value) {
        this(value, (String[]) null);
    }

    public NixString(String value, String[] context) {
        this.value = value;
        this.context = context == null || context.length == 0 ? null : context;
    }

    public NixString(String value, SortedSet<String> context) {
        this(value, context.toArray(new String[0]));
    }

    /** A string whose context is {@code element} alone. */
    public static NixString withContext(String value, String element) {
        return new NixString(value, new String[] {element});
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

    public static String[] merge(String[] a, String[] b) {
        if (a == null) return b;
        if (b == null || a == b) return a;
        if (a.length < b.length) {
            String[] t = a;
            a = b;
            b = t;
        }
        int missing = 0;
        for (String s : b) if (Arrays.binarySearch(a, s) < 0) missing++;
        if (missing == 0) return a;
        String[] r = new String[a.length + missing];
        int i = 0, j = 0, o = 0;
        while (i < a.length && j < b.length) {
            int c = a[i].compareTo(b[j]);
            r[o++] = c <= 0 ? a[i++] : b[j++];
            if (c == 0) j++;
        }
        while (i < a.length) r[o++] = a[i++];
        while (j < b.length) r[o++] = b[j++];
        return r;
    }

    @Override
    public String toString() {
        return value;
    }
}
