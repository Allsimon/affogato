package org.affogato.interp.runtime;

public final class NixList {
    public static final NixList EMPTY = new NixList(new Object[0]);

    public final Object[] items;

    public NixList(Object[] items) {
        this.items = items;
    }

    public int size() {
        return items.length;
    }

    public Object get(int i) {
        return Thunk.force(items[i]);
    }
}
