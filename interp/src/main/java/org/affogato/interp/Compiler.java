package org.affogato.interp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.affogato.interp.nodes.Node;
import org.affogato.interp.nodes.Nodes.*;
import org.affogato.interp.nodes.Root;
import org.affogato.interp.runtime.Coerce;
import org.affogato.interp.runtime.NixAttrs;
import org.affogato.interp.runtime.NixLambda;
import org.affogato.interp.runtime.NixList;
import org.affogato.interp.runtime.NixPath;
import org.affogato.interp.runtime.NixString;
import org.affogato.interp.runtime.Source;
import org.affogato.interp.runtime.SourceSection;
import org.affogato.interp.runtime.Thunk;
import org.affogato.parser.Ast.*;
import org.affogato.parser.ParseException;

/**
 * Turns the AST into interpreter nodes, resolving variables statically; a port of org.affogato.Translator.
 *
 * <p>Each lambda and each thunk gets its own frame whose first argument is the enclosing frame; {@code let},
 * {@code rec} and {@code with} allocate slots in the current frame.
 */
public final class Compiler {
    private static final class Frame {
        int size = Root.FIRST_SLOT;

        int add(String name) {
            return size++;
        }
    }

    private record Scope(Scope parent, Frame frame, Map<String, Integer> vars, int withSlot) {}

    private final Source source;
    private final String fileName;
    private final String baseDir;
    private final Map<String, Object> globals;
    private int hiddenCounter;

    private Compiler(Source source, String fileName, String baseDir, Map<String, Object> globals) {
        this.source = source;
        this.fileName = fileName;
        this.baseDir = baseDir;
        this.globals = globals;
    }

    /**
     * Translates a whole file. {@code scopeNames}, if any, are bound statically from the attribute set passed as
     * the root's second argument, as {@code scopedImport} requires.
     */
    public static Root translate(
            Source source,
            Expr expr,
            String fileName,
            String baseDir,
            Map<String, Object> globals,
            String[] scopeNames) {
        Compiler t = new Compiler(source, fileName, baseDir, globals);
        Frame frame = new Frame();
        Map<String, Integer> vars = new HashMap<>();
        int[] slots = new int[scopeNames.length];
        Node[] values = new Node[scopeNames.length];
        for (int i = 0; i < scopeNames.length; i++) {
            slots[i] = frame.add(scopeNames[i]);
            vars.put(scopeNames[i], slots[i]);
            values[i] = new ScopeArgNode(scopeNames[i]);
        }
        Node body = t.tr(expr, new Scope(null, frame, vars, -1), null);
        if (scopeNames.length > 0) body = new BindNode(slots, values, body);
        return new Root(frame.size, body, fileName, t.section(expr.pos()));
    }

    private SourceSection section(Pos pos) {
        int start = Math.min(pos.start(), source.getLength());
        int len = Math.max(0, Math.min(pos.end(), source.getLength()) - start);
        return source.createSection(start, len);
    }

    private Node tr(Expr e, Scope s) {
        return tr(e, s, null);
    }

    private Node tr(Expr e, Scope s, String name) {
        Node n = switch (e) {
            case IntLit i -> new ConstantNode(i.value());
            case FloatLit f -> new ConstantNode(f.value());
            case StrLit str -> new ConstantNode(new NixString(str.value()));
            case PathLit p -> new ConstantNode(new NixPath(resolvePath(p.raw(), false)));
            case Interp i -> interp(i, s);
            case SearchPath sp -> {
                Pos pos = sp.pos();
                yield tr(
                        new Call(
                                pos,
                                new Var(pos, "__findFile"),
                                List.of(new Var(pos, "__nixPath"), new StrLit(pos, sp.path()))),
                        s);
            }
            case Var v -> var(v, s);
            case Select sel ->
                new SelectNode(
                        tr(sel.target(), s),
                        keys(sel.path(), s),
                        sel.fallback() == null ? null : tr(sel.fallback(), s));
            case HasAttr h -> new HasAttrNode(tr(h.target(), s), keys(h.path(), s));
            case Attrs a -> attrs(a, s);
            case ListExpr l -> {
                if (l.items().isEmpty()) yield new ConstantNode(NixList.EMPTY);
                Node[] items = new Node[l.items().size()];
                for (int i = 0; i < items.length; i++) items[i] = lazy(l.items().get(i), s, null);
                yield new ListNode(items);
            }
            case Lambda l -> lambda(l, s, name);
            case Call c -> {
                Node[] args = new Node[c.args().size()];
                for (int i = 0; i < args.length; i++) args[i] = lazy(c.args().get(i), s, null);
                yield new CallNode(tr(c.fn(), s), args);
            }
            case Let l -> let(l, s);
            case With w -> {
                int slot = s.frame().add("<with>");
                Scope ws = new Scope(s, s.frame(), null, slot);
                yield new BindNode(new int[] {slot}, new Node[] {lazy(w.scope(), s, null)}, tr(w.body(), ws));
            }
            case Assert a ->
                new AssertNode(tr(a.cond(), s), tr(a.body(), s), text(a.cond().pos()));
            case If i -> new IfNode(tr(i.cond(), s), tr(i.then(), s), tr(i.otherwise(), s));
            case Not n2 -> new NotNode(tr(n2.operand(), s));
            case BinOp b ->
                switch (b.op()) {
                    case AND, OR, IMPL -> new LogicNode(b.op(), tr(b.left(), s), tr(b.right(), s));
                    default -> new BinaryNode(b.op(), tr(b.left(), s), tr(b.right(), s));
                };
            case CurPos c -> new ConstantNode(position(c.pos()));
        };
        return n.at(section(e.pos()));
    }

    private String text(Pos pos) {
        return source.getCharacters().subSequence(pos.start(), pos.end()).toString();
    }

    public NixAttrs position(Pos pos) {
        SourceSection sec = section(pos);
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("file", new NixString(fileName));
        m.put("line", (long) sec.getStartLine());
        m.put("column", (long) sec.getStartColumn());
        return NixAttrs.of(m);
    }

    /** Translates an expression in a lazy position: cheap expressions are evaluated directly, others suspended. */
    private Node lazy(Expr e, Scope s, String name) {
        return switch (e) {
            case IntLit i -> tr(e, s);
            case FloatLit f -> tr(e, s);
            case StrLit str -> tr(e, s);
            case PathLit p -> tr(e, s);
            case Var v -> isStatic(v.name(), s) ? tr(e, s) : thunk(e, s, name);
            case Lambda l -> tr(e, s, name);
            case ListExpr l when l.items().isEmpty() -> tr(e, s);
            default -> thunk(e, s, name);
        };
    }

    /** Like {@link #lazy}, for a binding whose sibling slots may not be initialised yet. */
    private Node lazyBinding(Expr e, Scope s, String name) {
        return e instanceof Var ? thunk(e, s, name) : lazy(e, s, name);
    }

    /** Measured on the top-level nixpkgs sweep: 4 and 8 are slower, 32 and beyond no faster. */
    private static final int IN_PLACE_MAX = 16;

    private Node thunk(Expr e, Scope s, String name) {
        int size = strictSize(e);
        if (size >= 0 && size <= IN_PLACE_MAX) return new InPlaceThunkNode(tr(e, s, name)).at(section(e.pos()));
        Frame frame = new Frame();
        Node body = tr(e, new Scope(s, frame, null, -1), name);
        Root root = new Root(frame.size, body, name == null ? "<thunk>" : name, section(e.pos()));
        return new ThunkNode(root).at(section(e.pos()));
    }

    /**
     * The nodes {@code e} evaluates when forced, or -1 when it needs frame slots of its own. Arguments, list items and
     * attribute values count as one each, since they are suspended on their own.
     */
    private static int strictSize(Expr e) {
        return switch (e) {
            case IntLit x -> 1;
            case FloatLit x -> 1;
            case StrLit x -> 1;
            case PathLit x -> 1;
            case SearchPath x -> 1;
            case CurPos x -> 1;
            case Var x -> 1;
            case Lambda x -> 1;
            case Select x -> dynamic(x.path()) ? -1 : sum(1, x.target(), x.fallback());
            case HasAttr x -> dynamic(x.path()) ? -1 : sum(1, x.target());
            case Call x -> sum(1 + x.args().size(), x.fn());
            case BinOp x -> sum(1, x.left(), x.right());
            case Not x -> sum(1, x.operand());
            case If x -> sum(1, x.cond(), x.then(), x.otherwise());
            case Assert x -> sum(1, x.cond(), x.body());
            case Interp x -> sum(1, x.parts().toArray(Expr[]::new));
            case ListExpr x -> 1 + x.items().size();
            case Attrs x -> {
                if (x.rec || !x.dynamic.isEmpty()) yield -1;
                for (AttrDef d : x.attrs.values()) if (d.source() != null) yield -1;
                yield 1 + x.attrs.size();
            }
            case Let x -> -1;
            case With x -> -1;
        };
    }

    private static boolean dynamic(List<AttrName> path) {
        for (AttrName a : path) if (a.dynamic() != null) return true;
        return false;
    }

    private static int sum(int n, Expr... parts) {
        for (Expr p : parts) {
            if (p == null) continue;
            int k = strictSize(p);
            if (k < 0) return -1;
            n += k;
        }
        return n;
    }

    private boolean isStatic(String name, Scope s) {
        for (Scope sc = s; sc != null; sc = sc.parent()) {
            if (sc.vars() != null && sc.vars().containsKey(name)) return true;
        }
        return globals.containsKey(name);
    }

    private Node var(Var v, Scope s) {
        String name = v.name();
        int depth = 0;
        Frame cur = s.frame();
        List<int[]> withs = new ArrayList<>();
        for (Scope sc = s; sc != null; sc = sc.parent()) {
            if (sc.frame() != cur) {
                depth++;
                cur = sc.frame();
            }
            if (sc.vars() != null) {
                Integer slot = sc.vars().get(name);
                if (slot != null) return new ReadVarNode(depth, slot);
            }
            if (sc.withSlot() >= 0) withs.add(new int[] {depth, sc.withSlot()});
        }
        Object g = globals.get(name);
        if (g != null) return g instanceof Thunk t ? new ForceNode(new ConstantNode(t)) : new ConstantNode(g);
        if (withs.isEmpty())
            throw new ParseException(
                    "undefined variable '" + name + "'", v.pos().start());
        int[] depths = new int[withs.size()];
        int[] slots = new int[withs.size()];
        for (int i = 0; i < depths.length; i++) {
            depths[i] = withs.get(i)[0];
            slots[i] = withs.get(i)[1];
        }
        return new WithVarNode(name, depths, slots);
    }

    private AttrKey[] keys(List<AttrName> path, Scope s) {
        AttrKey[] keys = new AttrKey[path.size()];
        for (int i = 0; i < keys.length; i++) {
            AttrName a = path.get(i);
            keys[i] = new AttrKey(a.name(), a.dynamic() == null ? null : tr(a.dynamic(), s));
        }
        return keys;
    }

    private String resolvePath(String raw, boolean keepTrailingSlash) {
        String abs;
        if (raw.startsWith("~/")) {
            abs = System.getenv("HOME") + raw.substring(1);
        } else if (raw.startsWith("/")) {
            abs = raw;
        } else {
            abs = baseDir + "/" + raw;
        }
        String c = Coerce.canonicalize(abs);
        return keepTrailingSlash && raw.endsWith("/") && !c.equals("/") ? c + "/" : c;
    }

    private Node interp(Interp i, Scope s) {
        Node[] parts = new Node[i.parts().size()];
        for (int k = 0; k < parts.length; k++) {
            Expr p = i.parts().get(k);
            if (i.isPath() && k == 0) {
                parts[k] = new ConstantNode(new NixPath(resolvePath(((PathLit) p).raw(), true)));
            } else {
                parts[k] = tr(p, s);
            }
        }
        return new InterpNode(parts, i.isPath());
    }

    private Node lambda(Lambda l, Scope s, String name) {
        Frame frame = new Frame();
        Map<String, Integer> vars = new HashMap<>();
        int argSlot = -1;
        if (l.arg() != null) {
            argSlot = frame.add(l.arg());
            vars.put(l.arg(), argSlot);
        }
        String[] formals = null;
        int[] formalSlots = null;
        boolean[] hasDefault = null;
        SourceSection[] formalPositions = null;
        List<Formal> fl = l.formals() == null ? List.of() : l.formals().list();
        if (l.formals() != null) {
            formals = new String[fl.size()];
            formalSlots = new int[fl.size()];
            hasDefault = new boolean[fl.size()];
            formalPositions = new SourceSection[fl.size()];
            for (int i = 0; i < formals.length; i++) {
                formals[i] = fl.get(i).name();
                formalPositions[i] = section(fl.get(i).pos());
                formalSlots[i] = frame.add(formals[i]);
                hasDefault[i] = fl.get(i).fallback() != null;
                vars.put(formals[i], formalSlots[i]);
            }
        }
        Scope ls = new Scope(s, frame, vars, -1);
        Node[] defaults = new Node[fl.size()];
        for (int i = 0; i < defaults.length; i++) {
            Expr d = fl.get(i).fallback();
            defaults[i] = d == null ? null : lazyBinding(d, ls, null);
        }
        Node body = tr(l.body(), ls);
        boolean ellipsis = l.formals() != null && l.formals().ellipsis();
        LambdaBodyNode node = new LambdaBodyNode(name, argSlot, formals, formalSlots, defaults, ellipsis, body);
        node.at(section(l.pos()));
        Root root = new Root(frame.size, node, name == null ? "<lambda>" : name, section(l.pos()));
        return new LambdaNode(
                root,
                new NixLambda.LambdaInfo(
                        name, l.arg(), formals, hasDefault, formalPositions, ellipsis, section(l.pos())));
    }

    /** Assigns hidden variables to the {@code inherit (from)} sources of a binding group. */
    private Map<InheritSource, String> inheritSources(Attrs a) {
        Map<InheritSource, String> sources = new IdentityHashMap<>();
        for (AttrDef d : a.attrs.values()) {
            if (d.kind() == AttrKind.INHERITED_FROM && !sources.containsKey(d.source())) {
                sources.put(d.source(), "\0inherit" + hiddenCounter++);
            }
        }
        return sources;
    }

    private Expr inheritedFrom(String hidden, String attr, Pos pos) {
        return new Select(pos, new Var(pos, hidden), List.of(new AttrName(attr, null)), null);
    }

    /**
     * Allocates slots for a recursive binding group ({@code rec} or {@code let}) and returns the slot initialisers.
     */
    private Scope recScope(Attrs a, Scope s, List<Integer> slots, List<Node> values) {
        Map<InheritSource, String> sources = inheritSources(a);
        Map<String, Integer> vars = new HashMap<>();
        for (String k : a.attrs.keySet()) vars.put(k, s.frame().add(k));
        for (String h : sources.values()) vars.put(h, s.frame().add(h));
        Scope rs = new Scope(s, s.frame(), vars, -1);
        for (var e : sources.entrySet()) {
            slots.add(vars.get(e.getValue()));
            values.add(lazyBinding(e.getKey().from, rs, null));
        }
        for (var e : a.attrs.entrySet()) {
            AttrDef d = e.getValue();
            slots.add(vars.get(e.getKey()));
            values.add(
                    switch (d.kind()) {
                        case PLAIN -> lazyBinding(d.value(), rs, e.getKey());
                        case INHERITED -> lazy(d.value(), s, e.getKey());
                        case INHERITED_FROM ->
                            thunk(inheritedFrom(sources.get(d.source()), e.getKey(), d.pos()), rs, e.getKey());
                    });
        }
        return rs;
    }

    private static int[] ints(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private Node let(Let l, Scope s) {
        List<Integer> slots = new ArrayList<>();
        List<Node> values = new ArrayList<>();
        Scope rs = recScope(l.binds(), s, slots, values);
        return new BindNode(ints(slots), values.toArray(new Node[0]), tr(l.body(), rs));
    }

    private Node attrs(Attrs a, Scope s) {
        TreeMap<String, AttrDef> sorted = new TreeMap<>(a.attrs);
        String[] keys = sorted.keySet().toArray(new String[0]);
        Node[] values = new Node[keys.length];
        SourceSection[] positions = new SourceSection[keys.length];
        List<Integer> slots = new ArrayList<>();
        List<Node> pre = new ArrayList<>();
        Scope inner;
        int overridesIndex = -1;
        int[] recSlots = null;
        if (a.rec) {
            inner = recScope(a, s, slots, pre);
            recSlots = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                values[i] = var(new Var(a.pos(), keys[i]), inner);
                recSlots[i] = inner.vars().get(keys[i]);
                if (keys[i].equals("__overrides")) overridesIndex = i;
            }
        } else {
            Map<InheritSource, String> sources = inheritSources(a);
            inner = s;
            if (!sources.isEmpty()) {
                Map<String, Integer> vars = new HashMap<>();
                for (var e : sources.entrySet()) {
                    int slot = s.frame().add(e.getValue());
                    vars.put(e.getValue(), slot);
                    slots.add(slot);
                    pre.add(lazy(e.getKey().from, s, null));
                }
                inner = new Scope(s, s.frame(), vars, -1);
            }
            for (int i = 0; i < keys.length; i++) {
                AttrDef d = sorted.get(keys[i]);
                values[i] = switch (d.kind()) {
                    case PLAIN, INHERITED -> lazy(d.value(), s, keys[i]);
                    case INHERITED_FROM ->
                        thunk(inheritedFrom(sources.get(d.source()), keys[i], d.pos()), inner, keys[i]);
                };
            }
        }
        for (int i = 0; i < keys.length; i++)
            positions[i] = section(sorted.get(keys[i]).pos());
        int nd = a.dynamic.size();
        Node[] dynNames = new Node[nd];
        Node[] dynValues = new Node[nd];
        SourceSection[] dynPositions = new SourceSection[nd];
        for (int i = 0; i < nd; i++) {
            DynAttr d = a.dynamic.get(i);
            dynNames[i] = tr(d.name(), inner);
            dynValues[i] = lazy(d.value(), inner, null);
            dynPositions[i] = section(d.pos());
        }
        Node node = new AttrsNode(keys, values, positions, dynNames, dynValues, dynPositions, overridesIndex, recSlots);
        return slots.isEmpty() ? node : new BindNode(ints(slots), pre.toArray(new Node[0]), node.at(section(a.pos())));
    }
}
