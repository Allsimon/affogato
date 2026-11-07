package org.affogato.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the protocol: objects are {@link Map}s, arrays {@link List}s, numbers {@link Long} or {@link
 * Double}, and {@code null} is {@code null}.
 */
public final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String src) {
        Json json = new Json(src);
        Object value = json.value();
        json.space();
        if (json.pos != src.length()) throw json.error("trailing characters");
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + pos);
    }

    private void space() {
        while (pos < src.length() && " \t\r\n".indexOf(src.charAt(pos)) >= 0) pos++;
    }

    private Object value() {
        space();
        if (pos >= src.length()) throw error("unexpected end");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object value) {
        if (!src.startsWith(word, pos)) throw error("unexpected character");
        pos += word.length();
        return value;
    }

    private Map<String, Object> object() {
        Map<String, Object> object = new LinkedHashMap<>();
        pos++;
        space();
        if (src.charAt(pos) == '}') {
            pos++;
            return object;
        }
        while (true) {
            space();
            String key = string();
            space();
            if (src.charAt(pos++) != ':') throw error("expected ':'");
            object.put(key, value());
            space();
            char c = src.charAt(pos++);
            if (c == '}') return object;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> array = new ArrayList<>();
        pos++;
        space();
        if (src.charAt(pos) == ']') {
            pos++;
            return array;
        }
        while (true) {
            array.add(value());
            space();
            char c = src.charAt(pos++);
            if (c == ']') return array;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        if (src.charAt(pos) != '"') throw error("expected a string");
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> out.append(e);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        String text = src.substring(start, pos);
        if (text.isEmpty()) throw error("unexpected character");
        return text.contains(".") || text.contains("e") || text.contains("E")
                ? (Object) Double.parseDouble(text)
                : (Object) Long.parseLong(text);
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> {
                out.append('"');
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> {
                            if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                            else out.append(c);
                        }
                    }
                }
                out.append('"');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    write(String.valueOf(e.getKey()), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> out.append(value);
        }
    }
}
