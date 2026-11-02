package org.affogato.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() {}

    public static MessageDigest digest(String algo) {
        String name = switch (algo) {
            case "md5" -> "MD5";
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            default -> throw new IllegalArgumentException("unknown hash algorithm '" + algo + "'");
        };
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int size(String algo) {
        return switch (algo) {
            case "md5" -> 16;
            case "sha1" -> 20;
            case "sha256" -> 32;
            case "sha512" -> 64;
            default -> throw new IllegalArgumentException("unknown hash algorithm '" + algo + "'");
        };
    }

    /** Hashes a Nix byte string (one char per byte). */
    public static byte[] hash(String algo, String bytes) {
        return digest(algo).digest(bytes.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static byte[] sha256(String bytes) {
        return hash("sha256", bytes);
    }

    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** Port of Nix's lenient {@code base64Decode}: decoding stops at the first {@code =}. */
    public static byte[] base64Decode(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0;
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (c == '\n') continue;
            int digit = BASE64.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("invalid character in Base64 string: '" + c + "'");
            bits += 6;
            d = d << 6 | digit;
            if (bits >= 8) {
                out.write(d >> (bits - 8) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    /** Folds a hash into {@code size} bytes by XOR, as {@code compressHash} does. */
    public static byte[] compress(byte[] hash, int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < hash.length; i++) out[i % size] ^= hash[i];
        return out;
    }
}
