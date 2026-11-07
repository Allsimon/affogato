package org.affogato.parser;

import java.util.List;

/**
 * A read-only view of a tree {@link TreeParser} shaped, whatever holds it: {@link SyntaxTree}'s nodes, or an IDE's.
 * Children leave out whitespace and comments.
 */
public interface SyntaxView<N> {
    /** The node's kind, or null for a token, an error or the root. */
    NodeKind kind(N node);

    /** The token's kind, or null for a node. */
    Lexeme.Kind token(N node);

    /** The parent, or null for the root. */
    N parent(N node);

    List<N> children(N node);

    String text(N node);

    /** The comment right before {@code node}, with only whitespace between them, or null. */
    String commentBefore(N node);
}
