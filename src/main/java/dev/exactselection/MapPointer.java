package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import static dev.exactselection.ExactSelectionController.CLICK_SLOP;

/** Presses and releases on the map itself: what a click, double click or drag means for the current selection. */
final class MapPointer extends UiPart {
    double rightPressX;
    double rightPressY;
    /** A contour-mode press inside a finished shape: a release without movement adds a vertex there instead of moving. */
    private boolean vertexOnClick;
    double pressX;
    double pressY;
    /** The pointer of a move or resize has left the CLICK_SLOP zone around its press. */
    private boolean dragMoved;
    /** When and where the last click that a double click acts on (a side or a vertex) was made, for one Minecraft missed. */
    private long clickAt;
    private double clickX;
    private double clickY;
    /** The left press landed on a side of the finished polygon: a click there does not start a contour. */
    private boolean sidePress;

    MapPointer(ExactSelectionController ui) { super(ui); }

    private void rememberClick(MouseButtonEvent event) {
        clickAt = Util.getMillis();
        clickX = event.x(); clickY = event.y();
    }

    /** A gesture that is still a click until the pointer leaves the click zone. */
    private static boolean clickable(MapGesture gesture) {
        return gesture instanceof MapGesture.Drag || gesture instanceof MapGesture.VertexDrag;
    }

    boolean pressMap(MouseButtonEvent event, boolean twice) {
        MapBridge.Frame frame = ui.checkedFrame();
        if (!ui.enabled() || frame == null) return true;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            ui.dragging = true;
            ui.lastMouseX = rightPressX = event.x(); ui.lastMouseY = rightPressY = event.y();
        } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) pressLeft(frame, event, twice);
        return true;
    }

    private void pressLeft(MapBridge.Frame frame, MouseButtonEvent event, boolean twice) {
        vertexOnClick = false;
        // Typed coordinates hold the map back until they are applied or dropped, so a click says why.
        if (ui.dirty) ui.say(ui.selectionPanel.compact ? "coordinates_pending" : "coordinates_pending_apply");
        if (ui.dirty || ui.holding()) return;
        pressX = event.x(); pressY = event.y();
        dragMoved = false;
        if (ui.rulerTool.mode) { ui.rulerTool.clickRuler(frame, event); return; }
        boolean ctrl = (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        // Minecraft reports a double click only within 250 ms, shorter than the system's double-click time; a
        // second press within 500 ms on the side or vertex just clicked is a double click too.
        if (!twice && Util.getMillis() - clickAt < 500
                && Math.hypot(event.x() - clickX, event.y() - clickY) < CLICK_SLOP * 2) twice = true;
        clickAt = 0;
        sidePress = false;
        if (pressVertex(frame, event, ctrl, twice) || pressSide(frame, event, ctrl, twice)) return;
        // In contour mode a press inside a finished shape may become a move; a click without movement still
        // adds the first vertex when the button is released.
        if (ui.polygonTool.mode && (!ui.polygonTool.draft.isEmpty() || !ui.insideBounds(frame, event.x(), event.y()))) {
            ui.polygonTool.addPolygonPoint(frame, event.x(), event.y());
            return;
        }
        vertexOnClick = ui.polygonTool.mode && !sidePress;
        pressSelection(frame, event);
    }

    /** A press on a vertex drags it, or with Ctrl/Cmd twice deletes it; true when that used up the press. */
    private boolean pressVertex(MapBridge.Frame frame, MouseButtonEvent event, boolean ctrl, boolean twice) {
        if (!ctrl && !twice && (ui.polygonTool.mode && !ui.polygonTool.draft.isEmpty() || ui.state.geometry() instanceof PolygonGeometry)) {
            // Holding the button on a contour or polygon vertex drags it; without movement it stays a click, which
            // closes a contour on its first vertex, or on a finished shape in contour mode starts a new one.
            vertexOnClick = ui.polygonTool.mode;
            ui.polygonTool.beginVertexDrag(frame, event.x(), event.y());
            if (ui.holding()) return true;
            vertexOnClick = false;
        }
        if (ctrl && (ui.polygonTool.mode || ui.state.geometry() instanceof PolygonGeometry
                        || ui.state.geometry() instanceof RectangleGeometry && ui.state.phase() == SelectionState.Phase.SELECTED)) {
            if (twice) ui.polygonTool.deleteVertex(frame, event.x(), event.y());
            else ui.polygonTool.beginVertexDrag(frame, event.x(), event.y());
            // Away from a vertex the Polygon tool still adds one, Ctrl/Cmd held or not.
            return ui.holding() || twice || !ui.polygonTool.mode && !(ui.state.geometry() instanceof RectangleGeometry);
        }
        return false;
    }

    /** On a side a click shows the controls and a double click adds a vertex; true when that used up the press. */
    private boolean pressSide(MapBridge.Frame frame, MouseButtonEvent event, boolean ctrl, boolean twice) {
        if (ui.state.source() != null && ui.state.geometry() instanceof RectangleGeometry rectangle
                && ui.state.phase() == SelectionState.Phase.SELECTED && ui.polygonTool.draft.isEmpty() && !ui.polygonTool.mode) {
            int edge = ui.polygonTool.hitPolygonEdge(frame, rectangle.vertices(), event.x(), event.y());
            // A press inside moves the territory; only a double click or a press just outside acts on the side.
            if (edge >= 0 && (twice || (ui.hitHandle(frame, event.x(), event.y()) == null
                    && !ui.insideBounds(frame, event.x(), event.y())))) {
                if (twice) ui.polygonTool.insertPolygonPoint(frame, new PolygonGeometry(rectangle.vertices()), edge, event.x(), event.y());
                else { ui.say("rectangle_edit_controls"); rememberClick(event); }
                return true;
            }
        }
        if (ui.polygonTool.draft.isEmpty() && ui.state.geometry() instanceof PolygonGeometry polygon) {
            // This also holds with the Polygon tool on (a contour just closed): a click on a side does not start a
            // new contour there. Just inside, a drag still moves the shape.
            boolean nearOutside = !ui.insideBounds(frame, event.x(), event.y());
            int edge = ui.polygonTool.hitPolygonEdge(frame, polygon.vertices(), event.x(), event.y());
            sidePress = edge >= 0;
            if (edge >= 0 && (twice || nearOutside)) {
                if (twice) ui.polygonTool.insertPolygonPoint(frame, polygon, edge, event.x(), event.y());
                else { ui.say("polygon_edit_controls"); rememberClick(event); }
                return true;
            }
            nearOutside &= !ui.polygonTool.mode;
            if (ui.polygonTool.hitVertex(frame, polygon.vertices(), event.x(), event.y()) >= 0 && nearOutside) {
                ui.say("polygon_edit_controls");
                return true;
            }
        }
        if (ui.polygonTool.mode && !ui.polygonTool.draft.isEmpty() && !ctrl) {
            int edge = ui.polygonTool.hitPolygonEdge(frame, ui.polygonTool.draft, event.x(), event.y(), false);
            if (edge >= 0) {
                if (twice) ui.polygonTool.insertDraftPoint(frame, edge, event.x(), event.y());
                else { ui.say("polygon_draft_controls"); rememberClick(event); }
                return true;
            }
        }
        return false;
    }

    /** A handle or the inside of the selection starts a drag; anywhere else the press is a corner of a new selection. */
    private void pressSelection(MapBridge.Frame frame, MouseButtonEvent event) {
        try {
            MapGesture.Handle handle = ui.hitHandle(frame, event.x(), event.y());
            int[] corner = ui.zoneSnap.block(frame, event.x(), event.y());
            int worldX = corner[0], worldZ = corner[1];
            boolean moveWhole = ui.insideBounds(frame, event.x(), event.y());
            if (handle != null || moveWhole) {
                // Handles resize; Shift still moves the whole shape even from a handle.
                boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
                ui.gesture = MapGesture.Drag.start(ui.state.snapshot(), handle == null || moveWhole && shift ? MapGesture.Handle.MOVE : handle,
                        frame, event.x(), event.y());
                return;
            }
            boolean finishing = ui.state.phase() == SelectionState.Phase.PREVIEW;
            SelectionState.Snapshot before = ui.historyBase();
            if (finishing && ui.chunks) {
                int originX = ui.chunkOriginX(), originZ = ui.chunkOriginZ();
                int anchorX = ChunkSelection.anchor(originX, worldX);
                int anchorZ = ChunkSelection.anchor(originZ, worldZ);
                worldX = ChunkSelection.endpoint(worldX, originX);
                worldZ = ChunkSelection.endpoint(worldZ, originZ);
                ui.state.hover(anchorX, anchorZ, worldX, worldZ);
            } else if (!finishing) {
                int originX = worldX, originZ = worldZ;
                if (ui.chunks) {
                    worldX = ChunkSelection.anchor(originX, originX);
                    worldZ = ChunkSelection.anchor(originZ, originZ);
                }
                ui.gesture = new MapGesture.Corners(before, originX, originZ);
            }
            ui.state.click(worldX, worldZ);
            if (finishing && !(ui.gesture instanceof MapGesture.Corners corners && corners.finish(ui.gestureHost)))
                ui.commitSelection(before, ui.state.snapshot(), "select");
            ui.message = "";
            ui.syncFields();
            ui.updateSelection();
        } catch (IllegalArgumentException e) { ui.say("invalid_coordinates"); }
    }

    void releaseHeld(MouseButtonEvent event, MapGesture.Held held) {
        // A lost or changed map context disables the tool, which drops the gesture; update and finish then do nothing.
        MapBridge.Frame frame = ui.checkedFrame();
        // A move or resize that barely left its press point is a click: the shape stays as it was (zoomed out, a
        // jitter of a pixel is many blocks), and in contour mode the click adds a vertex there.
        boolean click = clickable(held) && !dragMoved
                && Math.hypot(event.x() - pressX, event.y() - pressY) < CLICK_SLOP;
        boolean vertex = click && vertexOnClick && ui.polygonTool.mode;
        vertexOnClick = false;
        if (click && frame != null) {
            ui.cancelGesture();
            if (sidePress) {
                ui.say("polygon_edit_controls");
                rememberClick(event);
            }
            if (held instanceof MapGesture.VertexDrag drag) {
                ui.message = "";
                rememberClick(event);
                // On a contour vertex a click only closes the contour on its first one.
                if (vertex && !ui.polygonTool.draft.isEmpty()) {
                    if (drag.index == 0 && ui.polygonTool.draft.size() >= 3) ui.polygonTool.finishPolygon();
                    return;
                }
            }
            if (vertex) ui.polygonTool.addPolygonPoint(frame, event.x(), event.y());
            return;
        }
        if (frame != null) held.update(ui.gestureHost, frame, event.x(), event.y());
        held.finish(ui.gestureHost);
    }

    void followPointer(MapBridge.Frame frame, double mouseX, double mouseY) {
        // A move or resize follows the pointer only once it leaves the click zone, so a jittery click does not jump.
        if (clickable(ui.gesture) && !dragMoved
                && Math.hypot(mouseX - pressX, mouseY - pressY) >= CLICK_SLOP) dragMoved = true;
        if (frame != null && ui.gesture instanceof MapGesture.Held held && ui.dialog == Dialog.NONE
                && (dragMoved || !clickable(held)))
            held.update(ui.gestureHost, frame, mouseX, mouseY);
    }
}
