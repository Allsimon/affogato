package org.affogato.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class Sources {
    private Sources() {}

    static List<Path> expand(List<Path> paths) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : paths) {
            if (!Files.isDirectory(p)) {
                out.add(p);
                continue;
            }
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(f -> f.getFileName().toString().endsWith(".nix") && Files.isRegularFile(f))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    static String read(Path p) throws IOException {
        return decode(Files.readAllBytes(p), p.toString());
    }

    static String read(InputStream in) throws IOException {
        return decode(in.readAllBytes(), "<stdin>");
    }

    /** Strict, so that a file that is not UTF-8 is refused rather than rewritten with replacement characters. */
    private static String decode(byte[] bytes, String name) throws IOException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException(name + ": not valid UTF-8", e);
        }
    }

    static String message(IOException e) {
        return switch (e) {
            case NoSuchFileException n -> n.getFile() + ": no such file";
            case AccessDeniedException a -> a.getFile() + ": permission denied";
            default -> e.getMessage();
        };
    }

    /** {@code line:column} of {@code offset}, counted from 1. */
    static String position(String src, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < Math.min(offset, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return line + ":" + (offset - lineStart + 1);
    }
}
