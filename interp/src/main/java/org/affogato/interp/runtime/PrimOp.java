package org.affogato.interp.runtime;

public final class PrimOp {
    @FunctionalInterface
    public interface Impl {
        Object call(Object[] args);
    }

    public final String name;
    public final int arity;
    public final Impl impl;

    public PrimOp(String name, int arity, Impl impl) {
        this.name = name;
        this.arity = arity;
        this.impl = impl;
    }

    /** A primop applied to fewer arguments than its arity. */
    public record Partial(PrimOp op, Object[] args) {}
}
