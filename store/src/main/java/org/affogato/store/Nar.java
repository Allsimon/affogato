package org.affogato.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** Streams the NAR serialisation of a filesystem tree into a digest. */
public final class Nar {
    private final MessageDigest md;
    private final BiPredicate<Path, String> filter;
    private final byte[] buf = new byte[65536];

    private Nar(MessageDigest md, BiPredicate<Path, String> filter) {
        this.md = md;
        this.filter = filter;
    }

    /** {@code filter} receives each path below the root and its type ("regular", "directory", "symlink", "unknown"). */
    public static byte[] hash(String algo, Path root, BiPredicate<Path, String> filter) throws IOException {
        Nar nar = new Nar(Hashes.digest(algo), filter);
        nar.str("nix-archive-1");
        nar.dump(root);
        return nar.md.digest();
    }

    public static byte[] hash(String algo, Path root) throws IOException {
        return hash(algo, root, null);
    }

    /** NAR hash of a single regular file holding {@code contents}. */
    public static byte[] hashFlat(String algo, byte[] contents) {
        Nar nar = new Nar(Hashes.digest(algo), null);
        nar.str("nix-archive-1");
        nar.str("(");
        nar.str("type");
        nar.str("regular");
        nar.str("contents");
        nar.bytes(contents);
        nar.str(")");
        return nar.md.digest();
    }

    private void num(long n) {
        md.update(
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array());
    }

    private void pad(long n) {
        int p = (int) ((8 - n % 8) % 8);
        if (p > 0) md.update(new byte[p]);
    }

    private void bytes(byte[] b) {
        num(b.length);
        md.update(b);
        pad(b.length);
    }

    private void str(String s) {
        bytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static String typeOf(Path p) {
        if (Files.isSymbolicLink(p)) return "symlink";
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return "directory";
        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return "regular";
        return "unknown";
    }

    private void dump(Path p) throws IOException {
        str("(");
        String type = typeOf(p);
        switch (type) {
            case "symlink" -> {
                str("type");
                str("symlink");
                str("target");
                bytes(Files.readSymbolicLink(p).toString().getBytes(StandardCharsets.UTF_8));
            }
            case "directory" -> {
                str("type");
                str("directory");
                List<Path> children = new ArrayList<>();
                try (Stream<Path> s = Files.list(p)) {
                    s.forEach(children::add);
                }
                children.sort((a, b) -> compareBytes(nameBytes(a), nameBytes(b)));
                for (Path c : children) {
                    if (filter != null && !filter.test(c, typeOf(c))) continue;
                    str("entry");
                    str("(");
                    str("name");
                    bytes(nameBytes(c));
                    str("node");
                    dump(c);
                    str(")");
                }
            }
            case "regular" -> {
                str("type");
                str("regular");
                if (Files.isExecutable(p)) {
                    str("executable");
                    str("");
                }
                str("contents");
                long size = Files.size(p);
                num(size);
                try (InputStream in = Files.newInputStream(p)) {
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                pad(size);
            }
            default -> throw new IOException("file '" + p + "' has an unsupported type");
        }
        str(")");
    }

    private static byte[] nameBytes(Path p) {
        return p.getFileName().toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int compareBytes(byte[] a, byte[] b) {
        return java.util.Arrays.compareUnsigned(a, b);
    }
}
