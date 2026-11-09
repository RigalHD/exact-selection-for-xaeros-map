package dev.exactselection;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SelectionHistory {
    public interface Action {
        void undo() throws Exception;

        void redo() throws Exception;

        String label();
    }

    private static final int LIMIT = 100;
    private final Deque<Action> undo = new ArrayDeque<>();
    private final Deque<Action> redo = new ArrayDeque<>();

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public String undoLabel() {
        return canUndo() ? undo.peek().label() : "";
    }

    public String redoLabel() {
        return canRedo() ? redo.peek().label() : "";
    }

    public void recordApplied(Action action) {
        undo.push(action);
        redo.clear();
        while (undo.size() > LIMIT) undo.removeLast();
    }

    // A step that fails is dropped so it cannot block older ones; each action rolls back its own partial writes.
    public void undo() throws Exception {
        if (!canUndo()) return;
        Action action = undo.pop();
        action.undo();
        redo.push(action);
    }

    public void redo() throws Exception {
        if (!canRedo()) return;
        Action action = redo.pop();
        action.redo();
        undo.push(action);
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}
