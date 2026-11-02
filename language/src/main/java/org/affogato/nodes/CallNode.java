package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

public final class CallNode extends NixNode {
    @Child
    private NixNode fn;

    @Children
    private final NixNode[] args;

    @Children
    private final ApplyNode[] apply;

    public CallNode(NixNode fn, NixNode[] args) {
        this.fn = fn;
        this.args = args;
        this.apply = new ApplyNode[args.length];
        for (int i = 0; i < args.length; i++) apply[i] = ApplyNodeGen.create();
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object f = fn.execute(frame);
        for (int i = 0; i < args.length; i++) f = apply[i].execute(f, args[i].executeLazy(frame));
        return f;
    }
}
