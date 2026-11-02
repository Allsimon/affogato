package org.affogato.nodes;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;

/** Root of a file, thunk or lambda body. Arguments are {@code [parent frame, argument]}. */
public final class NixRootNode extends com.oracle.truffle.api.nodes.RootNode {
    @Child
    private NixNode body;

    private final String name;
    private final SourceSection section;

    public NixRootNode(
            TruffleLanguage<?> language, FrameDescriptor descriptor, NixNode body, String name, SourceSection section) {
        super(language, descriptor);
        this.body = body;
        this.name = name;
        this.section = section;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.execute(frame);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public SourceSection getSourceSection() {
        return section;
    }
}
