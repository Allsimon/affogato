package org.affogato.runtime;

import org.affogato.NixContext;

/**
 * An attribute's name with where it was defined, which {@code unsafeGetAttrPos} reports: one per definition, shared by
 * the sets made from it. A name without a definition is one per context.
 *
 * <p>Ordered as {@link String#compareTo}; the first eight bytes are kept inline so that most comparisons of a lookup
 * read no string.
 */
public final class Name {
    public static final Name FUNCTOR = new Name("__functor");
    public static final Name OUT_PATH = new Name("outPath");
    public static final Name TO_STRING = new Name("__toString");
    public static final Name TYPE = new Name("type");

    public final String name;
    /** The first eight chars, big-endian, each capped at 0xFF, padded with zeros. */
    final long prefix;
    /** Whether {@code prefix} holds the whole name. */
    final boolean exact;

    private final Source source;
    private final int offset;

    public Name(String name) {
        this(name, null, 0);
    }

    public Name(String name, Source source, int offset) {
        this.name = name;
        this.prefix = prefix(name);
        this.exact = exact(name);
        this.source = source;
        this.offset = offset;
    }

    public Name(String name, SourceSection position) {
        this(name, position == null ? null : position.getSource(), position == null ? 0 : position.getCharIndex());
    }

    /** The name without a definition, shared within the context under evaluation. */
    public static Name of(String name) {
        NixContext context = NixContext.get();
        return context == null ? new Name(name) : context.name(name);
    }

    static long prefix(String name) {
        long p = 0;
        int n = Math.min(name.length(), 8);
        for (int i = 0; i < n; i++) p |= (long) Math.min(name.charAt(i), 0xFF) << (56 - 8 * i);
        return p;
    }

    static boolean exact(String name) {
        int n = name.length();
        if (n > 8) return false;
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            if (c == 0 || c > 0xFF) return false;
        }
        return true;
    }

    /** Null when the name has no definition. */
    public SourceSection position() {
        return source == null ? null : source.createSection(offset, 0);
    }

    public Name withoutPosition() {
        return source == null ? this : of(name);
    }

    public int compareTo(Name other) {
        if (prefix != other.prefix) return Long.compareUnsigned(prefix, other.prefix);
        return exact && other.exact ? 0 : name.compareTo(other.name);
    }

    @Override
    public boolean equals(Object o) {
        return o == this || o instanceof Name a && compareTo(a) == 0;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
