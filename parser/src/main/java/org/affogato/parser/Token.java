package org.affogato.parser;

/** For PATH and HPATH, {@code interp} tells whether an interpolation follows immediately. */
public record Token(TokenType type, String text, int start, int end, boolean interp) {}
