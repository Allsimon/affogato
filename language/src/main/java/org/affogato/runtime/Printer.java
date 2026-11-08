package org.affogato.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders values the way {@code nix-instantiate --eval} does. */
public final class Printer {
    private static final Pattern IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_'-]*");
    private static final Set<String> KEYWORDS =
            Set.of("if", "then", "else", "assert", "with", "let", "in", "rec", "inherit");

    private final StringBuilder out = new StringBuilder();
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    private Printer() {}

    public static String print(Object v) {
        Printer p = new Printer();
        p.value(v);
        return p.out.toString();
    }

    /** Forces a value completely, as {@code builtins.deepSeq} does. */
    public static void forceDeep(Object v) {
        forceDeep(v, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void forceDeep(Object v, Set<Object> seen) {
        v = Thunk.force(v);
        if (!seen.add(v)) return;
        if (v instanceof NixAttrs a) {
            for (Object x : a.values()) forceDeep(x, seen);
        } else if (v instanceof NixList l) {
            for (Object x : l.items) forceDeep(x, seen);
        }
    }

    /** UTF-8 bytes of {@code «repeated»}, as byte strings are stored one char per byte. */
    static final String REPEATED = "\u00c2\u00abrepeated\u00c2\u00bb";

    /**
     * {@code raw} is the cell holding the value (a thunk or the value itself). Like Nix, attribute sets are tracked
     * by identity, and small lists, which Nix stores inline in their cell, by cell.
     */
    private void value(Object raw) {
        Object v = raw;
        if (v instanceof Thunk t) {
            if (!t.isEvaluated()) {
                out.append("<CODE>");
                return;
            }
            v = t.force();
        }
        switch (v) {
            case Long l -> out.append(l);
            case Double d -> out.append(formatFloat(d));
            case Boolean b -> out.append(b);
            case NixString s -> string(s.value);
            case NixPath p -> out.append(p.path());
            case NixNull n -> out.append("null");
            case NixAttrs a -> {
                if (a.size() > 0 && !seen.add(a)) {
                    out.append(REPEATED);
                } else {
                    attrs(a);
                }
            }
            case NixList l -> {
                if (l.size() > 0 && !seen.add(l.size() <= 2 ? raw : l)) {
                    out.append(REPEATED);
                } else {
                    list(l);
                }
            }
            case NixLambda l -> out.append("<LAMBDA>");
            case PrimOp p -> out.append("<PRIMOP>");
            case PrimOp.Partial p -> out.append("<PRIMOP-APP>");
            default -> throw new IllegalStateException(v.toString());
        }
    }

    private void attrs(NixAttrs a) {
        out.append("{ ");
        for (int i = 0; i < a.size(); i++) {
            attrName(out, a.keys()[i]);
            out.append(" = ");
            value(a.values()[i]);
            out.append("; ");
        }
        out.append("}");
    }

    private void list(NixList l) {
        out.append("[ ");
        for (Object x : l.items) {
            value(x);
            out.append(" ");
        }
        out.append("]");
    }

    private void string(String s) {
        quote(out, s);
    }

    public static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '$' -> out.append(i + 1 < s.length() && s.charAt(i + 1) == '{' ? "\\$" : "$");
                default -> out.append(c);
            }
        }
        out.append('"');
    }

    public static void attrName(StringBuilder out, String name) {
        if (IDENT.matcher(name).matches() && !KEYWORDS.contains(name)) {
            out.append(name);
        } else {
            quote(out, name);
        }
    }

    /** C++ iostream default formatting, i.e. {@code %g} with 6 significant digits. */
    public static String formatFloat(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return (1 / d < 0) ? "-0" : "0";
        BigDecimal bd = new BigDecimal(d).round(new MathContext(6));
        int exp = bd.precision() - bd.scale() - 1;
        if (exp < -4 || exp >= 6) {
            String mantissa = stripZeros(bd.movePointLeft(exp).toPlainString());
            return mantissa + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        return stripZeros(bd.toPlainString());
    }

    private static String stripZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
