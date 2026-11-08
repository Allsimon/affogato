package org.affogato.interp.builtins;

import java.security.MessageDigest;

/** Writes an ATerm as Nix byte strings (one char per byte) into a digest. */
final class AtermWriter {
    private static final boolean[] ESCAPED = new boolean[256];

    static {
        for (char c : "\"\\\n\r\t".toCharArray()) ESCAPED[c] = true;
    }

    private final MessageDigest digest;
    private final byte[] buf = new byte[1 << 16];
    private byte[] scratch = new byte[1 << 12];
    private int len;

    AtermWriter(MessageDigest digest) {
        this.digest = digest;
    }

    void reset() {
        len = 0;
        digest.reset();
    }

    AtermWriter put(char c) {
        if (len == buf.length) drain();
        buf[len++] = (byte) c;
        return this;
    }

    AtermWriter raw(String s) {
        put('"');
        write(bytes(s), 0, s.length());
        return put('"');
    }

    AtermWriter quoted(String s) {
        int n = s.length();
        byte[] b = bytes(s);
        put('"');
        int start = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (!ESCAPED[c]) continue;
            write(b, start, i - start);
            put('\\');
            put(
                    switch (c) {
                        case '\n' -> 'n';
                        case '\r' -> 'r';
                        case '\t' -> 't';
                        default -> (char) c;
                    });
            start = i + 1;
        }
        write(b, start, n - start);
        return put('"');
    }

    byte[] digest() {
        digest.update(buf, 0, len);
        len = 0;
        return digest.digest();
    }

    // The deprecated getBytes keeps the low byte of each char, which is exactly a Nix byte string, without allocating.
    @SuppressWarnings("deprecation")
    private byte[] bytes(String s) {
        int n = s.length();
        if (scratch.length < n) scratch = new byte[Math.max(n, scratch.length * 2)];
        s.getBytes(0, n, scratch, 0);
        return scratch;
    }

    private void write(byte[] b, int off, int n) {
        while (n > 0) {
            if (len == buf.length) drain();
            int k = Math.min(n, buf.length - len);
            System.arraycopy(b, off, buf, len, k);
            len += k;
            off += k;
            n -= k;
        }
    }

    private void drain() {
        digest.update(buf, 0, len);
        len = 0;
    }
}
