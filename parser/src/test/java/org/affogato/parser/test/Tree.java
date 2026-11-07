package org.affogato.parser.test;

import java.util.ArrayList;
import java.util.List;
import org.affogato.parser.Lexeme;
import org.affogato.parser.Lexeme.Kind;
import org.affogato.parser.NodeKind;
import org.affogato.parser.Tokenizer;
import org.affogato.parser.TreeBuilder;
import org.affogato.parser.TreeParser;

/** Runs {@link TreeParser} and prints its tree, one node or token per line, whitespace and comments left out. */
final class Tree implements TreeBuilder {
    private sealed interface Event {}

    private static final class Start implements Event {
        String name;
        boolean dropped;
    }

    private record Finish() implements Event {}

    private record Leaf(Lexeme lexeme) implements Event {}

    private record Error(String message) implements Event {}

    private final String src;
    private final List<Lexeme> tokens;
    private final List<Event> events = new ArrayList<>();
    private int index;

    private Tree(String src) {
        this.src = src;
        this.tokens = Tokenizer.tokenize(src).stream()
                .filter(l -> l.kind() != Kind.WHITESPACE && l.kind() != Kind.COMMENT)
                .toList();
    }

    static String parse(String src) {
        Tree tree = new Tree(src);
        Marker root = tree.mark();
        TreeParser.parse(tree);
        root.done(null);
        return tree.print();
    }

    /** The error messages, in source order. */
    static List<String> errors(String src) {
        return parse(src)
                .lines()
                .map(String::strip)
                .filter(l -> l.startsWith("ERROR"))
                .toList();
    }

    @Override
    public Kind token() {
        return lookAhead(0);
    }

    @Override
    public Kind lookAhead(int steps) {
        return index + steps < tokens.size() ? tokens.get(index + steps).kind() : null;
    }

    @Override
    public void advance() {
        if (index >= tokens.size()) throw new IllegalStateException("advance at the end");
        events.add(new Leaf(tokens.get(index++)));
    }

    @Override
    public Marker mark() {
        Start start = new Start();
        events.add(start);
        return new Mark(start);
    }

    @Override
    public void error(String message) {
        events.add(new Error(message));
    }

    private final class Mark implements Marker {
        private final Start start;
        private boolean closed;

        Mark(Start start) {
            this.start = start;
        }

        @Override
        public Marker precede() {
            Start before = new Start();
            events.add(events.indexOf(start), before);
            return new Mark(before);
        }

        @Override
        public void done(NodeKind kind) {
            close(kind == null ? "ROOT" : kind.name());
        }

        @Override
        public void error(String message) {
            close("ERROR " + message);
        }

        @Override
        public void drop() {
            if (closed) throw new IllegalStateException("closed twice");
            closed = true;
            start.dropped = true;
        }

        private void close(String name) {
            if (closed) throw new IllegalStateException("closed twice");
            closed = true;
            start.name = name;
            events.add(new Finish());
        }
    }

    private String print() {
        StringBuilder out = new StringBuilder();
        List<Start> open = new ArrayList<>();
        for (Event e : events) {
            switch (e) {
                case Start s when s.dropped -> {}
                case Start s -> {
                    if (s.name == null) throw new IllegalStateException("a marker left open");
                    out.append("  ".repeat(open.size())).append(s.name).append('\n');
                    open.add(s);
                }
                case Finish f -> open.removeLast();
                case Leaf l ->
                    out.append("  ".repeat(open.size()))
                            .append(l.lexeme().kind())
                            .append(' ')
                            .append(src, l.lexeme().start(), l.lexeme().end())
                            .append('\n');
                case Error r ->
                    out.append("  ".repeat(open.size()))
                            .append("ERROR ")
                            .append(r.message())
                            .append('\n');
            }
        }
        if (index != tokens.size()) throw new IllegalStateException("tokens left unconsumed");
        return out.toString();
    }
}
