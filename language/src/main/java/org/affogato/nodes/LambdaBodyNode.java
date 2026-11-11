package org.affogato.nodes;

import java.util.Arrays;
import org.affogato.NixContext;
import org.affogato.runtime.*;

/** Binds the argument (and formals, if any) in the lambda's frame, then evaluates the body. */
public final class LambdaBodyNode extends NixNode {
    private final String fnName;
    /** Whether the body reads the argument itself, as {@code x: } or {@code args@}. */
    private final boolean named;

    private final Name[] formals;
    private final int[] formalSlots;
    private final NixNode[] defaults;
    private final boolean ellipsis;
    private final NixNode body;

    public LambdaBodyNode(
            String fnName,
            boolean named,
            Name[] formals,
            int[] formalSlots,
            NixNode[] defaults,
            boolean ellipsis,
            NixNode body) {
        this.fnName = fnName;
        this.named = named;
        this.formals = formals;
        this.formalSlots = formalSlots;
        this.defaults = defaults;
        this.ellipsis = ellipsis;
        this.body = body;
    }

    @Override
    public Object execute(Object[] frame) {
        NixContext ctx = NixContext.get();
        if (ctx.cancelled) throw new Cancelled();
        if (++ctx.callDepth > NixContext.MAX_CALL_DEPTH) {
            ctx.callDepth--;
            throw new NixException("stack overflow; max-call-depth exceeded", this);
        }
        try {
            if (formals != null) {
                bindFormals(frame, frame[NixRootNode.ARG]);
                if (!named) frame[NixRootNode.ARG] = null;
            }
            return body.execute(frame);
        } finally {
            ctx.callDepth--;
        }
    }

    private void bindFormals(Object[] frame, Object arg) {
        NixAttrs attrs = Types.attrs(arg);
        int matched = 0;
        for (int i = 0; i < formals.length; i++) {
            Object v = attrs.getRaw(formals[i]);
            if (v == null) {
                if (defaults[i] == null)
                    throw new NixException(fn() + " called without required argument '" + formals[i].name + "'", this);
                v = defaults[i].executeLazy(frame);
            } else {
                matched++;
            }
            frame[formalSlots[i]] = v;
        }
        if (!ellipsis && matched < attrs.size()) {
            for (Name k : attrs.keys()) {
                if (!Arrays.asList(formals).contains(k))
                    throw new NixException(fn() + " called with unexpected argument '" + k.name + "'", this);
            }
        }
    }

    private String fn() {
        return fnName == null ? "anonymous lambda" : "function '" + fnName + "'";
    }
}
