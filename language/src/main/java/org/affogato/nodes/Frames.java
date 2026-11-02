package org.affogato.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

final class Frames {
    private Frames() {}

    @ExplodeLoop
    static Frame up(Frame frame, int depth) {
        Frame f = frame;
        for (int i = 0; i < depth; i++) f = (MaterializedFrame) f.getArguments()[0];
        return f;
    }
}
