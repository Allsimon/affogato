package org.affogato.interp.builtins;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.affogato.interp.runtime.*;

/** TOML 1.0 parser for {@code builtins.fromTOML}; the input is a byte string. */
final class Toml {
    private final String s;
    private int i;
    /** Tables created by a header or a dotted key, which may not be redefined by another header. */
    private final Set<Map<String, Object>> defined = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Inline tables and arrays, which are closed to further modification. */
    private final Set<Object> sealed = Collections.newSetFromMap(new IdentityHashMap<>());

    private Toml(String s) {
        this.s = s;
    }

    static void register(Builtins b) {
        b.add("fromTOML", 1, a -> new Toml(Types.string(a[0]).value).parse());
    }

    private NixException error(String msg) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
        return new NixException("while parsing TOML: " + msg + " at line " + line);
    }

    private boolean eof() {
        return i >= s.length();
    }

    private char peek() {
        return eof() ? '\0' : s.charAt(i);
    }

    private void ws() {
        while (!eof() && (peek() == ' ' || peek() == '\t')) i++;
    }

    private void comment() {
        if (peek() == '#') while (!eof() && peek() != '\n') i++;
    }

    private void eol() {
        ws();
        comment();
        if (eof()) return;
        if (peek() == '\r') i++;
        if (peek() != '\n') throw error("expected newline");
        i++;
    }

    /** Skips whitespace, newlines and comments, as allowed inside arrays. */
    private void wsNl() {
        while (!eof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else if (c == '#') {
                comment();
            } else {
                return;
            }
        }
    }

    private Object parse() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            wsNl();
            if (eof()) break;
            if (s.startsWith("[[", i)) {
                i += 2;
                List<String> key = key();
                ws();
                if (!s.startsWith("]]", i)) throw error("expected ']]'");
                i += 2;
                eol();
                Map<String, Object> parent = navigate(root, key.subList(0, key.size() - 1));
                String last = key.getLast();
                Object existing = parent.get(last);
                List<Object> arr;
                if (existing == null) {
                    arr = new ArrayList<>();
                    parent.put(last, arr);
                } else if (existing instanceof List<?> && !sealed.contains(existing)) {
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) existing;
                    arr = l;
                } else {
                    throw error("key '" + last + "' is already defined");
                }
                current = new LinkedHashMap<>();
                arr.add(current);
            } else if (peek() == '[') {
                i++;
                List<String> key = key();
                ws();
                if (peek() != ']') throw error("expected ']'");
                i++;
                eol();
                current = navigate(root, key);
                if (!defined.add(current)) throw error("table '" + String.join(".", key) + "' is already defined");
            } else {
                keyValue(current);
                eol();
            }
        }
        return toNix(root);
    }

    /** Walks to the table at {@code path}, creating implicit tables; arrays of tables resolve to their last element. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> navigate(Map<String, Object> table, List<String> path) {
        for (String k : path) {
            Object v = table.get(k);
            if (v == null) {
                Map<String, Object> t = new LinkedHashMap<>();
                table.put(k, t);
                table = t;
            } else if (v instanceof Map<?, ?> m && !sealed.contains(m)) {
                table = (Map<String, Object>) m;
            } else if (v instanceof List<?> l
                    && !sealed.contains(l)
                    && !l.isEmpty()
                    && l.getLast() instanceof Map<?, ?> m) {
                table = (Map<String, Object>) m;
            } else {
                throw error("key '" + k + "' is already defined");
            }
        }
        return table;
    }

    private void keyValue(Map<String, Object> table) {
        List<String> key = key();
        ws();
        if (peek() != '=') throw error("expected '='");
        i++;
        ws();
        Object v = value();
        Map<String, Object> target = navigate(table, key.subList(0, key.size() - 1));
        if (key.size() > 1) defined.add(target);
        if (target.containsKey(key.getLast())) throw error("key '" + key.getLast() + "' is already defined");
        target.put(key.getLast(), v);
    }

    private List<String> key() {
        List<String> parts = new ArrayList<>();
        while (true) {
            ws();
            char c = peek();
            if (c == '"') {
                parts.add(basicString());
            } else if (c == '\'') {
                parts.add(literalString());
            } else {
                int start = i;
                while (!eof() && (Character.isLetterOrDigit(peek()) && peek() < 128 || peek() == '_' || peek() == '-'))
                    i++;
                if (i == start) throw error("expected a key");
                parts.add(s.substring(start, i));
            }
            ws();
            if (peek() != '.') return parts;
            i++;
        }
    }

    private Object value() {
        char c = peek();
        if (s.startsWith("\"\"\"", i)) return multilineBasic();
        if (s.startsWith("'''", i)) return multilineLiteral();
        if (c == '"') return basicString();
        if (c == '\'') return literalString();
        if (s.startsWith("true", i)) {
            i += 4;
            return true;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return false;
        }
        if (c == '[') return array();
        if (c == '{') return inlineTable();
        return number();
    }

    private List<Object> array() {
        i++;
        List<Object> out = new ArrayList<>();
        while (true) {
            wsNl();
            if (peek() == ']') {
                i++;
                break;
            }
            out.add(value());
            wsNl();
            if (peek() == ',') {
                i++;
            } else if (peek() == ']') {
                i++;
                break;
            } else {
                throw error("expected ',' or ']'");
            }
        }
        sealed.add(out);
        return out;
    }

    private Map<String, Object> inlineTable() {
        i++;
        Map<String, Object> out = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            i++;
        } else {
            while (true) {
                keyValue(out);
                ws();
                if (peek() == ',') {
                    i++;
                } else if (peek() == '}') {
                    i++;
                    break;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }
        sealed.add(out);
        return out;
    }

    private Object number() {
        int start = i;
        while (!eof()) {
            char c = peek();
            if (c < 128 && Character.isLetterOrDigit(c) || "+-_.:".indexOf(c) >= 0) {
                i++;
            } else if (c == ' '
                    && i + 1 < s.length()
                    && Character.isDigit(s.charAt(i + 1))
                    && s.substring(start, i).matches("\\d{4}-\\d{2}-\\d{2}")) {
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        if (t.matches("\\d{4}-\\d{2}-\\d{2}.*") || t.matches("\\d{2}:\\d{2}:\\d{2}.*")) {
            throw error("Dates and times are not supported");
        }
        return number(t);
    }

    private Object number(String t) {
        if (t.equals("inf") || t.equals("+inf")) return Double.POSITIVE_INFINITY;
        if (t.equals("-inf")) return Double.NEGATIVE_INFINITY;
        if (t.endsWith("nan")) return Double.NaN;
        if (t.contains("__") || t.startsWith("_") || t.endsWith("_")) throw error("invalid number '" + t + "'");
        String u = t.replace("_", "");
        try {
            if (u.startsWith("0x")) return Long.parseLong(u.substring(2), 16);
            if (u.startsWith("0o")) return Long.parseLong(u.substring(2), 8);
            if (u.startsWith("0b")) return Long.parseLong(u.substring(2), 2);
            if (u.matches("[+-]?(0|[1-9]\\d*)")) return Long.parseLong(u.startsWith("+") ? u.substring(1) : u);
            if (u.matches("[+-]?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) return Double.parseDouble(u);
        } catch (NumberFormatException e) {
            throw error("number '" + t + "' is out of range");
        }
        throw error("invalid value '" + t + "'");
    }

    private String basicString() {
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof() || peek() == '\n') throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                escape(sb);
            } else {
                sb.append(c);
            }
        }
    }

    private void escape(StringBuilder sb) {
        if (eof()) throw error("unterminated string");
        char e = s.charAt(i++);
        switch (e) {
            case 'b' -> sb.append('\b');
            case 't' -> sb.append('\t');
            case 'n' -> sb.append('\n');
            case 'f' -> sb.append('\f');
            case 'r' -> sb.append('\r');
            case 'e' -> sb.append('\u001b');
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case 'u', 'U' -> {
                int n = e == 'u' ? 4 : 8;
                if (i + n > s.length()) throw error("invalid unicode escape");
                int cp = Integer.parseInt(s.substring(i, i + n), 16);
                i += n;
                String u = new String(Character.toChars(cp));
                sb.append(new String(u.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
            }
            default -> throw error("invalid escape sequence '\\" + e + "'");
        }
    }

    private String literalString() {
        i++;
        int end = s.indexOf('\'', i);
        int nl = s.indexOf('\n', i);
        if (end < 0 || nl >= 0 && nl < end) throw error("unterminated string");
        String r = s.substring(i, end);
        i = end + 1;
        return r;
    }

    private String multilineBasic() {
        i += 3;
        if (peek() == '\n') i++;
        else if (s.startsWith("\r\n", i)) i += 2;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) throw error("unterminated string");
            if (s.startsWith("\"\"\"", i)) {
                int n = 0;
                while (i + n < s.length() && s.charAt(i + n) == '"') n++;
                if (n > 5) throw error("too many quotes");
                sb.append("\"".repeat(n - 3));
                i += n;
                return sb.toString();
            }
            char c = s.charAt(i++);
            if (c == '\\') {
                int save = i;
                while (!eof() && (peek() == ' ' || peek() == '\t')) i++;
                if (peek() == '\n' || s.startsWith("\r\n", i)) {
                    while (!eof() && Character.isWhitespace(peek())) i++;
                } else {
                    i = save;
                    escape(sb);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private String multilineLiteral() {
        i += 3;
        if (peek() == '\n') i++;
        else if (s.startsWith("\r\n", i)) i += 2;
        int end = s.indexOf("'''", i);
        if (end < 0) throw error("unterminated string");
        for (int k = 0; k < 2 && end + 3 < s.length() && s.charAt(end + 3) == '\''; k++) end++;
        String r = s.substring(i, end);
        i = end + 3;
        return r;
    }

    private static Object toNix(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put((String) k, toNix(x)));
            return NixAttrs.of(out);
        }
        if (v instanceof List<?> l)
            return new NixList(l.stream().map(Toml::toNix).toArray());
        if (v instanceof String str) return new NixString(str);
        return v;
    }
}
