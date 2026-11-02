package org.affogato.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.util.Arrays;
import org.affogato.NixContext;
import org.affogato.runtime.NixAttrs;
import org.affogato.runtime.NixException;
import org.affogato.runtime.Types;

/** Binds the argument (and formals, if any) in the lambda's frame, then evaluates the body. */
public final class LambdaBodyNode extends NixNode {
    private final String fnName;
    private final int argSlot;

    @CompilationFinal(dimensions = 1)
    private final String[] formals;

    @CompilationFinal(dimensions = 1)
    private final int[] formalSlots;

    @Children
    private final NixNode[] defaults;

    private final boolean ellipsis;

    @Child
    private NixNode body;

    public LambdaBodyNode(
            String fnName,
            int argSlot,
            String[] formals,
            int[] formalSlots,
            NixNode[] defaults,
            boolean ellipsis,
            NixNode body) {
        this.fnName = fnName;
        this.argSlot = argSlot;
        this.formals = formals;
        this.formalSlots = formalSlots;
        this.defaults = defaults;
        this.ellipsis = ellipsis;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        NixContext ctx = NixContext.get();
        if (++ctx.callDepth > NixContext.MAX_CALL_DEPTH) {
            ctx.callDepth--;
            throw stackOverflow();
        }
        try {
            Object arg = frame.getArguments()[1];
            if (argSlot >= 0) frame.setObject(argSlot, arg);
            if (formals != null) bindFormals(frame, arg);
            return body.execute(frame);
        } finally {
            ctx.callDepth--;
        }
    }

    @TruffleBoundary
    private NixException stackOverflow() {
        return new NixException("stack overflow; max-call-depth exceeded", this);
    }

    @ExplodeLoop
    private void bindFormals(VirtualFrame frame, Object arg) {
        NixAttrs attrs = Types.attrs(arg);
        int matched = 0;
        for (int i = 0; i < formals.length; i++) {
            Object v = attrs.getRaw(formals[i]);
            if (v == null) {
                if (defaults[i] == null) throw missing(formals[i]);
                v = defaults[i].executeLazy(frame);
            } else {
                matched++;
            }
            frame.setObject(formalSlots[i], v);
        }
        if (!ellipsis && matched < attrs.size()) throw unexpected(attrs);
    }

    private String fn() {
        return fnName == null ? "anonymous lambda" : "function '" + fnName + "'";
    }

    @TruffleBoundary
    private NixException missing(String name) {
        return new NixException(fn() + " called without required argument '" + name + "'", this);
    }

    @TruffleBoundary
    private NixException unexpected(NixAttrs attrs) {
        for (String k : attrs.keys()) {
            if (!Arrays.asList(formals).contains(k)) {
                return new NixException(fn() + " called with unexpected argument '" + k + "'", this);
            }
        }
        throw new IllegalStateException();
    }
}
