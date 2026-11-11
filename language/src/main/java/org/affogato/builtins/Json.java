package org.affogato.builtins;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.affogato.NixContext;
import org.affogato.runtime.*;

public final class Json {
    private final StringBuilder out;
    private final SortedSet<String> context;
    private final boolean copyToStore;

    private Json(StringBuilder out, SortedSet<String> context, boolean copyToStore) {
        this.out = out;
        this.context = context;
        this.copyToStore = copyToStore;
    }

    static void register(Builtins b) {
        b.add("toJSON", 1, a -> toJson(a[0], true));
        b.add("fromJSON", 1, a -> new Parser(Types.string(a[0]).value).parseTop());
    }

    /** Port of {@code printValueAsJSON}. */
    public static NixString toJson(Object v, boolean copyToStore) {
        StringBuilder out = new StringBuilder();
        TreeSet<String> context = new TreeSet<>();
        write(out, context, v, copyToStore);
        return new NixString(out.toString(), context);
    }

    /** Appends the JSON of {@code v} to {@code out}, and its string context to {@code context}. */
    static void write(StringBuilder out, SortedSet<String> context, Object v, boolean copyToStore) {
        new Json(out, context, copyToStore).value(v);
    }

    private void value(Object v) {
        NixContext ctx = NixContext.get();
        if (++ctx.callDepth > NixContext.MAX_CALL_DEPTH) {
            ctx.callDepth--;
            throw new NixException("stack overflow; max-call-depth exceeded");
        }
        try {
            valueInner(v);
        } finally {
            ctx.callDepth--;
        }
    }

    private void valueInner(Object v) {
        v = Thunk.force(v);
        switch (v) {
            case Long l -> out.append(l);
            case Double d -> out.append(formatDouble(d));
            case Boolean b -> out.append(b);
            case NixNull n -> out.append("null");
            case NixString s -> {
                if (s.context != null) Collections.addAll(context, s.context);
                string(s.value);
            }
            case NixPath p -> {
                if (copyToStore) {
                    String sp = NixContext.get().copyPathToStore(p.path());
                    context.add(sp);
                    string(sp);
                } else {
                    string(p.path());
                }
            }
            case NixList l -> {
                out.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) out.append(',');
                    value(l.items[i]);
                }
                out.append(']');
            }
            case NixAttrs a -> {
                if (a.has("__toString")) {
                    NixString s = Coerce.toNixString(a, false, copyToStore);
                    if (s.context != null) Collections.addAll(context, s.context);
                    string(s.value);
                    return;
                }
                Object outPath = a.getRaw("outPath");
                if (outPath != null) {
                    value(outPath);
                    return;
                }
                out.append('{');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) out.append(',');
                    string(a.keys()[i]);
                    out.append(':');
                    value(a.values()[i]);
                }
                out.append('}');
            }
            default -> throw new NixException("cannot convert " + Types.describe(v) + " to JSON");
        }
    }

    private void string(String s) {
        checkUtf8(s);
        quote(out, s);
    }

    /** nlohmann::json's check when it serializes: well-formed UTF-8, without overlong forms or surrogates. */
    private static void checkUtf8(String s) {
        int i = 0;
        while (i < s.length()) {
            int b = s.charAt(i);
            int n;
            int lo = 0x80;
            int hi = 0xbf;
            if (b < 0x80) {
                i++;
                continue;
            } else if (b >= 0xc2 && b <= 0xdf) {
                n = 1;
            } else if (b >= 0xe0 && b <= 0xef) {
                n = 2;
                if (b == 0xe0) lo = 0xa0;
                if (b == 0xed) hi = 0x9f;
            } else if (b >= 0xf0 && b <= 0xf4) {
                n = 3;
                if (b == 0xf0) lo = 0x90;
                if (b == 0xf4) hi = 0x8f;
            } else {
                throw invalidUtf8(i, b);
            }
            for (int k = 1; k <= n; k++) {
                if (i + k >= s.length()) {
                    throw new NixException("JSON serialization error: [json.exception.type_error.316] incomplete UTF-8"
                            + " string; last byte: " + hexByte(s.charAt(s.length() - 1)));
                }
                int c = s.charAt(i + k);
                if (c < (k == 1 ? lo : 0x80) || c > (k == 1 ? hi : 0xbf)) throw invalidUtf8(i + k, c);
            }
            i += n + 1;
        }
    }

    private static NixException invalidUtf8(int index, int b) {
        return new NixException("JSON serialization error: [json.exception.type_error.316] invalid UTF-8 byte at index "
                + index + ": " + hexByte(b));
    }

    private static String hexByte(int b) {
        return String.format("0x%02X", b);
    }

    static void quote(StringBuilder out, String s) {
        out.append('"');
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\') continue;
            out.append(s, start, i);
            start = i + 1;
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(String.format("\\u%04x", (int) c));
            }
        }
        out.append(s, start, s.length()).append('"');
    }

    /** nlohmann::json's shortest round-trip formatting. */
    static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int k = digits.length();
        int n = k - bd.scale();
        StringBuilder sb = new StringBuilder(d < 0 ? "-" : "");
        if (k <= n && n <= 15) {
            sb.append(digits).append("0".repeat(n - k)).append(".0");
        } else if (0 < n && n <= 15) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-4 < n && n <= 0) {
            sb.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            sb.append(digits.charAt(0));
            if (k > 1) sb.append('.').append(digits, 1, k);
            int e = n - 1;
            sb.append('e').append(e < 0 ? '-' : '+');
            int ae = Math.abs(e);
            sb.append(ae < 10 ? "0" + ae : Integer.toString(ae));
        }
        return sb.toString();
    }

    /** JSON parser producing Nix values; strings come back as UTF-8 byte strings. */
    static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        private NixException error(String what) {
            return new NixException("JSON parse error: " + what + " at offset " + i);
        }

        Object parseTop() {
            Object v = parse();
            ws();
            if (i != s.length()) throw error("unexpected trailing characters");
            return v;
        }

        private void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        private Object parse() {
            ws();
            if (i >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    TreeMap<String, Object> m = new TreeMap<>();
                    ws();
                    if (peek('}')) return NixAttrs.of(m);
                    do {
                        ws();
                        String k = str();
                        ws();
                        expect(':');
                        m.put(k, parse());
                        ws();
                    } while (accept(','));
                    expect('}');
                    return NixAttrs.of(m);
                }
                case '[' -> {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (peek(']')) return new NixList(new Object[0]);
                    do {
                        l.add(parse());
                        ws();
                    } while (accept(','));
                    expect(']');
                    return new NixList(l.toArray());
                }
                case '"' -> {
                    return new NixString(str());
                }
                default -> {
                    if (s.startsWith("true", i)) {
                        i += 4;
                        return true;
                    }
                    if (s.startsWith("false", i)) {
                        i += 5;
                        return false;
                    }
                    if (s.startsWith("null", i)) {
                        i += 4;
                        return NixNull.INSTANCE;
                    }
                    return number();
                }
            }
        }

        private boolean peek(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private boolean accept(char c) {
            ws();
            return peek(c);
        }

        private void expect(char c) {
            if (!peek(c)) throw error("expected '" + c + "'");
        }

        private Object number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isFloat = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isFloat = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isFloat = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty() || t.equals("-")) throw error("unexpected character");
            if (!isFloat) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException e) {
                    throw new NixException("JSON number " + t + " is out of range");
                }
            }
            return Double.parseDouble(t);
        }

        /** Parses a string literal; input and output are byte strings, so \\u escapes are encoded as UTF-8. */
        private String str() {
            if (!peek('"')) throw error("expected string");
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return NixString.checkNoNul(sb.toString());
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw error("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        int cp = hex4();
                        if (Character.isHighSurrogate((char) cp) && s.startsWith("\\u", i)) {
                            i += 2;
                            cp = Character.toCodePoint((char) cp, (char) hex4());
                        }
                        String u = new String(Character.toChars(cp));
                        sb.append(new String(u.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
                    }
                    default -> sb.append(e);
                }
            }
        }

        private int hex4() {
            if (i + 4 > s.length()) throw error("invalid unicode escape");
            int v = Integer.parseInt(s.substring(i, i + 4), 16);
            i += 4;
            return v;
        }
    }
}
