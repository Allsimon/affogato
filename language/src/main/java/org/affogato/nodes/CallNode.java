package org.affogato.nodes;

import org.affogato.runtime.*;

public final class CallNode extends NixNode {
    private final NixNode fn;
    private final NixNode[] args;

    public CallNode(NixNode fn, NixNode[] args) {
        this.fn = fn;
        this.args = args;
    }

    @Override
    public Object execute(Object[] frame) {
        Object f = fn.execute(frame);
        int n = args.length;
        for (int i = 0; i < n; ) {
            if (f instanceof PrimOp p && p.arity > 1 && p.arity <= n - i) {
                Object[] all = new Object[p.arity];
                for (int j = 0; j < all.length; j++) all[j] = args[i++].executeLazy(frame);
                f = Thunk.force(p.impl.call(all));
            } else {
                f = Apply.apply(f, args[i++].executeLazy(frame));
            }
        }
        return f;
    }
}
