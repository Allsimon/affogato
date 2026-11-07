package org.affogato.parser;

import org.affogato.parser.Lexeme.Kind;

/**
 * What {@link TreeParser} builds its tree through, shaped after IntelliJ's {@code PsiBuilder} so an IDE can adapt
 * its own. It reads {@link Tokenizer}'s lexemes with whitespace and comments skipped.
 */
public interface TreeBuilder {
    /** The current token, or null at the end. */
    Kind token();

    /** The token {@code steps} ahead of the current one, or null past the end. */
    Kind lookAhead(int steps);

    void advance();

    /** Starts a node before the current token. */
    Marker mark();

    /** Reports an error at the current token, without consuming it. */
    void error(String message);

    interface Marker {
        /** Starts a node before this one, which will enclose it. */
        Marker precede();

        void done(NodeKind kind);

        /** Ends this node as an error holding the tokens consumed since it started. */
        void error(String message);

        void drop();
    }
}
