package org.affogato.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.affogato.runtime.*;

/** String context builtins. Elements are encoded as Nix does: {@code path}, {@code =drv}, {@code !output!drv}. */
final class Context {
    private Context() {}

    static void register(Builtins b) {
        b.add("unsafeDiscardStringContext", 1, a -> new NixString(Coerce.toNixString(a[0], false, false).value));
        b.add("hasContext", 1, a -> Types.string(a[0]).hasContext());
        b.add("unsafeDiscardOutputDependency", 1, a -> {
            NixString s = Coerce.toNixString(a[0], false, false);
            if (s.context == null) return s;
            TreeSet<String> ctx = new TreeSet<>();
            for (String c : s.context) ctx.add(c.startsWith("=") ? c.substring(1) : c);
            return new NixString(s.value, ctx);
        });
        b.add("addDrvOutputDependencies", 1, a -> {
            NixString s = Coerce.toNixString(a[0], false, false);
            if (s.context == null || s.context.size() != 1) {
                throw new NixException("context of string '" + s.value + "' must have exactly one element");
            }
            String c = s.context.iterator().next();
            if (c.startsWith("!"))
                throw new NixException(
                        "`addDrvOutputDependencies` can only act on derivations, not on a derivation output");
            if (!c.startsWith("=") && !c.endsWith(".drv"))
                throw new NixException("path '" + c + "' is not a derivation");
            return new NixString(s.value, java.util.Set.of(c.startsWith("=") ? c : "=" + c));
        });
        b.add("getContext", 1, a -> {
            NixString s = Types.string(a[0]);
            TreeMap<String, Boolean> paths = new TreeMap<>();
            TreeMap<String, Boolean> all = new TreeMap<>();
            TreeMap<String, List<Object>> outputs = new TreeMap<>();
            if (s.context != null) {
                for (String c : s.context) {
                    if (c.startsWith("=")) {
                        all.put(c.substring(1), true);
                    } else if (c.startsWith("!")) {
                        int sep = c.indexOf('!', 1);
                        outputs.computeIfAbsent(c.substring(sep + 1), k -> new ArrayList<>())
                                .add(new NixString(c.substring(1, sep)));
                    } else {
                        paths.put(c, true);
                    }
                }
            }
            TreeSet<String> keys = new TreeSet<>();
            keys.addAll(paths.keySet());
            keys.addAll(all.keySet());
            keys.addAll(outputs.keySet());
            TreeMap<String, Object> m = new TreeMap<>();
            for (String k : keys) {
                TreeMap<String, Object> info = new TreeMap<>();
                if (paths.containsKey(k)) info.put("path", true);
                if (all.containsKey(k)) info.put("allOutputs", true);
                if (outputs.containsKey(k))
                    info.put("outputs", new NixList(outputs.get(k).toArray()));
                m.put(k, NixAttrs.of(info));
            }
            return NixAttrs.of(m);
        });
        b.add("appendContext", 2, a -> {
            NixString s = Types.string(a[0]);
            TreeSet<String> ctx = s.context == null ? new TreeSet<>() : new TreeSet<>(s.context);
            NixAttrs attrs = Types.attrs(a[1]);
            for (int i = 0; i < attrs.size(); i++) {
                String path = attrs.keys()[i];
                NixAttrs info = Types.attrs(attrs.values()[i]);
                Object p = info.get("path");
                if (p != null && Types.bool(p)) ctx.add(path);
                Object all = info.get("allOutputs");
                if (all != null && Types.bool(all)) {
                    if (!path.endsWith(".drv"))
                        throw new NixException("tried to add all-outputs context of " + path
                                + ", which is not a derivation, to a string");
                    ctx.add("=" + path);
                }
                Object outs = info.get("outputs");
                if (outs != null) {
                    NixList l = Types.list(outs);
                    if (l.size() > 0 && !path.endsWith(".drv"))
                        throw new NixException("tried to add derivation output context of " + path
                                + ", which is not a derivation, to a string");
                    for (Object o : l.items) ctx.add("!" + Types.string(o).value + "!" + path);
                }
            }
            return new NixString(s.value, ctx);
        });
    }
}
