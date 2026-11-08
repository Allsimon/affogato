package org.affogato;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.affogato.store.Nar;

/**
 * NAR hashes of regular files shared across contexts, valid while the file keeps its inode, size, mtime and ctime:
 * any write updates the ctime, which tools cannot set.
 */
final class NarCache {
    static final int MAX_FILES = 1 << 14;

    /** As in DirCache: timestamps are coarse, so only files unchanged for this long are kept. */
    private static final long SETTLED_MILLIS = 1000;

    private static final String ATTRIBUTES = "unix:isRegularFile,dev,ino,size,lastModifiedTime,ctime";

    private record Entry(List<Object> stat, byte[] hash) {}

    private static final LinkedHashMap<Path, Entry> ENTRIES = new LinkedHashMap<>(256, 0.75f, true);

    private NarCache() {}

    static byte[] sha256(Path p) throws IOException {
        long now = System.currentTimeMillis();
        Map<String, Object> a = Files.readAttributes(p, ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        if (!(Boolean) a.get("isRegularFile")) return Nar.hash("sha256", p);
        List<Object> stat =
                List.of(a.get("dev"), a.get("ino"), a.get("size"), a.get("lastModifiedTime"), a.get("ctime"));
        synchronized (ENTRIES) {
            Entry e = ENTRIES.get(p);
            if (e != null && e.stat.equals(stat)) return e.hash;
        }
        byte[] hash = Nar.hash("sha256", p);
        long changed = Math.max(
                ((java.nio.file.attribute.FileTime) a.get("lastModifiedTime")).toMillis(),
                ((java.nio.file.attribute.FileTime) a.get("ctime")).toMillis());
        if (changed < now - SETTLED_MILLIS) {
            synchronized (ENTRIES) {
                ENTRIES.put(p, new Entry(stat, hash));
                if (ENTRIES.size() > MAX_FILES)
                    ENTRIES.remove(ENTRIES.keySet().iterator().next());
            }
        }
        return hash;
    }
}
