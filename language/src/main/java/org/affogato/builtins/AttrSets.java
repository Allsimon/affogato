package org.affogato.builtins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import org.affogato.runtime.*;

final class AttrSets {
    private AttrSets() {}

    static void register(Builtins b) {
        b.add("attrNames", 1, a -> {
            NixAttrs s = Types.attrs(a[0]);
            Object[] out = new Object[s.size()];
            for (int i = 0; i < out.length; i++) out[i] = new NixString(s.key(i));
            return new NixList(out);
        });
        b.add("attrValues", 1, a -> new NixList(Types.attrs(a[0]).values().clone()));
        b.add("getAttr", 2, a -> {
            String name = Types.string(a[0]).value;
            Object v = Types.attrs(a[1]).get(name);
            if (v == null) throw new NixException("attribute '" + name + "' missing");
            return v;
        });
        b.add("hasAttr", 2, a -> Types.attrs(a[1]).has(Types.string(a[0]).value));
        b.add("removeAttrs", 2, a -> {
            NixAttrs s = Types.attrs(a[0]);
            boolean[] drop = null;
            int dropped = 0;
            for (Object x : Types.list(a[1]).items) {
                int i = s.indexOf(Types.string(x).value);
                if (i < 0) continue;
                if (drop == null) drop = new boolean[s.size()];
                if (!drop[i]) dropped++;
                drop[i] = true;
            }
            if (dropped == 0) return s;
            Name[] k = new Name[s.size() - dropped];
            Object[] v = new Object[k.length];
            for (int i = 0, o = 0; i < s.size(); i++) {
                if (drop[i]) continue;
                k[o] = s.keys()[i];
                v[o] = s.values()[i];
                o++;
            }
            return new NixAttrs(k, v);
        });
        b.add("listToAttrs", 1, a -> {
            Object[] items = Types.list(a[0]).items;
            Name[] k = new Name[items.length];
            Object[] v = new Object[items.length];
            for (int i = 0; i < items.length; i++) {
                NixAttrs e = Types.attrs(items[i]);
                Object name = e.get("name");
                if (name == null) throw new NixException("attribute 'name' missing");
                Object value = e.getRaw("value");
                if (value == null) throw new NixException("attribute 'value' missing");
                k[i] = Name.of(Types.string(name).value);
                v[i] = value;
            }
            return NixAttrs.ofPairs(k, v);
        });
        b.add("intersectAttrs", 2, a -> {
            NixAttrs x = Types.attrs(a[0]);
            NixAttrs y = Types.attrs(a[1]);
            if (x.size() < y.minSize()) {
                Name[] xk = x.keys();
                Object[] v = new Object[xk.length];
                Name[] k = new Name[xk.length];
                y.getRawSorted(xk, v, k);
                int n = 0;
                for (int i = 0; i < xk.length; i++) {
                    if (v[i] == null) continue;
                    k[n] = k[i];
                    v[n++] = v[i];
                }
                return new NixAttrs(Arrays.copyOf(k, n), Arrays.copyOf(v, n));
            }
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < y.size(); i++) if (x.getRaw(y.keys()[i]) != null) keep.add(i);
            return subset(y, keep);
        });
        b.add("catAttrs", 2, a -> {
            String name = Types.string(a[0]).value;
            List<Object> out = new ArrayList<>();
            for (Object x : Types.list(a[1]).items) {
                Object v = Types.attrs(x).getRaw(name);
                if (v != null) out.add(v);
            }
            return new NixList(out.toArray());
        });
        b.add("mapAttrs", 2, a -> {
            NixAttrs s = Types.attrs(a[1]);
            return new NixAttrs(s.keys(), Thunk.mapAttrs(a[0], s));
        });
        b.add("zipAttrsWith", 2, a -> {
            TreeMap<String, List<Object>> m = new TreeMap<>();
            for (Object x : Types.list(a[1]).items) {
                NixAttrs s = Types.attrs(x);
                for (int i = 0; i < s.size(); i++)
                    m.computeIfAbsent(s.key(i), k -> new ArrayList<>()).add(s.values()[i]);
            }
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, v) -> out.put(k, Thunk.apply(a[0], new NixString(k), new NixList(v.toArray()))));
            return NixAttrs.of(out);
        });
        b.add("functionArgs", 1, a -> {
            Object f = Types.function(a[0]);
            if (!(f instanceof NixLambda l) || l.info.formals() == null) return NixAttrs.EMPTY;
            return new NixAttrs(l.info.formalNames(), l.info.formalDefaults().clone());
        });
    }

    static NixAttrs subset(NixAttrs s, List<Integer> keep) {
        Name[] k = new Name[keep.size()];
        Object[] v = new Object[keep.size()];
        for (int i = 0; i < k.length; i++) {
            k[i] = s.keys()[keep.get(i)];
            v[i] = s.values()[keep.get(i)];
        }
        return new NixAttrs(k, v);
    }
}
