package org.affogato.store;

public final class NixBase32 {
    private static final String CHARS = "0123456789abcdfghijklmnpqrsvwxyz";

    private NixBase32() {}

    public static String encode(byte[] hash) {
        int len = (hash.length * 8 - 1) / 5 + 1;
        StringBuilder sb = new StringBuilder(len);
        for (int n = len - 1; n >= 0; n--) {
            int b = n * 5;
            int i = b / 8;
            int j = b % 8;
            int c = (hash[i] & 0xff) >> j;
            if (i + 1 < hash.length) c |= (hash[i + 1] & 0xff) << (8 - j);
            sb.append(CHARS.charAt(c & 0x1f));
        }
        return sb.toString();
    }

    public static byte[] decode(String s, int size) {
        byte[] out = new byte[size];
        for (int n = 0; n < s.length(); n++) {
            int digit = CHARS.indexOf(s.charAt(s.length() - n - 1));
            if (digit < 0) throw new IllegalArgumentException("invalid base-32 hash '" + s + "'");
            int b = n * 5;
            int i = b / 8;
            int j = b % 8;
            out[i] |= (byte) (digit << j);
            if (i + 1 < size) {
                out[i + 1] |= (byte) (digit >> (8 - j));
            } else if ((digit >> (8 - j)) != 0) {
                throw new IllegalArgumentException("invalid base-32 hash '" + s + "'");
            }
        }
        return out;
    }
}
