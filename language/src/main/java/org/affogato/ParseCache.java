package org.affogato;

import java.util.LinkedHashMap;
import org.affogato.parser.Ast;
import org.affogato.parser.Parser;

/** Parsed files shared across contexts, reused while their content is unchanged. */
final class ParseCache {
    /** hello's closure over nixpkgs is 3 MB of source. */
    static final long MAX_CHARS = 16L << 20;

    private record Entry(String content, Ast.Expr ast) {}

    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(256, 0.75f, true);
    private static long chars;

    private ParseCache() {}

    static Ast.Expr parse(String file, String content) {
        synchronized (ENTRIES) {
            Entry e = ENTRIES.get(file);
            if (e != null && e.content.equals(content)) return e.ast;
        }
        Ast.Expr ast = Parser.parse(content);
        synchronized (ENTRIES) {
            Entry old = ENTRIES.put(file, new Entry(content, ast));
            if (old != null) chars -= old.content.length();
            chars += content.length();
            var it = ENTRIES.values().iterator();
            while (chars > MAX_CHARS && ENTRIES.size() > 1) {
                chars -= it.next().content.length();
                it.remove();
            }
        }
        return ast;
    }
}
