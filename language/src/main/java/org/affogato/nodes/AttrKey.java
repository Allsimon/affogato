package org.affogato.nodes;

import org.affogato.runtime.*;

/** One element of an attribute path: a static name, or a dynamic expression when {@code name} is null. */
public record AttrKey(String name, NixNode dynamic) {
    String name(Object[] frame) {
        return name != null ? name : Types.string(dynamic.execute(frame)).value;
    }
}
