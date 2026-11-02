package org.affogato.parser;

public final class ParseException extends RuntimeException {
    public final int offset;

    public ParseException(String message, int offset) {
        super(message);
        this.offset = offset;
    }
}
