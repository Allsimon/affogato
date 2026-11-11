package org.affogato.nodes;

import org.affogato.runtime.NixLambda;
import org.affogato.runtime.SourceSection;

/** A file, thunk or lambda body with a frame of its own: {@code [parent frame, argument, slots...]}. */
public final class NixRootNode {
    public static final int ARG = 1;
    public static final int FIRST_SLOT = 2;

    private final int frameSize;
    private final NixNode body;
    public final String name;
    public final SourceSection section;
    /** Null unless the body of a lambda. */
    public final NixLambda.LambdaInfo info;

    public NixRootNode(int frameSize, NixNode body, String name, SourceSection section) {
        this(frameSize, body, name, section, null);
    }

    public NixRootNode(int frameSize, NixNode body, String name, SourceSection section, NixLambda.LambdaInfo info) {
        this.frameSize = frameSize;
        this.body = body;
        this.name = name;
        this.section = section;
        this.info = info;
    }

    public Object call(Object parent, Object arg) {
        Object[] frame = new Object[frameSize];
        frame[0] = parent;
        frame[ARG] = arg;
        return body.execute(frame);
    }
}
