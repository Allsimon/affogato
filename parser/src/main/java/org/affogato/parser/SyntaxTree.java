package org.affogato.parser;

import java.util.ArrayList;
import java.util.List;
import org.affogato.parser.Lexeme.Kind;

/** The tree {@link TreeParser} builds over {@link Tokenizer}'s lexemes, whitespace and comments left out. */
public final class SyntaxTree {
    /** A node, a token, or an error, by which of {@code kind} and {@code token} it has; the root has neither. */
    public static final class Node {
        public final NodeKind kind;
        public final Kind token;
        public final boolean error;
        final List<Node> children = new ArrayList<>();
        Node parent;
        int start;
        int end;

        Node(NodeKind kind, Kind token, boolean error) {
            this.kind = kind;
            this.token = token;
            this.error = error;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public Node parent() {
            return parent;
        }

        public List<Node> children() {
            return List.copyOf(children);
        }

        @Override
        public String toString() {
            return (kind != null ? kind.name() : token != null ? token.name() : error ? "ERROR" : "ROOT") + "@" + start
                    + ".." + end;
        }
    }

    private final String src;
    private final Node root;
    private final List<Lexeme> comments;

    private SyntaxTree(String src, Node root, List<Lexeme> comments) {
        this.src = src;
        this.root = root;
        this.comments = comments;
    }

    public static SyntaxTree parse(String src) {
        Builder builder = new Builder(src);
        TreeParser.parse(builder);
        List<Lexeme> comments = Tokenizer.tokenize(src).stream()
                .filter(l -> l.kind() == Kind.COMMENT)
                .toList();
        return new SyntaxTree(src, builder.finish(), comments);
    }

    private String commentBefore(Node node) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            Lexeme comment = comments.get(i);
            if (comment.end() <= node.start) {
                return src.substring(comment.end(), node.start).isBlank()
                        ? src.substring(comment.start(), comment.end())
                        : null;
            }
        }
        return null;
    }

    public Node root() {
        return root;
    }

    public String text(Node node) {
        return src.substring(node.start, node.end);
    }

    /** The innermost node or token whose range holds {@code offset}, else the one ending there, as at a caret. */
    public Node at(int offset) {
        Node node = root;
        while (true) {
            Node next = null;
            for (Node child : node.children) {
                if (child.start <= offset && offset < child.end) {
                    next = child;
                    break;
                }
                if (child.end == offset && child.end > child.start) next = child;
            }
            if (next == null) return node;
            node = next;
        }
    }

    public SyntaxView<Node> view() {
        return new SyntaxView<>() {
            @Override
            public NodeKind kind(Node node) {
                return node.kind;
            }

            @Override
            public Kind token(Node node) {
                return node.token;
            }

            @Override
            public Node parent(Node node) {
                return node.parent;
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public String text(Node node) {
                return SyntaxTree.this.text(node);
            }

            @Override
            public String commentBefore(Node node) {
                return SyntaxTree.this.commentBefore(node);
            }
        };
    }

    /** Collects the events of {@link TreeParser}, then nests them, as an IDE's builder would. */
    private static final class Builder implements TreeBuilder {
        private sealed interface Event {}

        private static final class Start implements Event {
            NodeKind kind;
            boolean error;
            boolean dropped;
            boolean done;
        }

        private record Finish() implements Event {}

        private record Leaf(Lexeme lexeme) implements Event {}

        private final List<Lexeme> tokens;
        private final List<Event> events = new ArrayList<>();
        private final int length;
        private int index;

        Builder(String src) {
            this.length = src.length();
            this.tokens = Tokenizer.tokenize(src).stream()
                    .filter(l -> l.kind() != Kind.WHITESPACE && l.kind() != Kind.COMMENT)
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
            Start start = new Start();
            start.error = true;
            start.done = true;
            events.add(start);
            events.add(new Finish());
        }

        private final class Mark implements Marker {
            private final Start start;

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
                start.kind = kind;
                start.done = true;
                events.add(new Finish());
            }

            @Override
            public void error(String message) {
                start.error = true;
                start.done = true;
                events.add(new Finish());
            }

            @Override
            public void drop() {
                start.dropped = true;
            }
        }

        Node finish() {
            while (index < tokens.size()) advance();
            Node root = new Node(null, null, false);
            root.end = length;
            Node current = root;
            int at = 0;
            for (Event e : events) {
                switch (e) {
                    case Start s when s.dropped || !s.done -> {}
                    case Start s -> {
                        Node node = new Node(s.kind, null, s.error);
                        node.parent = current;
                        node.start = at;
                        current.children.add(node);
                        current = node;
                    }
                    case Finish f -> {
                        current.end = Math.max(current.start, at);
                        if (!current.children.isEmpty()) {
                            current.start = current.children.getFirst().start;
                            current.end = current.children.getLast().end;
                        }
                        current = current.parent;
                    }
                    case Leaf l -> {
                        Node leaf = new Node(null, l.lexeme().kind(), false);
                        leaf.parent = current;
                        leaf.start = l.lexeme().start();
                        leaf.end = l.lexeme().end();
                        at = leaf.end;
                        current.children.add(leaf);
                    }
                }
            }
            return root;
        }
    }
}
