package org.affogato.builtins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.affogato.runtime.*;

final class Lists {
    private Lists() {}

    static void register(Builtins b) {
        b.add("length", 1, a -> (long) Types.list(a[0]).size());
        b.add("head", 1, a -> {
            NixList l = Types.list(a[0]);
            if (l.size() == 0) throw new NixException("'builtins.head' called on an empty list");
            return l.get(0);
        });
        b.add("tail", 1, a -> {
            NixList l = Types.list(a[0]);
            if (l.size() == 0) throw new NixException("'builtins.tail' called on an empty list");
            return new NixList(Arrays.copyOfRange(l.items, 1, l.size()));
        });
        b.add("elemAt", 2, a -> {
            NixList l = Types.list(a[0]);
            long i = Types.integer(a[1]);
            if (i < 0 || i >= l.size())
                throw new NixException("'builtins.elemAt' called with index " + i + " on a list of size " + l.size());
            return l.get((int) i);
        });
        b.add("map", 2, a -> {
            NixList l = Types.list(a[1]);
            if (l.size() == 0) return l;
            Types.callable(a[0]);
            Object[] out = new Object[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = Thunk.apply(a[0], l.items[i]);
            return new NixList(out);
        });
        b.add("filter", 2, a -> {
            NixList l = Types.list(a[1]);
            List<Object> out = new ArrayList<>();
            for (Object x : l.items) if (Types.bool(Apply.apply(a[0], x))) out.add(x);
            return out.size() == l.size() ? l : new NixList(out.toArray());
        });
        b.add("foldl'", 3, a -> {
            Object acc = a[1];
            for (Object x : Types.list(a[2]).items) acc = Apply.apply(Apply.apply(a[0], acc), x);
            return acc;
        });
        b.add("genList", 2, a -> {
            long n = Types.integer(a[1]);
            if (n < 0) throw new NixException("cannot create list of size " + n);
            Types.callable(a[0]);
            Object[] out = new Object[(int) n];
            for (int i = 0; i < n; i++) out[i] = Thunk.apply(a[0], (long) i);
            return new NixList(out);
        });
        b.add("concatLists", 1, a -> {
            List<Object> out = new ArrayList<>();
            for (Object x : Types.list(a[0]).items) out.addAll(Arrays.asList(Types.list(x).items));
            return new NixList(out.toArray());
        });
        b.add("concatMap", 2, a -> {
            List<Object> out = new ArrayList<>();
            for (Object x : Types.list(a[1]).items) out.addAll(Arrays.asList(Types.list(Apply.apply(a[0], x)).items));
            return new NixList(out.toArray());
        });
        b.add("elem", 2, a -> {
            for (Object x : Types.list(a[1]).items) if (Ops.equalCells(a[0], x)) return true;
            return false;
        });
        b.add("all", 2, a -> {
            for (Object x : Types.list(a[1]).items) if (!Types.bool(Apply.apply(a[0], x))) return false;
            return true;
        });
        b.add("any", 2, a -> {
            for (Object x : Types.list(a[1]).items) if (Types.bool(Apply.apply(a[0], x))) return true;
            return false;
        });
        b.add("sort", 2, a -> {
            NixList l = Types.list(a[1]);
            if (l.size() == 0) return l;
            Types.callable(a[0]);
            Object[] items = l.items.clone();
            for (int i = 0; i < items.length; i++) items[i] = Thunk.force(items[i]);
            mergeSort(items, (x, y) -> Types.bool(Apply.apply(Apply.apply(a[0], x), y)));
            return new NixList(items);
        });
        b.add("partition", 2, a -> {
            List<Object> right = new ArrayList<>();
            List<Object> wrong = new ArrayList<>();
            for (Object x : Types.list(a[1]).items) (Types.bool(Apply.apply(a[0], x)) ? right : wrong).add(x);
            TreeMap<String, Object> m = new TreeMap<>();
            m.put("right", new NixList(right.toArray()));
            m.put("wrong", new NixList(wrong.toArray()));
            return NixAttrs.of(m);
        });
        b.add("groupBy", 2, a -> {
            TreeMap<String, List<Object>> groups = new TreeMap<>();
            for (Object x : Types.list(a[1]).items) {
                groups.computeIfAbsent(Types.string(Apply.apply(a[0], x)).value, k -> new ArrayList<>())
                        .add(x);
            }
            TreeMap<String, Object> m = new TreeMap<>();
            groups.forEach((k, v) -> m.put(k, new NixList(v.toArray())));
            return NixAttrs.of(m);
        });
        b.add("genericClosure", 1, a -> {
            NixAttrs args = Types.attrs(a[0]);
            Object start = args.get("startSet");
            if (start == null) throw new NixException("attribute 'startSet' required");
            Object op = args.get("operator");
            if (op == null) throw new NixException("attribute 'operator' required");
            TreeSet<Object> seen = new TreeSet<>((x, y) -> Ops.lessThan(x, y) ? -1 : Ops.lessThan(y, x) ? 1 : 0);
            List<Object> work = new ArrayList<>(Arrays.asList(Types.list(start).items));
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < work.size(); i++) {
                NixAttrs e = Types.attrs(work.get(i));
                Object key = e.get("key");
                if (key == null) throw new NixException("attribute 'key' required");
                if (!seen.add(key)) continue;
                out.add(e);
                work.addAll(Arrays.asList(Types.list(Apply.apply(op, e)).items));
            }
            return new NixList(out.toArray());
        });
    }

    interface Less {
        boolean lt(Object a, Object b);
    }

    /** Stable sort that tolerates inconsistent comparators, which {@code java.util} sorting rejects. */
    static void mergeSort(Object[] a, Less less) {
        if (a.length < 2) return;
        Object[] tmp = new Object[a.length];
        for (int width = 1; width < a.length; width *= 2) {
            for (int lo = 0; lo < a.length - width; lo += 2 * width) {
                int mid = lo + width;
                int hi = Math.min(lo + 2 * width, a.length);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) tmp[k++] = less.lt(a[j], a[i]) ? a[j++] : a[i++];
                while (i < mid) tmp[k++] = a[i++];
                while (j < hi) tmp[k++] = a[j++];
                System.arraycopy(tmp, lo, a, lo, hi - lo);
            }
        }
    }
}
