package org.affogato.interp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.affogato.interp.runtime.NixException;

/** {@code [-I PATH]... [-A ATTR] [--strict|--json|--drv-path] [--timeout MS] (-E EXPR | FILE)}. */
public final class Main {
    private Main() {}

    public static void main(String[] argv) throws Exception {
        List<String> path = new ArrayList<>();
        String attr = "";
        String output = "eval";
        String expr = null;
        String file = null;
        long timeout = 0;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "-I" -> path.add(argv[++i]);
                case "-A" -> attr = argv[++i];
                case "-E" -> expr = argv[++i];
                case "--strict" -> output = "strict";
                case "--json" -> output = "json";
                case "--drv-path" -> output = "drv-path";
                case "--timeout" -> timeout = Long.parseLong(argv[++i]);
                default -> file = argv[i];
            }
        }
        String env = System.getenv("NIX_PATH");
        if (env != null && !env.isEmpty()) path.add(env);
        String name;
        String content;
        if (expr != null) {
            name = "";
            content = new String(expr.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        } else {
            Path p = Path.of(file).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) p = p.resolve("default.nix");
            name = p.toString();
            content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        }
        var options = new Interpreter.Options(String.join(":", path), "", "", output, attr, "");
        try {
            String out = Interpreter.evaluate(options, name, content, timeout);
            new java.io.PrintStream(System.out, true, StandardCharsets.ISO_8859_1).println(out);
        } catch (NixException | org.affogato.interp.nodes.Nodes.Cancelled e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }
}
