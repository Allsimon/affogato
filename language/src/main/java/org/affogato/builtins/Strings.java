package org.affogato.builtins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.affogato.runtime.*;
import org.affogato.store.Hashes;

final class Strings {
    private Strings() {}

    static void register(Builtins b) {
        b.add("toString", 1, a -> Coerce.toNixString(a[0], true, false));
        b.add("stringLength", 1, a ->
                (long) Coerce.toNixString(a[0], false, true).value.length());
        b.add("substring", 3, a -> {
            long start = Types.integer(a[0]);
            long len = Types.integer(a[1]);
            NixString s = Coerce.toNixString(a[2], false, true);
            if (start < 0) throw new NixException("negative start position in 'substring'");
            int n = s.value.length();
            if (start >= n) return new NixString("", s.context);
            long end = len < 0 ? n : Math.min(n, start + len);
            return new NixString(s.value.substring((int) start, (int) end), s.context);
        });
        b.add("concatStringsSep", 2, a -> {
            NixString sep = Types.string(a[0]);
            StringBuilder sb = new StringBuilder();
            String[] ctx = sep.context;
            Object[] items = Types.list(a[1]).items;
            for (int i = 0; i < items.length; i++) {
                if (i > 0) sb.append(sep.value);
                NixString s = Coerce.toNixString(items[i], false, true);
                sb.append(s.value);
                ctx = NixString.merge(ctx, s.context);
            }
            return new NixString(sb.toString(), ctx);
        });
        b.add("replaceStrings", 3, a -> replaceStrings(a[0], a[1], a[2]));
        b.add("match", 2, a -> {
            Pattern p = regex(Types.string(a[0]).value);
            NixString s = Types.string(a[1]);
            Matcher m = p.matcher(s.value);
            if (!m.matches()) return NixNull.INSTANCE;
            return groups(m, s);
        });
        b.add("split", 2, a -> {
            Pattern p = regex(Types.string(a[0]).value);
            NixString s = Types.string(a[1]);
            Matcher m = p.matcher(s.value);
            List<Object> out = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                out.add(new NixString(s.value.substring(last, m.start())));
                out.add(groups(m, s));
                last = m.end();
            }
            if (out.isEmpty()) return new NixList(new Object[] {s});
            out.add(new NixString(s.value.substring(last)));
            return new NixList(out.toArray());
        });
        b.add("hashString", 2, a -> {
            String algo = Types.string(a[0]).value;
            try {
                return new NixString(Hashes.hex(Hashes.hash(algo, Types.string(a[1]).value)));
            } catch (IllegalArgumentException e) {
                throw new NixException(e.getMessage());
            }
        });
        b.add("parseDrvName", 1, a -> {
            String s = Types.stringNoContext(a[0]);
            String name = s;
            String version = "";
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '-' && i + 1 < s.length() && !Character.isLetter(s.charAt(i + 1))) {
                    name = s.substring(0, i);
                    version = s.substring(i + 1);
                    break;
                }
            }
            TreeMap<String, Object> m = new TreeMap<>();
            m.put("name", new NixString(name));
            m.put("version", new NixString(version));
            return NixAttrs.of(m);
        });
        b.add("compareVersions", 2, a -> (long) compareVersions(Types.string(a[0]).value, Types.string(a[1]).value));
        b.add("splitVersion", 1, a -> {
            String v = Types.string(a[0]).value;
            List<Object> out = new ArrayList<>();
            int[] p = {0};
            while (p[0] < v.length()) {
                String c = nextComponent(v, p);
                if (c.isEmpty()) break;
                out.add(new NixString(c));
            }
            return new NixList(out.toArray());
        });
        b.add("baseNameOf", 1, a -> {
            Object v = Thunk.force(a[0]);
            NixString s = Coerce.toNixString(v, false, false);
            return new NixString(baseNameOf(s.value), s.context);
        });
        b.add("dirOf", 1, a -> {
            Object v = Thunk.force(a[0]);
            if (v instanceof NixPath p) {
                String d = org.affogato.NixContext.parentDir(p.path());
                return new NixPath(d);
            }
            NixString s = Coerce.toNixString(v, false, false);
            int i = s.value.lastIndexOf('/');
            String d = i < 0 ? "." : i == 0 ? "/" : s.value.substring(0, i);
            return new NixString(d, s.context);
        });
    }

    static String baseNameOf(String path) {
        if (path.isEmpty()) return "";
        int last = path.length() - 1;
        if (path.charAt(last) == '/' && last > 0) last--;
        int pos = path.lastIndexOf('/', last);
        pos = pos < 0 ? 0 : pos + 1;
        return path.substring(pos, last + 1);
    }

    private static Object replaceStrings(Object fromV, Object toV, Object sV) {
        NixList from = Types.list(fromV);
        NixList to = Types.list(toV);
        if (from.size() != to.size()) {
            throw new NixException(
                    "'from' and 'to' arguments passed to builtins.replaceStrings have different lengths");
        }
        String[] fromS = new String[from.size()];
        for (int i = 0; i < fromS.length; i++) fromS[i] = Types.string(from.items[i]).value;
        NixString[] toS = new NixString[to.size()];
        NixString s = Types.string(sV);
        String[] ctx = s.context;
        StringBuilder res = new StringBuilder();
        String str = s.value;
        for (int p = 0; p <= str.length(); ) {
            boolean found = false;
            for (int i = 0; i < fromS.length; i++) {
                if (str.startsWith(fromS[i], p)) {
                    found = true;
                    if (toS[i] == null) toS[i] = Coerce.toNixString(to.items[i], false, true);
                    res.append(toS[i].value);
                    ctx = NixString.merge(ctx, toS[i].context);
                    if (fromS[i].isEmpty()) {
                        if (p < str.length()) res.append(str.charAt(p));
                        p++;
                    } else {
                        p += fromS[i].length();
                    }
                    break;
                }
            }
            if (!found) {
                if (p < str.length()) res.append(str.charAt(p));
                p++;
            }
        }
        return new NixString(res.toString(), ctx);
    }

    private static NixList groups(Matcher m, NixString s) {
        Object[] out = new Object[m.groupCount()];
        for (int i = 1; i <= out.length; i++) {
            String g = m.group(i);
            out[i - 1] = g == null ? NixNull.INSTANCE : new NixString(g);
        }
        return new NixList(out);
    }

    private static final Map<String, Pattern> REGEX_CACHE = new HashMap<>();

    static synchronized Pattern regex(String posix) {
        Pattern p = REGEX_CACHE.get(posix);
        if (p == null) {
            try {
                p = Pattern.compile(translateRegex(posix), Pattern.DOTALL);
            } catch (PatternSyntaxException e) {
                throw new NixException("invalid regular expression '" + posix + "'");
            }
            REGEX_CACHE.put(posix, p);
        }
        return p;
    }

    private static final Map<String, String> CLASSES = Map.ofEntries(
            Map.entry("alpha", "\\p{Alpha}"),
            Map.entry("digit", "0-9"),
            Map.entry("alnum", "\\p{Alnum}"),
            Map.entry("upper", "\\p{Upper}"),
            Map.entry("lower", "\\p{Lower}"),
            Map.entry("space", "\\s\\x0B"),
            Map.entry("blank", " \\t"),
            Map.entry("punct", "\\p{Punct}"),
            Map.entry("print", "\\p{Print}"),
            Map.entry("graph", "\\p{Graph}"),
            Map.entry("cntrl", "\\p{Cntrl}"),
            Map.entry("xdigit", "0-9A-Fa-f"));

    /** Translates a POSIX extended regular expression to {@link Pattern} syntax. */
    static String translateRegex(String re) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < re.length()) {
            char c = re.charAt(i);
            if (c == '\\' && i + 1 < re.length()) {
                out.append(c).append(re.charAt(i + 1));
                i += 2;
            } else if (c == '[') {
                i = bracket(re, i + 1, out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int bracket(String re, int i, StringBuilder out) {
        out.append('[');
        if (i < re.length() && re.charAt(i) == '^') {
            out.append('^');
            i++;
        }
        boolean first = true;
        while (i < re.length()) {
            char c = re.charAt(i);
            if (c == ']' && !first) {
                out.append(']');
                return i + 1;
            }
            first = false;
            if (c == '[' && i + 1 < re.length() && ":=.".indexOf(re.charAt(i + 1)) >= 0) {
                char kind = re.charAt(i + 1);
                int end = re.indexOf(kind + "]", i + 2);
                if (end < 0) throw new NixException("invalid regular expression '" + re + "'");
                String name = re.substring(i + 2, end);
                if (kind == ':') {
                    String cls = CLASSES.get(name);
                    if (cls == null) throw new NixException("invalid character class '" + name + "'");
                    out.append(cls);
                } else {
                    out.append(Pattern.quote(name).replace("\\Q", "").replace("\\E", ""));
                }
                i = end + 2;
                continue;
            }
            if (c == '\\' || c == '[' || c == '&' || c == '^' || c == ']') out.append('\\');
            out.append(c);
            i++;
        }
        throw new NixException("invalid regular expression: unterminated bracket expression");
    }

    static String nextComponent(String s, int[] p) {
        while (p[0] < s.length() && (s.charAt(p[0]) == '.' || s.charAt(p[0]) == '-')) p[0]++;
        if (p[0] >= s.length()) return "";
        int start = p[0];
        if (isDigit(s.charAt(p[0]))) {
            while (p[0] < s.length() && isDigit(s.charAt(p[0]))) p[0]++;
        } else {
            while (p[0] < s.length() && !isDigit(s.charAt(p[0])) && s.charAt(p[0]) != '.' && s.charAt(p[0]) != '-')
                p[0]++;
        }
        return s.substring(start, p[0]);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static Integer toInt(String s) {
        if (s.isEmpty() || !s.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean componentsLT(String c1, String c2) {
        Integer n1 = toInt(c1);
        Integer n2 = toInt(c2);
        if (n1 != null && n2 != null) return n1 < n2;
        if (c1.isEmpty() && n2 != null) return true;
        if (c1.equals("pre") && !c2.equals("pre")) return true;
        if (c2.equals("pre")) return false;
        if (n2 != null) return true;
        if (n1 != null) return false;
        return c1.compareTo(c2) < 0;
    }

    static int compareVersions(String v1, String v2) {
        int[] p1 = {0};
        int[] p2 = {0};
        while (p1[0] < v1.length() || p2[0] < v2.length()) {
            String c1 = nextComponent(v1, p1);
            String c2 = nextComponent(v2, p2);
            if (componentsLT(c1, c2)) return -1;
            if (componentsLT(c2, c1)) return 1;
        }
        return 0;
    }
}
