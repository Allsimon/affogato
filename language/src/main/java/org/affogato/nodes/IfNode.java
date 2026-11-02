package org.affogato.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import org.affogato.runtime.Types;

public final class IfNode extends NixNode {
    @Child
    private NixNode cond;

    @Child
    private NixNode then;

    @Child
    private NixNode otherwise;

    private final CountingConditionProfile profile = CountingConditionProfile.create();

    public IfNode(NixNode cond, NixNode then, NixNode otherwise) {
        this.cond = cond;
        this.then = then;
        this.otherwise = otherwise;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return profile.profile(Types.bool(cond.execute(frame))) ? then.execute(frame) : otherwise.execute(frame);
    }
}
