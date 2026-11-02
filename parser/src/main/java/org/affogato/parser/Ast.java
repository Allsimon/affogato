package org.affogato.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class Ast {
    private Ast() {}

    public record Pos(int start, int end) {}

    public sealed interface Expr
            permits IntLit,
                    FloatLit,
                    StrLit,
                    PathLit,
                    Interp,
                    SearchPath,
                    Var,
                    Select,
                    HasAttr,
                    Attrs,
                    ListExpr,
                    Lambda,
                    Call,
                    Let,
                    With,
                    Assert,
                    If,
                    BinOp,
                    Not,
                    CurPos {
        Pos pos();
    }

    public record IntLit(Pos pos, long value) implements Expr {}

    public record FloatLit(Pos pos, double value) implements Expr {}

    public record StrLit(Pos pos, String value) implements Expr {}

    /** An unresolved path literal as written; {@code ~/} and relative paths are resolved by the translator. */
    public record PathLit(Pos pos, String raw) implements Expr {}

    /** String or path interpolation. For paths, the first part is a {@link PathLit}. */
    public record Interp(Pos pos, boolean isPath, List<Expr> parts) implements Expr {}

    public record SearchPath(Pos pos, String path) implements Expr {}

    public record Var(Pos pos, String name) implements Expr {}

    /** Exactly one of {@code name} and {@code dynamic} is set. */
    public record AttrName(String name, Expr dynamic) {
        @Override
        public String toString() {
            return name != null ? name : "\"${...}\"";
        }
    }

    public record Select(Pos pos, Expr target, List<AttrName> path, Expr fallback) implements Expr {}

    public record HasAttr(Pos pos, Expr target, List<AttrName> path) implements Expr {}

    public enum AttrKind {
        PLAIN,
        INHERITED,
        INHERITED_FROM
    }

    /** The expression of an {@code inherit (from)} clause, shared by all attributes it introduces. */
    public static final class InheritSource {
        public final Expr from;

        public InheritSource(Expr from) {
            this.from = from;
        }
    }

    /** For INHERITED, {@code value} is the {@link Var} to look up in the enclosing scope. */
    public record AttrDef(Expr value, Pos pos, AttrKind kind, InheritSource source) {}

    public record DynAttr(Expr name, Expr value, Pos pos) {}

    public static final class Attrs implements Expr {
        public Pos pos;
        public boolean rec;
        public final LinkedHashMap<String, AttrDef> attrs = new LinkedHashMap<>();
        public final List<DynAttr> dynamic = new ArrayList<>();

        public Attrs(Pos pos, boolean rec) {
            this.pos = pos;
            this.rec = rec;
        }

        @Override
        public Pos pos() {
            return pos;
        }
    }

    public record ListExpr(Pos pos, List<Expr> items) implements Expr {}

    public record Formal(Pos pos, String name, Expr fallback) {}

    public record Formals(List<Formal> list, boolean ellipsis) {}

    /** {@code arg} is the plain or {@code @}-bound argument name; {@code formals} is null for {@code x: body}. */
    public record Lambda(Pos pos, String arg, Formals formals, Expr body) implements Expr {}

    public record Call(Pos pos, Expr fn, List<Expr> args) implements Expr {}

    public record Let(Pos pos, Attrs binds, Expr body) implements Expr {}

    public record With(Pos pos, Expr scope, Expr body) implements Expr {}

    public record Assert(Pos pos, Expr cond, Expr body) implements Expr {}

    public record If(Pos pos, Expr cond, Expr then, Expr otherwise) implements Expr {}

    public enum Op {
        EQ,
        NEQ,
        AND,
        OR,
        IMPL,
        UPDATE,
        CONCAT,
        ADD,
        SUB,
        MUL,
        DIV,
        LT,
        LE,
        GT,
        GE
    }

    public record BinOp(Pos pos, Op op, Expr left, Expr right) implements Expr {}

    public record Not(Pos pos, Expr operand) implements Expr {}

    public record CurPos(Pos pos) implements Expr {}
}
