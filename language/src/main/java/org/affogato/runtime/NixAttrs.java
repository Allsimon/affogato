package org.affogato.runtime;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * An attribute set: flat sorted arrays, or the lazy result of {@code //}. Most {@code //} results are only looked up,
 * never iterated, so a union keeps its operands and is merged on first use of {@link #keys()}, {@link #values()} or
 * an index.
 */
public final class NixAttrs {
    public static final NixAttrs EMPTY = new NixAttrs(new Name[0], new Object[0]);

    /** iets measured that a union of up to eight flat sets keeps lookups cheap (numtide/iets evaluation.md). */
    private static final int MAX_LEAVES = 8;

    private static final int MIN_UNION_SIZE = 8;

    /** Sorted by byte order, which is what {@code attrNames} and iteration expose; null while a union. */
    private Name[] keys;

    /** While a union, a {@code NixAttrs[]}: the flat operands of the {@code //}, lowest priority first. */
    private Object[] values;

    public NixAttrs(Name[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    private NixAttrs(NixAttrs[] leaves) {
        this.values = leaves;
    }

    /** Null once flat. */
    private NixAttrs[] leaves() {
        return this.keys == null ? (NixAttrs[]) this.values : null;
    }

    public static NixAttrs of(Map<String, Object> map) {
        TreeMap<String, Object> sorted = map instanceof TreeMap<String, Object> t ? t : new TreeMap<>(map);
        Name[] keys = new Name[sorted.size()];
        int i = 0;
        for (String name : sorted.keySet()) keys[i++] = Name.of(name);
        return new NixAttrs(keys, sorted.values().toArray());
    }

    private record Pair(Name key, Object value) {}

    /** Takes the arrays, in any order; of equal keys, the first wins. */
    public static NixAttrs ofPairs(Name[] keys, Object[] values) {
        int n = keys.length;
        int i = 1;
        while (i < n && keys[i - 1].compareTo(keys[i]) < 0) i++;
        if (i >= n) return new NixAttrs(keys, values);
        Pair[] pairs = new Pair[n];
        for (int j = 0; j < n; j++) pairs[j] = new Pair(keys[j], values[j]);
        Arrays.sort(pairs, (x, y) -> x.key.compareTo(y.key));
        int o = 0;
        for (Pair p : pairs) {
            if (o > 0 && keys[o - 1].equals(p.key)) continue;
            keys[o] = p.key;
            values[o++] = p.value;
        }
        return o == n ? new NixAttrs(keys, values) : new NixAttrs(Arrays.copyOf(keys, o), Arrays.copyOf(values, o));
    }

    public Name[] keys() {
        flatten();
        return keys;
    }

    public String key(int i) {
        return keys()[i].name;
    }

    public String[] names() {
        Name[] ks = keys();
        String[] names = new String[ks.length];
        for (int i = 0; i < names.length; i++) names[i] = ks[i].name;
        return names;
    }

    /** Indexed like {@link #keys()}; writes are seen by later lookups. */
    public Object[] values() {
        flatten();
        return values;
    }

    public int size() {
        return keys().length;
    }

    /** A lower bound of {@link #size()} that does not merge a union. */
    public int minSize() {
        NixAttrs[] leaves = leaves();
        if (leaves == null) return keys.length;
        int n = 0;
        for (NixAttrs l : leaves) n = Math.max(n, l.keys.length);
        return n;
    }

    public boolean isEmpty() {
        return keys != null && keys.length == 0;
    }

    /** Same contract as {@link Arrays#binarySearch(Object[], Object)}. */
    public int indexOf(String key) {
        flatten();
        return search(keys, key, Name.prefix(key), Name.exact(key), 0, keys.length - 1);
    }

    public int indexOf(Name key) {
        flatten();
        return search(keys, key.name, key.prefix, key.exact, 0, keys.length - 1);
    }

    /** {@link #indexOf} in sorted {@code keys}. */
    public static int search(Name[] keys, Name key) {
        return search(keys, key.name, key.prefix, key.exact, 0, keys.length - 1);
    }

    private static int search(Name[] keys, String name, long prefix, boolean exact, int lo, int hi) {
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Name k = keys[mid];
            long p = k.prefix;
            int c;
            if (p != prefix) {
                c = Long.compareUnsigned(p, prefix);
            } else if (exact && k.exact) {
                return mid;
            } else {
                c = k.name.compareTo(name);
            }
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /** Returns the raw (possibly unevaluated) value, or null if absent. */
    public Object getRaw(String key) {
        return getRaw(key, Name.prefix(key), Name.exact(key));
    }

    public Object getRaw(Name key) {
        return getRaw(key.name, key.prefix, key.exact);
    }

    private Object getRaw(String name, long prefix, boolean exact) {
        NixAttrs[] leaves = leaves();
        if (leaves == null) return flatGetRaw(name, prefix, exact);
        for (int l = leaves.length - 1; l >= 0; l--) {
            Object v = leaves[l].flatGetRaw(name, prefix, exact);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * For each of the sorted {@code keys} whose {@code values} slot is empty, stores what {@link #getRaw} would
     * return, and in {@code found} this set's own key; one galloping pass per leaf instead of a search per key.
     */
    public void getRawSorted(Name[] keys, Object[] values, Name[] found) {
        NixAttrs[] leaves = leaves();
        if (leaves == null) {
            flatGetRawSorted(keys, values, found);
            return;
        }
        for (int l = leaves.length - 1; l >= 0; l--) leaves[l].flatGetRawSorted(keys, values, found);
    }

    private void flatGetRawSorted(Name[] ks, Object[] vs, Name[] found) {
        int from = 0;
        for (int j = 0; j < ks.length && from < keys.length; j++) {
            if (vs[j] != null) continue;
            int at = gallop(ks[j], from);
            if (at < 0) {
                from = -(at + 1);
                continue;
            }
            vs[j] = values[at];
            found[j] = keys[at];
            from = at + 1;
        }
    }

    private Object flatGetRaw(String name, long prefix, boolean exact) {
        int i = search(keys, name, prefix, exact, 0, keys.length - 1);
        return i >= 0 ? values[i] : null;
    }

    /** Returns the forced value, or null if absent; the value is stored over its thunk, so later reads skip it. */
    public Object get(String key) {
        return get(key, Name.prefix(key), Name.exact(key));
    }

    public Object get(Name key) {
        return get(key.name, key.prefix, key.exact);
    }

    private Object get(String name, long prefix, boolean exact) {
        NixAttrs[] leaves = leaves();
        if (leaves == null) return flatGet(name, prefix, exact);
        for (int l = leaves.length - 1; l >= 0; l--) {
            Object v = leaves[l].flatGet(name, prefix, exact);
            if (v != null) return v;
        }
        return null;
    }

    private Object flatGet(String name, long prefix, boolean exact) {
        int i = search(keys, name, prefix, exact, 0, keys.length - 1);
        if (i < 0) return null;
        Object v = values[i];
        if (v instanceof Thunk t) {
            v = t.force();
            values[i] = v;
        }
        return v;
    }

    /** {@link #get} by index into {@link #keys()}. */
    public Object force(int i) {
        Object[] vs = values();
        Object v = vs[i];
        if (v instanceof Thunk t) {
            v = t.force();
            vs[i] = v;
        }
        return v;
    }

    /** Where {@code key} was defined; null when absent or unknown. */
    public SourceSection position(String key) {
        long prefix = Name.prefix(key);
        boolean exact = Name.exact(key);
        NixAttrs[] leaves = leaves();
        if (leaves == null) return flatPosition(key, prefix, exact);
        for (int l = leaves.length - 1; l >= 0; l--) {
            if (leaves[l].flatGetRaw(key, prefix, exact) != null) return leaves[l].flatPosition(key, prefix, exact);
        }
        return null;
    }

    private SourceSection flatPosition(String name, long prefix, boolean exact) {
        int i = search(keys, name, prefix, exact, 0, keys.length - 1);
        return i >= 0 ? keys[i].position() : null;
    }

    public boolean has(String key) {
        return getRaw(key) != null;
    }

    public NixAttrs update(NixAttrs right) {
        if (right.isEmpty()) return this;
        if (isEmpty()) return right;
        NixAttrs[] leaves = leaves();
        NixAttrs[] rightLeaves = right.leaves();
        if (leaves == null && keys.length < MIN_UNION_SIZE) return merge(this, right.flat());
        NixAttrs[] l = leaves != null ? leaves : new NixAttrs[] {this};
        NixAttrs[] r = rightLeaves != null ? rightLeaves : new NixAttrs[] {right};
        NixAttrs[] all = Arrays.copyOf(l, l.length + r.length);
        System.arraycopy(r, 0, all, l.length, r.length);
        int n = all.length > MAX_LEAVES ? compact(all, MAX_LEAVES) : all.length;
        return n == 1 ? all[0] : new NixAttrs(n == all.length ? all : Arrays.copyOf(all, n));
    }

    /**
     * Merges adjacent flat sets until {@code target} remain, smallest pair first: {@code //} is associative, and a
     * package set's large base is then rarely copied. Returns the new count.
     */
    private static int compact(NixAttrs[] ls, int target) {
        int n = ls.length;
        for (; n > target; n--) {
            int best = 0;
            for (int i = 1; i < n - 1; i++) {
                if (ls[i].keys.length + ls[i + 1].keys.length < ls[best].keys.length + ls[best + 1].keys.length)
                    best = i;
            }
            ls[best] = merge(ls[best], ls[best + 1]);
            System.arraycopy(ls, best + 2, ls, best + 1, n - best - 2);
        }
        return n;
    }

    private NixAttrs flat() {
        flatten();
        return this;
    }

    private void flatten() {
        if (keys != null) return;
        NixAttrs[] ls = leaves().clone();
        compact(ls, 1);
        NixAttrs acc = ls[0];
        keys = acc.keys;
        values = acc.values;
    }

    /** Merges two flat sets, {@code right} winning. */
    private static NixAttrs merge(NixAttrs left, NixAttrs right) {
        Name[] lk = left.keys;
        Name[] rk = right.keys;
        int n = lk.length + rk.length;
        Name[] k = new Name[n];
        Object[] v = new Object[n];
        int i = 0, o = 0;
        for (int j = 0; j < rk.length; j++) {
            int at = left.gallop(rk[j], i);
            int end = at >= 0 ? at : -(at + 1);
            o = left.copyRun(i, end, k, v, o);
            k[o] = rk[j];
            v[o] = right.values[j];
            o++;
            i = at >= 0 ? end + 1 : end;
        }
        o = left.copyRun(i, lk.length, k, v, o);
        if (o == n) return new NixAttrs(k, v);
        return new NixAttrs(Arrays.copyOf(k, o), Arrays.copyOf(v, o));
    }

    /** {@link #indexOf} restricted to keys from {@code from}, cheap when the key lies close to it. */
    private int gallop(Name key, int from) {
        int bound = 1;
        while (from + bound <= keys.length && keys[from + bound - 1].compareTo(key) < 0) bound <<= 1;
        return search(
                keys, key.name, key.prefix, key.exact, from + (bound >> 1), Math.min(from + bound, keys.length) - 1);
    }

    private int copyRun(int from, int to, Name[] k, Object[] v, int o) {
        int length = to - from;
        System.arraycopy(keys, from, k, o, length);
        System.arraycopy(values, from, v, o, length);
        return o + length;
    }

    public boolean isDerivation() {
        Object t = get(Name.TYPE);
        return t instanceof NixString s && s.value.equals("derivation");
    }
}
