package org.affogato.runtime;

public record SourceSection(Source getSource, int getCharIndex, int getCharLength) {
    public int getStartLine() {
        return getSource.line(getCharIndex);
    }

    public int getStartColumn() {
        return getSource.column(getCharIndex);
    }
}
