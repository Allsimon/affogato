package org.affogato.nodes;

final class Frames {
    private Frames() {}

    static Object[] up(Object[] frame, int depth) {
        Object[] f = frame;
        for (int i = 0; i < depth; i++) f = (Object[]) f[0];
        return f;
    }
}
