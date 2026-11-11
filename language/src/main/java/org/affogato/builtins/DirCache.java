package org.affogato.builtins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import org.affogato.runtime.Name;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixString;
import org.affogato.runtime.Thunk;
import org.affogato.store.Nar;

/**
 * {@code readDir} results shared across contexts, valid while the directory keeps its inode and mtime: adding,
 * removing or renaming an entry updates the mtime, and an entry's type cannot change in place.
 */
final class DirCache {
    /** nixpkgs' by-name tree is ~20k entries. */
    static final int MAX_ENTRIES = 1 << 18;

    /**
     * Timestamps are coarse, so a change right after the listing could keep the mtime: only directories unchanged
     * for this long are kept, as git does for its index.
     */
    private static final long SETTLED_MILLIS = 1000;

    private record Listing(Object inode, FileTime mtime, Name[] names, Object[] types) {}

    private static final LinkedHashMap<Path, Listing> LISTINGS = new LinkedHashMap<>(1024, 0.75f, true);
    private static int entries;

    private DirCache() {}

    static NixAttrs read(Path dir) throws IOException {
        long now = System.currentTimeMillis();
        BasicFileAttributes a = Files.readAttributes(dir, BasicFileAttributes.class);
        synchronized (LISTINGS) {
            Listing l = LISTINGS.get(dir);
            if (l != null && l.mtime.equals(a.lastModifiedTime()) && l.inode.equals(a.fileKey())) {
                return new NixAttrs(l.names, l.types.clone());
            }
        }
        List<Name> k = new ArrayList<>();
        List<Object> v = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            // Nix gets the types free from d_type; a stat per pkgs/by-name entry is most of a nixpkgs eval.
            s.forEach(p -> {
                k.add(new Name(
                        org.affogato.builtins.Files.utf8ToBytes(p.getFileName().toString())));
                v.add(Thunk.eager(() -> new NixString(Nar.typeOf(p))));
            });
        }
        NixAttrs attrs = NixAttrs.ofPairs(k.toArray(new Name[0]), v.toArray());
        if (a.fileKey() != null && a.lastModifiedTime().toMillis() < now - SETTLED_MILLIS) {
            synchronized (LISTINGS) {
                Listing old = LISTINGS.put(
                        dir,
                        new Listing(
                                a.fileKey(),
                                a.lastModifiedTime(),
                                attrs.keys(),
                                attrs.values().clone()));
                if (old != null) entries -= old.names.length;
                entries += attrs.keys().length;
                var it = LISTINGS.values().iterator();
                while (entries > MAX_ENTRIES && LISTINGS.size() > 1) {
                    entries -= it.next().names.length;
                    it.remove();
                }
            }
        }
        return attrs;
    }
}
