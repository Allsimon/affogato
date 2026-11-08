package org.affogato.interp.runtime;

public record NixPath(String path) {
    @Override
    public String toString() {
        return path;
    }
}
