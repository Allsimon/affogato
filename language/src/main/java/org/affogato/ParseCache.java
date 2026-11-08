package org.affogato;

import java.util.LinkedHashMap;
import java.util.function.Function;
import org.affogato.nodes.NixRootNode;
import org.affogato.parser.Ast;
import org.affogato.parser.Parser;

/** Parsed and translated files shared across contexts, reused while their content is unchanged. */
final class ParseCache {
    /** hello's closure over nixpkgs is 3 MB of source. */
    static final long MAX_CHARS = 16L << 20;

    private static final class Entry {
        final String content;
        final Ast.Expr ast;
        /** For a plain {@code import}; translation needs the scope's names, so scopedImport does not share it. */
        volatile NixRootNode root;

        Entry(String content, Ast.Expr ast) {
            this.content = content;
            this.ast = ast;
        }
    }

    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(256, 0.75f, true);
    private static long chars;

    private ParseCache() {}

    static Ast.Expr parse(String file, String content) {
        return entry(file, content).ast;
    }

    static NixRootNode translate(String file, String content, Function<Ast.Expr, NixRootNode> translator) {
        Entry e = entry(file, content);
        NixRootNode root = e.root;
        if (root == null) e.root = root = translator.apply(e.ast);
        return root;
    }

    private static Entry entry(String file, String content) {
        synchronized (ENTRIES) {
            Entry e = ENTRIES.get(file);
            if (e != null && e.content.equals(content)) return e;
        }
        Entry e = new Entry(content, Parser.parse(content));
        synchronized (ENTRIES) {
            Entry old = ENTRIES.put(file, e);
            if (old != null) chars -= old.content.length();
            chars += content.length();
            var it = ENTRIES.values().iterator();
            while (chars > MAX_CHARS && ENTRIES.size() > 1) {
                chars -= it.next().content.length();
                it.remove();
            }
        }
        return e;
    }
}
