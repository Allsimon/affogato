package org.affogato.lint;

/** Replaces the source between offsets {@code start} and {@code end} with {@code replacement}. */
public record Edit(int start, int end, String replacement) {}
