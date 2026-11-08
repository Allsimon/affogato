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
            for (int i = 0; i < out.length; i++) out[i] = new NixString(s.keys()[i]);
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
            String[] k = new String[s.size() - dropped];
            Object[] v = new Object[k.length];
            var p = s.positions() == null ? null : new SourceSection[k.length];
            for (int i = 0, o = 0; i < s.size(); i++) {
                if (drop[i]) continue;
                k[o] = s.keys()[i];
                v[o] = s.values()[i];
                if (p != null) p[o] = s.positions()[i];
                o++;
            }
            return new NixAttrs(k, v, p);
        });
        b.add("listToAttrs", 1, a -> {
            Object[] items = Types.list(a[0]).items;
            String[] k = new String[items.length];
            Object[] v = new Object[items.length];
            for (int i = 0; i < items.length; i++) {
                NixAttrs e = Types.attrs(items[i]);
                Object name = e.get("name");
                if (name == null) throw new NixException("attribute 'name' missing");
                Object value = e.getRaw("value");
                if (value == null) throw new NixException("attribute 'value' missing");
                k[i] = Types.string(name).value;
                v[i] = value;
            }
            return NixAttrs.ofPairs(k, v);
        });
        b.add("intersectAttrs", 2, a -> {
            NixAttrs x = Types.attrs(a[0]);
            NixAttrs y = Types.attrs(a[1]);
            if (x.size() < y.minSize()) {
                String[] xk = x.keys();
                String[] k = new String[xk.length];
                Object[] v = new Object[xk.length];
                var p = new SourceSection[xk.length];
                int n = 0;
                for (String key : xk) {
                    Object value = y.getRaw(key);
                    if (value == null) continue;
                    k[n] = key;
                    v[n] = value;
                    p[n++] = y.position(key);
                }
                return new NixAttrs(Arrays.copyOf(k, n), Arrays.copyOf(v, n), Arrays.copyOf(p, n));
            }
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < y.size(); i++) if (x.has(y.keys()[i])) keep.add(i);
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
            return new NixAttrs(s.keys(), Thunk.mapAttrs(a[0], s), s.positions());
        });
        b.add("zipAttrsWith", 2, a -> {
            TreeMap<String, List<Object>> m = new TreeMap<>();
            for (Object x : Types.list(a[1]).items) {
                NixAttrs s = Types.attrs(x);
                for (int i = 0; i < s.size(); i++)
                    m.computeIfAbsent(s.keys()[i], k -> new ArrayList<>()).add(s.values()[i]);
            }
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, v) -> out.put(k, Thunk.apply(a[0], new NixString(k), new NixList(v.toArray()))));
            return NixAttrs.of(out);
        });
        b.add("functionArgs", 1, a -> {
            Object f = Types.function(a[0]);
            if (!(f instanceof NixLambda l) || l.info.formals() == null) return NixAttrs.EMPTY;
            TreeMap<String, Integer> order = new TreeMap<>();
            for (int i = 0; i < l.info.formals().length; i++) order.put(l.info.formals()[i], i);
            String[] k = order.keySet().toArray(new String[0]);
            Object[] v = new Object[k.length];
            var p = new SourceSection[k.length];
            for (int i = 0; i < k.length; i++) {
                int j = order.get(k[i]);
                v[i] = l.info.hasDefault()[j];
                p[i] = l.info.formalPositions()[j];
            }
            return new NixAttrs(k, v, p);
        });
    }

    static NixAttrs subset(NixAttrs s, List<Integer> keep) {
        String[] k = new String[keep.size()];
        Object[] v = new Object[keep.size()];
        var p = s.positions() == null ? null : new SourceSection[keep.size()];
        for (int i = 0; i < k.length; i++) {
            k[i] = s.keys()[keep.get(i)];
            v[i] = s.values()[keep.get(i)];
            if (p != null) p[i] = s.positions()[keep.get(i)];
        }
        return new NixAttrs(k, v, p);
    }
}
