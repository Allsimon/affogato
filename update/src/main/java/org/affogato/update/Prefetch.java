package org.affogato.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.affogato.store.NixBase32;

/** The hash of what a fetcher downloads, from {@code nix-prefetch-url}, and where it put it in the store. */
final class Prefetch {
    private final String program;
    /** The store path of each URL fetched, by its hash. */
    private final Map<String, String> paths = new ConcurrentHashMap<>();

    Prefetch(String program) {
        this.program = program;
    }

    /** The hash of {@code url}, of the archive unpacked when {@code unpack}, else of the file. */
    String hash(String url, boolean unpack) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(program));
        if (unpack) command.add("--unpack");
        command.addAll(List.of("--type", "sha256", url, "--print-path"));
        Processes.Result r = Processes.run(command, null, null, false);
        if (r.status() != 0) throw new IOException("cannot fetch " + url + ": " + r.last());
        List<String> lines = r.out().strip().lines().toList();
        String hash = lines.isEmpty() ? "" : lines.getFirst().strip();
        String sri;
        try {
            sri = "sha256-" + Base64.getEncoder().encodeToString(NixBase32.decode(hash, 32));
        } catch (IllegalArgumentException e) {
            throw new IOException(program + " printed " + hash + ", not a hash", e);
        }
        if (lines.size() > 1) paths.put(key(url, unpack, sri), lines.get(1).strip());
        return sri;
    }

    /** Where {@code url}, of hash {@code sri}, is in the store, fetching it when this did not. */
    String path(String url, boolean unpack, String sri) throws IOException, InterruptedException {
        String path = paths.get(key(url, unpack, sri));
        if (path != null) return path;
        String got = hash(url, unpack);
        path = paths.get(key(url, unpack, got));
        if (!got.equals(sri)) throw new IOException(url + " has the hash " + got + ", not " + sri);
        if (path == null) throw new IOException(program + " did not print where it put " + url);
        return path;
    }

    private static String key(String url, boolean unpack, String sri) {
        return (unpack ? "unpack " : "file ") + url + " " + sri;
    }
}
