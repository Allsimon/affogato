package org.affogato.interp.builtins;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.affogato.interp.runtime.*;

/** Port of {@code printValueAsXML} (src/libexpr/value-to-xml.cc), without locations. */
final class Xml {
    private final StringBuilder out = new StringBuilder("<?xml version='1.0' encoding='utf-8'?>\n");
    private final Set<String> context = new TreeSet<>();
    private final Set<String> drvsSeen = new HashSet<>();
    private int depth;

    private Xml() {}

    static void register(Builtins b) {
        b.add("toXML", 1, a -> {
            Xml x = new Xml();
            x.open("expr", null);
            x.value(a[0]);
            x.close("expr");
            return new NixString(x.out.toString(), x.context);
        });
    }

    private void indent() {
        out.append("  ".repeat(depth));
    }

    private void attrs(TreeMap<String, String> attrs) {
        if (attrs == null) return;
        attrs.forEach((k, v) ->
                out.append(' ').append(k).append("=\"").append(escape(v)).append('"'));
    }

    private void open(String name, TreeMap<String, String> attrs) {
        indent();
        out.append('<').append(name);
        attrs(attrs);
        out.append(">\n");
        depth++;
    }

    private void close(String name) {
        depth--;
        indent();
        out.append("</").append(name).append(">\n");
    }

    private void empty(String name, TreeMap<String, String> attrs) {
        indent();
        out.append('<').append(name);
        attrs(attrs);
        out.append(" />\n");
    }

    private static TreeMap<String, String> attr(String k, String v) {
        TreeMap<String, String> m = new TreeMap<>();
        m.put(k, v);
        return m;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("&quot;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '\n' -> sb.append("&#xA;");
                case '\r' -> sb.append("&#xD;");
                case '\t' -> sb.append("&#x9;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private void showAttrs(NixAttrs a) {
        for (int i = 0; i < a.size(); i++) {
            open("attr", attr("name", a.keys()[i]));
            value(a.values()[i]);
            close("attr");
        }
    }

    private void value(Object v) {
        v = Thunk.force(v);
        switch (v) {
            case Long l -> empty("int", attr("value", l.toString()));
            case Boolean b -> empty("bool", attr("value", b.toString()));
            case NixString s -> {
                if (s.context != null) context.addAll(s.context);
                empty("string", attr("value", s.value));
            }
            case NixPath p -> empty("path", attr("value", p.path()));
            case NixNull n -> empty("null", null);
            case Double d -> empty("float", attr("value", Printer.formatFloat(d)));
            case NixAttrs a -> {
                if (a.isDerivation()) {
                    TreeMap<String, String> xa = new TreeMap<>();
                    String drvPath = "";
                    if (a.get("drvPath") instanceof NixString s) xa.put("drvPath", drvPath = s.value);
                    if (a.get("outPath") instanceof NixString s) xa.put("outPath", s.value);
                    open("derivation", xa);
                    if (!drvPath.isEmpty() && drvsSeen.add(drvPath)) {
                        showAttrs(a);
                    } else {
                        empty("repeated", null);
                    }
                    close("derivation");
                } else {
                    open("attrs", null);
                    showAttrs(a);
                    close("attrs");
                }
            }
            case NixList l -> {
                open("list", null);
                for (Object x : l.items) value(x);
                close("list");
            }
            case NixLambda l -> {
                open("function", null);
                if (l.info.formals() != null) {
                    TreeMap<String, String> pa = new TreeMap<>();
                    if (l.info.arg() != null) pa.put("name", l.info.arg());
                    if (l.info.ellipsis()) pa.put("ellipsis", "1");
                    open("attrspat", pa);
                    String[] names = l.info.formals().clone();
                    Arrays.sort(names);
                    for (String n : names) empty("attr", attr("name", n));
                    close("attrspat");
                } else {
                    empty("varpat", attr("name", l.info.arg()));
                }
                close("function");
            }
            default -> empty("unevaluated", null);
        }
    }
}
