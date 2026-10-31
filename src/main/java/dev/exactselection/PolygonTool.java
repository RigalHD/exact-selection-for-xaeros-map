package dev.exactselection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import static dev.exactselection.ExactSelectionController.ACCENT;

/** The Polygon tool: the contour being drawn and vertex edits of contours and finished polygons. */
final class PolygonTool extends UiPart {
    boolean mode;
    final List<GridPoint> draft = new ArrayList<>();
    GridPoint cursor;
    SelectionState.Snapshot stateBefore;
    final Deque<List<GridPoint>> draftUndo = new ArrayDeque<>();
    final Deque<List<GridPoint>> draftRedo = new ArrayDeque<>();

    PolygonTool(ExactSelectionController ui) { super(ui); }

    void togglePolygonMode() {
        if (!ui.enabled() || ui.holding()) return;
        if (mode) {
            discardDraft();
            mode = false;
            ui.say("polygon_cancelled");
        } else {
            if (ui.state.phase() == SelectionState.Phase.PREVIEW) {
                if (!(ui.gesture instanceof MapGesture.Corners corners && corners.cancel(ui.gestureHost))) ui.state.reset();
                ui.syncFields();
            }
            mode = true;
            ui.rulerTool.setRulerMode(false);
            clearDraft();
            stateBefore = ui.state.snapshot();
            ui.say("polygon_controls");
        }
        ui.updateSelection();
    }

    void clearDraft() {
        draft.clear();
        cursor = null;
        stateBefore = null;
        draftUndo.clear(); draftRedo.clear();
    }

    /** Drops the contour being drawn as one undo step, so a stray Esc cannot lose a long contour. */
    void discardDraft() {
        if (!draft.isEmpty()) {
            List<GridPoint> saved = List.copyOf(draft);
            Deque<List<GridPoint>> steps = new ArrayDeque<>(draftUndo);
            SelectionState.Snapshot before = stateBefore;
            ui.history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() {
                    ui.rulerTool.setRulerMode(false);
                    mode = true;
                    clearDraft();
                    draft.addAll(saved);
                    draftUndo.addAll(steps);
                    stateBefore = before;
                }
                @Override public void redo() { mode = false; clearDraft(); }
                @Override public String label() { return ui.tr("history_contour_cancel").getString(); }
            });
        }
        clearDraft();
    }

    GridPoint polygonPoint(MapBridge.Frame frame, double x, double y, GridPoint anchor) {
        long wx = Math.round(frame.worldX(x)), wz = Math.round(frame.worldZ(y));
        if (ui.shiftDown && anchor != null) {
            if (Math.abs(wx - anchor.x()) >= Math.abs(wz - anchor.z())) wz = anchor.z();
            else wx = anchor.x();
        } else {
            GridPoint stuck = ui.zoneSnap.point(frame, x, y);
            if (stuck != null) return stuck;
        }
        return new GridPoint(wx, wz);
    }

    int hitPolygonEdge(MapBridge.Frame frame, List<GridPoint> vertices, double x, double y) {
        return hitPolygonEdge(frame, vertices, x, y, true);
    }

    /** The side under the pointer; an open contour has no side from its last vertex back to the first. */
    int hitPolygonEdge(MapBridge.Frame frame, List<GridPoint> vertices, double x, double y, boolean closed) {
        if (hitVertex(frame, vertices, x, y) >= 0) return -1;
        int nearest = -1;
        double distance = 64;
        for (int i = 0; i < (closed ? vertices.size() : vertices.size() - 1); i++) {
            GridPoint a = vertices.get(i), b = vertices.get((i + 1) % vertices.size());
            double ax = frame.screenX(a.x()), ay = frame.screenY(a.z());
            double dx = frame.screenX(b.x()) - ax, dy = frame.screenY(b.z()) - ay;
            double length = dx * dx + dy * dy;
            if (length == 0) continue;
            double t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length));
            double ex = x - ax - t * dx, ey = y - ay - t * dy;
            double candidate = ex * ex + ey * ey;
            if (candidate <= distance) { nearest = i; distance = candidate; }
        }
        return nearest;
    }

    /** Adds a contour vertex on side {@code edge}, as a double click does on a finished shape. */
    void insertDraftPoint(MapBridge.Frame frame, int edge, double x, double y) {
        if (draft.size() >= PolygonGeometry.MAX_VERTICES) { ui.say("polygon_too_many_points"); return; }
        GridPoint point;
        try { point = onSide(frame, draft.get(edge), draft.get(edge + 1), x, y); }
        catch (IllegalArgumentException e) { ui.say("invalid_coordinates"); return; }
        if (draft.contains(point)) { ui.say("polygon_duplicate_point"); return; }
        recordDraftChange(List.copyOf(draft));
        draft.add(edge + 1, point);
        ui.updateSelection();
        ui.say("polygon_draft_vertex_inserted");
    }

    /**
     * The block on side a–b nearest the pointer. The hit tolerance is in screen pixels, which can span many blocks
     * when zoomed out; placing the vertex under the cursor would bend the side.
     */
    static GridPoint onSide(MapBridge.Frame frame, GridPoint a, GridPoint b, double x, double y) {
        double ex = b.x() - a.x(), ez = b.z() - a.z();
        double t = ((frame.worldX(x) - a.x()) * ex + (frame.worldZ(y) - a.z()) * ez) / (ex * ex + ez * ez);
        t = Math.max(0, Math.min(1, t));
        return new GridPoint(Math.round(a.x() + t * ex), Math.round(a.z() + t * ez));
    }

    void insertPolygonPoint(MapBridge.Frame frame, PolygonGeometry polygon, int edge, double x, double y) {
        if (polygon.vertices().size() >= PolygonGeometry.MAX_VERTICES) {
            ui.say("polygon_too_many_points"); return;
        }
        try {
            List<GridPoint> points = new ArrayList<>(polygon.vertices());
            // Inserted on the side itself; Ctrl/Cmd-drag moves it afterwards.
            GridPoint point = onSide(frame, points.get(edge), points.get((edge + 1) % points.size()), x, y);
            if (points.contains(point)) { ui.say("polygon_duplicate_point"); return; }
            points.add(edge + 1, point);
            PolygonGeometry next = new PolygonGeometry(points);
            next.triangulate();
            SelectionState.Snapshot before = ui.state.snapshot();
            ui.state.setGeometry(next);
            ui.commitSelection(before, ui.state.snapshot(), "vertex");
            ui.say("polygon_vertex_inserted");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ui.say("polygon_invalid");
        }
    }

    void addPolygonPoint(MapBridge.Frame frame, double mouseX, double mouseY) {
        if (draft.size() >= 3) {
            GridPoint first = draft.get(0);
            double dx = frame.screenX(first.x()) - mouseX, dy = frame.screenY(first.z()) - mouseY;
            if (dx * dx + dy * dy <= 64.0) { finishPolygon(); return; }
        }
        GridPoint point;
        try { point = polygonPoint(frame, mouseX, mouseY, draft.isEmpty() ? null : draft.getLast()); }
        catch (IllegalArgumentException e) { ui.say("invalid_coordinates"); return; }
        if (draft.contains(point)) { ui.say("polygon_duplicate_point"); return; }
        if (draft.size() >= PolygonGeometry.MAX_VERTICES) {
            ui.say("polygon_too_many_points"); return;
        }
        if (draft.isEmpty()) stateBefore = ui.state.snapshot();
        recordDraftChange(List.copyOf(draft));
        draft.add(point);
        ui.updateSelection();
        if (draft.size() == 1) ui.say("polygon_draft_controls");
        else ui.say("polygon_point_count", draft.size());
    }

    void finishPolygon() {
        if (draft.size() < 3) { ui.say("polygon_minimum_points"); return; }
        try {
            PolygonGeometry polygon = new PolygonGeometry(draft);
            SelectionState.Snapshot before = stateBefore == null ? ui.state.snapshot() : stateBefore;
            // A new contour is a new selection: it no longer edits the area that was loaded.
            ui.state.load(polygon, null);
            ui.gesture = null;
            clearDraft();
            ui.commitSelection(before, ui.state.snapshot(), "polygon");
            ui.say("polygon_created");
        } catch (IllegalArgumentException e) {
            ui.say("polygon_invalid");
        }
    }

    void recordDraftChange(List<GridPoint> before) {
        draftUndo.push(before);
        draftRedo.clear();
        while (draftUndo.size() > 100) draftUndo.removeLast();
    }

    void removeLastPolygonPoint() {
        if (draft.isEmpty() || ui.holding()) return;
        recordDraftChange(List.copyOf(draft));
        draft.remove(draft.size() - 1);
        ui.say("polygon_point_removed");
    }

    /** One step back in the contour; also true while an undone contour waits for redo, which blocks the stored history. */
    boolean undoDraft() {
        if (draftUndo.isEmpty()) return draft.isEmpty() && !draftRedo.isEmpty();
        stepDraft(draftUndo, draftRedo, "history_undone");
        return true;
    }

    boolean redoDraft() {
        if (draftRedo.isEmpty()) return false;
        stepDraft(draftRedo, draftUndo, "history_redone");
        return true;
    }

    private void stepDraft(Deque<List<GridPoint>> from, Deque<List<GridPoint>> to, String key) {
        to.push(List.copyOf(draft));
        draft.clear(); draft.addAll(from.pop());
        ui.say(key);
        ui.updateSelection();
    }

    int hitVertex(MapBridge.Frame frame, List<GridPoint> vertices, double x, double y) {
        int nearest = -1;
        double distance = 64;
        for (int i = 0; i < vertices.size(); i++) {
            GridPoint point = vertices.get(i);
            double dx = frame.screenX(point.x()) - x, dy = frame.screenY(point.z()) - y;
            double candidate = dx * dx + dy * dy;
            if (candidate <= distance) { nearest = i; distance = candidate; }
        }
        return nearest;
    }

    void deleteVertex(MapBridge.Frame frame, double x, double y) {
        if (mode && !draft.isEmpty()) {
            int index = hitVertex(frame, draft, x, y);
            if (index < 0) return;
            recordDraftChange(List.copyOf(draft)); draft.remove(index); cursor = null;
            ui.say("polygon_vertex_deleted"); ui.updateSelection();
            return;
        }
        if (ui.state.phase() != SelectionState.Phase.SELECTED || ui.state.geometry() == null) return;
        List<GridPoint> points = new ArrayList<>(ui.state.geometry().vertices());
        int index = hitVertex(frame, points, x, y);
        if (index < 0) return;
        if (points.size() <= 3) { ui.say("polygon_minimum_points"); return; }
        try {
            SelectionState.Snapshot before = ui.state.snapshot(); points.remove(index);
            PolygonGeometry next = new PolygonGeometry(points); next.triangulate();
            ui.state.setGeometry(next); ui.commitSelection(before, ui.state.snapshot(), "vertex_delete");
            ui.say("polygon_vertex_deleted");
        } catch (IllegalArgumentException | IllegalStateException e) { ui.say("polygon_invalid"); }
    }

    void beginVertexDrag(MapBridge.Frame frame, double x, double y) {
        List<GridPoint> points = mode && !draft.isEmpty() ? draft
                : ui.state.geometry() != null && ui.state.phase() == SelectionState.Phase.SELECTED ? ui.state.geometry().vertices() : List.of();
        int index = hitVertex(frame, points, x, y);
        if (index < 0) return;
        ui.gesture = new MapGesture.VertexDrag(ui.state.snapshot(), index, Math.round(frame.worldX(x)), Math.round(frame.worldZ(y)),
                mode && !draft.isEmpty() ? List.copyOf(points) : null);
        cursor = null;
        ui.say("polygon_vertex_drag");
    }

    void trackPolygonCursor(MapBridge.Frame frame, double mouseX, double mouseY) {
        if (mode && !(ui.gesture instanceof MapGesture.VertexDrag) && !ui.selectionPanel.overPanel(mouseX, mouseY)) {
            try { cursor = polygonPoint(frame, mouseX, mouseY, draft.isEmpty() ? null : draft.getLast()); }
            catch (IllegalArgumentException e) { cursor = null; }
        }
    }

    /** The contour being drawn: its lines and vertices, and the closing hint on the first vertex. */
    void renderDraft(GuiGraphics g, MapBridge.Frame frame, double mouseX, double mouseY) {
        GeometryScreenRenderer.drawPolyline(g, frame, draft, cursor,
                ui.screen.width, ui.screen.height, ACCENT);
        for (int i = 0; i < draft.size(); i++) {
            GridPoint point = draft.get(i);
            double px = frame.screenX(point.x()), py = frame.screenY(point.z());
            if (px < -5 || px > ui.screen.width + 5 || py < -5 || py > ui.screen.height + 5) continue;
            boolean closing = i == 0 && draft.size() >= 3 && !(ui.gesture instanceof MapGesture.VertexDrag)
                    && Math.hypot(px - mouseX, py - mouseY) <= 8;
            int color = ui.mapOverlays.vertexColor(i, i == 0 ? 0xFF80FF95 : ACCENT);
            MapOverlays.marker(g, (int) px, (int) py, 3, color);
            if (closing) {
                g.renderOutline((int) px - 5, (int) py - 5, 11, 11, -1);
                ui.mapOverlays.label(g, ui.tr("polygon_close"), (int) px, (int) py - 16);
            }
        }
    }
}
