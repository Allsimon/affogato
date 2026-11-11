package org.affogato.store;

import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;

public final class StorePaths {
    private StorePaths() {}

    public static String make(String storeDir, String type, byte[] sha256, String name) {
        String fingerprint = type + ":sha256:" + Hashes.hex(sha256) + ":" + storeDir + ":" + name;
        byte[] h = Hashes.compress(Hashes.sha256(fingerprint), 20);
        return storeDir + "/" + NixBase32.encode(h) + "-" + name;
    }

    private static String withRefs(String type, Collection<String> refs, boolean self) {
        Collection<String> sorted = refs instanceof SortedSet<String> ? refs : new TreeSet<>(refs);
        int length = type.length() + 5;
        for (String r : sorted) length += r.length() + 1;
        StringBuilder sb = new StringBuilder(length).append(type);
        for (String r : sorted) sb.append(':').append(r);
        if (self) sb.append(":self");
        return sb.toString();
    }

    public static String text(String storeDir, String name, String contents, Collection<String> refs) {
        return text(storeDir, name, Hashes.sha256(contents), refs);
    }

    public static String text(String storeDir, String name, byte[] contentsSha256, Collection<String> refs) {
        return make(storeDir, withRefs("text", refs, false), contentsSha256, name);
    }

    /** A path added recursively with SHA-256, e.g. by path interpolation. */
    public static String source(String storeDir, String name, byte[] narSha256, Collection<String> refs) {
        return make(storeDir, withRefs("source", refs, false), narSha256, name);
    }

    public static String fixedOutput(String storeDir, String name, String algo, byte[] hash, boolean recursive) {
        if (recursive && algo.equals("sha256")) return make(storeDir, "source", hash, name);
        String inner = "fixed:out:" + (recursive ? "r:" : "") + algo + ":" + Hashes.hex(hash) + ":";
        return make(storeDir, "output:out", Hashes.sha256(inner), name);
    }

    public static String output(String storeDir, String drvName, String outputName, byte[] drvHash) {
        String name = outputName.equals("out") ? drvName : drvName + "-" + outputName;
        return make(storeDir, "output:" + outputName, drvHash, name);
    }

    /** Validates a store path name, mirroring {@code checkName}. */
    public static void checkName(String name) {
        if (name.isEmpty()) throw new IllegalArgumentException("name must not be empty");
        if (name.length() > 211)
            throw new IllegalArgumentException("name '" + name + "' must be no longer than 211 characters");
        if (name.charAt(0) == '.'
                && (name.equals(".") || name.equals("..") || name.startsWith(".-") || name.startsWith("..-"))) {
            throw new IllegalArgumentException("name '" + name + "' is not valid");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "+-._?=".indexOf(c) >= 0)) {
                throw new IllegalArgumentException("name '" + name + "' contains illegal character '" + c + "'");
            }
        }
    }
}
