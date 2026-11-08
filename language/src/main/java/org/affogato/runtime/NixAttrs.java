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
    public static final NixAttrs EMPTY = new NixAttrs(new String[0], new Object[0], null);

    /** iets measured that a union of up to eight flat sets keeps lookups cheap (numtide/iets evaluation.md). */
    private static final int MAX_LEAVES = 8;

    private static final int MIN_UNION_SIZE = 8;

    /** Sorted by byte order, which is what {@code attrNames} and iteration expose; null while a union. */
    private String[] keys;

    private Object[] values;
    /** Definition sites for {@code unsafeGetAttrPos}; null when unknown. */
    private SourceSection[] positions;

    /** The flat operands of a {@code //}, lowest priority first; null once flat. */
    private NixAttrs[] leaves;

    public NixAttrs(String[] keys, Object[] values, SourceSection[] positions) {
        this.keys = keys;
        this.values = values;
        this.positions = positions;
    }

    private NixAttrs(NixAttrs[] leaves) {
        this.leaves = leaves;
    }

    public static NixAttrs of(Map<String, Object> map) {
        TreeMap<String, Object> sorted = map instanceof TreeMap<String, Object> t ? t : new TreeMap<>(map);
        return new NixAttrs(
                sorted.keySet().toArray(new String[0]), sorted.values().toArray(), null);
    }

    private record Pair(String key, Object value) {}

    /** Takes the arrays, in any order; of equal keys, the first wins. */
    public static NixAttrs ofPairs(String[] keys, Object[] values) {
        int n = keys.length;
        int i = 1;
        while (i < n && keys[i - 1].compareTo(keys[i]) < 0) i++;
        if (i >= n) return new NixAttrs(keys, values, null);
        Pair[] pairs = new Pair[n];
        for (int j = 0; j < n; j++) pairs[j] = new Pair(keys[j], values[j]);
        Arrays.sort(pairs, (x, y) -> x.key.compareTo(y.key));
        int o = 0;
        for (Pair p : pairs) {
            if (o > 0 && keys[o - 1].equals(p.key)) continue;
            keys[o] = p.key;
            values[o++] = p.value;
        }
        return o == n
                ? new NixAttrs(keys, values, null)
                : new NixAttrs(Arrays.copyOf(keys, o), Arrays.copyOf(values, o), null);
    }

    public String[] keys() {
        flatten();
        return keys;
    }

    /** Indexed like {@link #keys()}; writes are seen by later lookups. */
    public Object[] values() {
        flatten();
        return values;
    }

    public SourceSection[] positions() {
        flatten();
        return positions;
    }

    public int size() {
        return keys().length;
    }

    /** A lower bound of {@link #size()} that does not merge a union. */
    public int minSize() {
        if (leaves == null) return keys.length;
        int n = 0;
        for (NixAttrs l : leaves) n = Math.max(n, l.keys.length);
        return n;
    }

    public boolean isEmpty() {
        return leaves == null && keys.length == 0;
    }

    /** Same contract as {@link Arrays#binarySearch(Object[], Object)}, without the megamorphic {@code compareTo}. */
    public int indexOf(String key) {
        flatten();
        return indexOf(key, 0, keys.length - 1);
    }

    private int indexOf(String key, int lo, int hi) {
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys[mid].compareTo(key);
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
        if (leaves == null) return flatGetRaw(key);
        for (int l = leaves.length - 1; l >= 0; l--) {
            Object v = leaves[l].flatGetRaw(key);
            if (v != null) return v;
        }
        return null;
    }

    private Object flatGetRaw(String key) {
        int i = indexOf(key, 0, keys.length - 1);
        return i >= 0 ? values[i] : null;
    }

    /** Returns the forced value, or null if absent; the value is stored over its thunk, so later reads skip it. */
    public Object get(String key) {
        if (leaves == null) return flatGet(key);
        for (int l = leaves.length - 1; l >= 0; l--) {
            Object v = leaves[l].flatGet(key);
            if (v != null) return v;
        }
        return null;
    }

    private Object flatGet(String key) {
        int i = indexOf(key, 0, keys.length - 1);
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

    public SourceSection position(String key) {
        if (leaves == null) return flatPosition(key);
        for (int l = leaves.length - 1; l >= 0; l--) {
            if (leaves[l].flatGetRaw(key) != null) return leaves[l].flatPosition(key);
        }
        return null;
    }

    private SourceSection flatPosition(String key) {
        int i = indexOf(key, 0, keys.length - 1);
        return i >= 0 && positions != null ? positions[i] : null;
    }

    public boolean has(String key) {
        return getRaw(key) != null;
    }

    public NixAttrs update(NixAttrs right) {
        if (right.isEmpty()) return this;
        if (isEmpty()) return right;
        if (leaves == null && keys.length < MIN_UNION_SIZE) return merge(this, right.flat());
        NixAttrs[] l = leaves != null ? leaves : new NixAttrs[] {this};
        NixAttrs[] r = right.leaves != null ? right.leaves : new NixAttrs[] {right};
        if (l.length + r.length > MAX_LEAVES) {
            l = new NixAttrs[] {flat()};
            if (1 + r.length > MAX_LEAVES) return merge(l[0], right.flat());
        }
        NixAttrs[] all = Arrays.copyOf(l, l.length + r.length);
        System.arraycopy(r, 0, all, l.length, r.length);
        return new NixAttrs(all);
    }

    private NixAttrs flat() {
        flatten();
        return this;
    }

    private void flatten() {
        if (leaves == null) return;
        NixAttrs acc = leaves[0];
        for (int l = 1; l < leaves.length; l++) acc = merge(acc, leaves[l]);
        keys = acc.keys;
        values = acc.values;
        positions = acc.positions;
        leaves = null;
    }

    /** Merges two flat sets, {@code right} winning. */
    private static NixAttrs merge(NixAttrs left, NixAttrs right) {
        String[] lk = left.keys;
        String[] rk = right.keys;
        int n = lk.length + rk.length;
        String[] k = new String[n];
        Object[] v = new Object[n];
        SourceSection[] p = left.positions != null || right.positions != null ? new SourceSection[n] : null;
        int i = 0, o = 0;
        for (int j = 0; j < rk.length; j++) {
            int at = left.gallop(rk[j], i);
            int end = at >= 0 ? at : -(at + 1);
            o = left.copyRun(i, end, k, v, p, o);
            k[o] = rk[j];
            v[o] = right.values[j];
            if (p != null && right.positions != null) p[o] = right.positions[j];
            o++;
            i = at >= 0 ? end + 1 : end;
        }
        o = left.copyRun(i, lk.length, k, v, p, o);
        if (o == n) return new NixAttrs(k, v, p);
        return new NixAttrs(Arrays.copyOf(k, o), Arrays.copyOf(v, o), p == null ? null : Arrays.copyOf(p, o));
    }

    /** {@link #indexOf} restricted to keys from {@code from}, cheap when the key lies close to it. */
    private int gallop(String key, int from) {
        int bound = 1;
        while (from + bound <= keys.length && keys[from + bound - 1].compareTo(key) < 0) bound <<= 1;
        return indexOf(key, from + (bound >> 1), Math.min(from + bound, keys.length) - 1);
    }

    private int copyRun(int from, int to, String[] k, Object[] v, SourceSection[] p, int o) {
        int length = to - from;
        System.arraycopy(keys, from, k, o, length);
        System.arraycopy(values, from, v, o, length);
        if (p != null && positions != null) System.arraycopy(positions, from, p, o, length);
        return o + length;
    }

    public boolean isDerivation() {
        Object t = get("type");
        return t instanceof NixString s && s.value.equals("derivation");
    }
}
