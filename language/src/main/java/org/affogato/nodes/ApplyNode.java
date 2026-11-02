package org.affogato.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import org.affogato.runtime.Apply;
import org.affogato.runtime.NixLambda;

public abstract sealed class ApplyNode extends Node permits ApplyNodeGen {
    public abstract Object execute(Object fn, Object arg);

    @Specialization(guards = "fn.target == cachedTarget", limit = "3")
    static Object direct(
            NixLambda fn,
            Object arg,
            @Cached("fn.target") RootCallTarget cachedTarget,
            @Cached("create(cachedTarget)") DirectCallNode call) {
        return call.call(fn.closure, arg);
    }

    @Specialization(replaces = "direct")
    static Object indirect(NixLambda fn, Object arg, @Cached IndirectCallNode call) {
        return call.call(fn.target, fn.closure, arg);
    }

    @Fallback
    static Object other(Object fn, Object arg) {
        return Apply.apply(fn, arg);
    }
}
