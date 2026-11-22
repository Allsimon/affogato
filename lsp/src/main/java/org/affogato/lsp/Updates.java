package org.affogato.lsp;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.affogato.lint.Edit;

/** Updates packages to their latest version, kept outside this module as it fetches and builds. */
public interface Updates {
    /** The text of a file before and after an update. */
    record Change(String before, String after) {}

    /**
     * The edits to the text that move a package to {@code version}, the dependency hashes they leave as they were,
     * and the other files it changes, such as lock files, by path.
     */
    record Update(String version, List<Edit> edits, List<String> notUpdated, Map<String, Change> files) {
        public Update(String version, List<Edit> edits, List<String> notUpdated) {
            this(version, edits, notUpdated, Map.of());
        }
    }

    /** The files an update of the packages of a workspace changes, by path, and a line for each package it changed. */
    record WorkspaceUpdate(Map<String, Change> files, List<String> summary) {}

    /** Told each step as it starts, with the part of the work done before it, from 0 to 1. */
    interface Progress {
        Progress NONE = (step, done) -> {};

        void report(String step, double done);
    }

    /**
     * Updates, on a thread of its own, the package at {@code index} in {@link org.affogato.lint.Packages#find} of
     * {@code text}, the content of {@code file}, reporting its steps to {@code progress}; then calls {@code done} with
     * the update, or null when the package is up to date, or {@code failed} with what went wrong. The returned
     * runnable cancels it, stopping what it runs.
     */
    Runnable update(
            String file, String text, int index, Progress progress, Consumer<Update> done, Consumer<String> failed);

    /**
     * As {@link #update}, but refreshes the hashes of the package at the version it has, or at the commit it is pinned
     * to; the update has no edits when they are right already.
     */
    Runnable refresh(
            String file, String text, int index, Progress progress, Consumer<Update> done, Consumer<String> failed);

    /** As {@link #update}, but only tells the newest version of the package, or null when it has it; cached a while. */
    Runnable newer(String file, String text, int index, Consumer<String> done, Consumer<String> failed);

    /**
     * Updates the packages of the {@code .nix} files below {@code roots}, as {@code affogato update} does, without
     * writing them.
     */
    Runnable updateWorkspace(
            List<String> roots, Progress progress, Consumer<WorkspaceUpdate> done, Consumer<String> failed);
}
