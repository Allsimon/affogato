package org.affogato.runtime;

import java.util.Arrays;

public final class Source {
    private final String name;
    private final String text;
    private int[] lineStarts;

    public Source(String name, String text) {
        this.name = name;
        this.text = text;
    }

    public String getName() {
        return name;
    }

    public CharSequence getCharacters() {
        return text;
    }

    public int getLength() {
        return text.length();
    }

    public SourceSection createSection(int start, int length) {
        return new SourceSection(this, start, length);
    }

    int line(int offset) {
        int i = Arrays.binarySearch(lineStarts(), offset);
        return i >= 0 ? i + 1 : -i - 1;
    }

    int column(int offset) {
        return offset - lineStarts()[line(offset) - 1] + 1;
    }

    private int[] lineStarts() {
        if (lineStarts == null) {
            int n = 1;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
            int[] starts = new int[n];
            for (int i = 0, k = 1; i < text.length(); i++) if (text.charAt(i) == '\n') starts[k++] = i + 1;
            lineStarts = starts;
        }
        return lineStarts;
    }
}
