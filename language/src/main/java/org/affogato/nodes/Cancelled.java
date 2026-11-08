package org.affogato.nodes;

import org.affogato.runtime.*;

/** Not a {@link NixException}, so that {@code tryEval} cannot catch it. */
public final class Cancelled extends RuntimeException {
    Cancelled() {
        super("evaluation cancelled", null, false, false);
    }
}
