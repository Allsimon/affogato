package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

public abstract sealed class NixNode extends Node
        permits AssertNode,
                AttrKey,
                AttrsNode,
                BinaryNode,
                BindNode,
                CallNode,
                ConstantNode,
                ForceNode,
                HasAttrNode,
                IfNode,
                InPlaceThunkNode,
                InterpNode,
                LambdaBodyNode,
                LambdaNode,
                ListNode,
                LogicNode,
                NotNode,
                ReadVarNode,
                ScopeArgNode,
                SelectNode,
                ThunkNode,
                WithVarNode {
    private SourceSection section;

    /** Evaluates to weak head normal form: never returns a {@link org.affogato.runtime.Thunk}. */
    public abstract Object execute(VirtualFrame frame);

    /** Evaluates for storage in a lazy position; may return a thunk instead of a value. */
    public Object executeLazy(VirtualFrame frame) {
        return execute(frame);
    }

    public final NixNode at(SourceSection s) {
        this.section = s;
        return this;
    }

    @Override
    public SourceSection getSourceSection() {
        return section;
    }
}
