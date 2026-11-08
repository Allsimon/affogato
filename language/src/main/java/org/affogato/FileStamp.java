package org.affogato;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

/** Identifies a regular file's content: any write changes its ctime, which tools cannot set. */
final class FileStamp {
    /** Timestamps are coarse, so a change within the same tick could keep them: as git does for its index. */
    private static final long SETTLED_MILLIS = 1000;

    private static final String ATTRIBUTES = "unix:isRegularFile,dev,ino,size,lastModifiedTime,ctime";

    private FileStamp() {}

    /** Null unless {@code p} is a regular file unchanged for {@link #SETTLED_MILLIS}. */
    static List<Object> of(Path p) {
        long now = System.currentTimeMillis();
        Map<String, Object> a;
        try {
            a = Files.readAttributes(p, ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
        if (!(Boolean) a.get("isRegularFile")) return null;
        FileTime mtime = (FileTime) a.get("lastModifiedTime");
        FileTime ctime = (FileTime) a.get("ctime");
        if (Math.max(mtime.toMillis(), ctime.toMillis()) >= now - SETTLED_MILLIS) return null;
        return List.of(a.get("dev"), a.get("ino"), a.get("size"), mtime, ctime);
    }
}
