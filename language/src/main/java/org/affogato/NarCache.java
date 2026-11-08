package org.affogato;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.affogato.store.Nar;

/** NAR hashes of regular files shared across contexts, valid while their {@link FileStamp} is unchanged. */
final class NarCache {
    static final int MAX_FILES = 1 << 14;

    private record Entry(List<Object> stamp, byte[] hash) {}

    private static final LinkedHashMap<Path, Entry> ENTRIES = new LinkedHashMap<>(256, 0.75f, true);

    private NarCache() {}

    static byte[] sha256(Path p) throws IOException {
        List<Object> stamp = FileStamp.of(p);
        if (stamp == null) return Nar.hash("sha256", p);
        synchronized (ENTRIES) {
            Entry e = ENTRIES.get(p);
            if (e != null && e.stamp.equals(stamp)) return e.hash;
        }
        byte[] hash = Nar.hash("sha256", p);
        synchronized (ENTRIES) {
            ENTRIES.put(p, new Entry(stamp, hash));
            if (ENTRIES.size() > MAX_FILES)
                ENTRIES.remove(ENTRIES.keySet().iterator().next());
        }
        return hash;
    }
}
