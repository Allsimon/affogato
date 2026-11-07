package org.affogato.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts between offsets and the protocol's positions: a line and a UTF-16 column, as Java strings count. */
final class Lines {
    private final int[] starts;
    private final int length;

    Lines(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        this.starts = starts.stream().mapToInt(Integer::intValue).toArray();
        this.length = text.length();
    }

    Map<String, Object> position(int offset) {
        int line = line(offset);
        return Map.of("line", (long) line, "character", (long) (offset - starts[line]));
    }

    Map<String, Object> range(int start, int end) {
        return Map.of("start", position(start), "end", position(end));
    }

    int offset(Map<?, ?> position) {
        int line = (int) Math.min(((Number) position.get("line")).longValue(), starts.length - 1);
        int offset = starts[line] + (int) ((Number) position.get("character")).longValue();
        int lineEnd = line + 1 < starts.length ? starts[line + 1] - 1 : length;
        return Math.min(offset, lineEnd);
    }

    int line(int offset) {
        int i = java.util.Arrays.binarySearch(starts, offset);
        return i >= 0 ? i : -i - 2;
    }
}
