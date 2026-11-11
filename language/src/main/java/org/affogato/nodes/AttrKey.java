package org.affogato.nodes;

import org.affogato.runtime.*;

/** One element of an attribute path: a static name, or a dynamic expression when {@code name} is null. */
public record AttrKey(Name name, NixNode dynamic) {
    /** The name to look up: an {@link Name}, or the string a dynamic name evaluates to. */
    Object key(Object[] frame) {
        return name != null ? name : Types.string(dynamic.execute(frame)).value;
    }
}
