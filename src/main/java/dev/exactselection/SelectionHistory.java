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

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public String undoLabel() { return canUndo() ? undo.peek().label() : ""; }
    public String redoLabel() { return canRedo() ? redo.peek().label() : ""; }

    public void execute(Action action) throws Exception {
        action.redo();
        recordApplied(action);
    }

    public void recordApplied(Action action) {
        undo.push(action);
        redo.clear();
        while (undo.size() > LIMIT) undo.removeLast();
    }

    public void undo() throws Exception {
        if (!canUndo()) return;
        Action action = undo.peek();
        action.undo();
        undo.pop();
        redo.push(action);
    }

    public void redo() throws Exception {
        if (!canRedo()) return;
        Action action = redo.peek();
        action.redo();
        redo.pop();
        undo.push(action);
    }

    public void clear() { undo.clear(); redo.clear(); }
}
