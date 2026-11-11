package dev.exactselection;

import static dev.exactselection.ExactSelectionController.CLICK_SLOP;
import static dev.exactselection.SelectionPanel.formatLength;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/** The ruler: measures the length of a line through block corners on the map, which can be saved as a route. */
final class RulerTool extends UiPart {
    /** The ruler is drawn in the warning yellow, which stands out against the teal selection. */
    static final int COLOR = Theme.WARNING;
    /** Ruler: block corners A and B (B null while it follows the cursor); a press that drags sets B on release. */
    boolean mode;

    boolean pressed;
    private boolean compactBefore;
    GridPoint pointA;
    /** The points between A and B, added with Ctrl/Cmd + click. */
    private final List<GridPoint> via = new ArrayList<>();

    GridPoint pointB;
    private GridPoint cursor;

    RulerTool(ExactSelectionController ui) {
        super(ui);
    }

    /** The map screen went away while a ruler point was held. */
    void endPress() {
        pressed = false;
    }

    void toggleRulerMode() {
        if (!ui.enabled() || ui.holding() || !ui.polygonTool.draft.isEmpty()) return;
        setRulerMode(!mode);
        if (mode) {
            if (ui.state.phase() == SelectionState.Phase.PREVIEW) {
                if (!(ui.gesture instanceof MapGesture.Corners corners && corners.cancel(ui.gestureHost)))
                    ui.state.reset();
                ui.syncFields();
            }
            ui.polygonTool.turnOff();
            ui.polygonTool.clearDraft();
        }
        ui.message = mode ? ui.tr("ruler_controls").getString() : "";
        ui.updateSelection();
    }

    /** The ruler shows the compact panel, without the selection's fields; leaving it brings the previous one back. */
    void setRulerMode(boolean on) {
        if (on == mode) return;
        if (on) compactBefore = ui.selectionPanel.compact;
        mode = on;
        clearRuler();
        ui.selectionPanel.showCompact(on || compactBefore);
        ui.focus(null);
        ui.selectionPanel.layoutMainPanel();
    }

    void clearRuler() {
        pointA = pointB = cursor = null;
        via.clear();
        pressed = false;
    }

    /** Takes back the last point: B falls back on the point before it, or follows the cursor again. */
    void removeLastPoint() {
        if (!via.isEmpty()) pointB = via.removeLast();
        else if (pointB != null) pointB = null;
        else clearRuler();
    }

    boolean routeReady() {
        return pointB != null && rulerDistance() > 0;
    }

    void saveRoute() {
        if (routeReady()) ui.newAreaWindow.openRouteNameDialog(new PolylineGeometry(rulerRoute()));
    }

    /** A, the points on the way and the current end. */
    private List<GridPoint> rulerRoute() {
        List<GridPoint> route = new ArrayList<>();
        if (pointA != null) route.add(pointA);
        route.addAll(via);
        if (rulerEnd() != null) route.add(rulerEnd());
        return route;
    }

    /** The block corner nearest the pointer, as for a contour vertex. Null outside the world. */
    private GridPoint rulerPoint(MapBridge.Frame frame, double x, double y, GridPoint anchor) {
        try {
            return ui.polygonTool.polygonPoint(frame, x, y, anchor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private GridPoint rulerEnd() {
        return pointB != null ? pointB : cursor;
    }

    /** Length of the line from A to the current end. */
    private double rulerDistance() {
        List<GridPoint> route = rulerRoute();
        double length = 0;
        for (int i = 1; i < route.size(); i++)
            length += Math.hypot(
                    route.get(i).x() - route.get(i - 1).x(),
                    route.get(i).z() - route.get(i - 1).z());
        return length;
    }

    String rulerDistanceText() {
        return pointA == null || rulerEnd() == null
                ? ui.tr("ruler_distance_empty").getString()
                : ui.tr("ruler_distance", formatLength(rulerDistance())).getString();
    }

    List<String> rulerDeltaParts() {
        GridPoint end = rulerEnd();
        if (pointA == null || end == null) return List.of(ui.tr("ruler_start").getString());
        long dx = Math.abs(end.x() - pointA.x()), dz = Math.abs(end.z() - pointA.z());
        return List.of(
                ui.tr("ruler_dx", dx).getString(),
                ui.tr("ruler_dz", dz).getString(),
                ui.tr("ruler_axes", dx + dz).getString());
    }

    /** The same distance in the Nether (one block per eight) or, from the Nether, in the Overworld. */
    String rulerOtherDimensionText() {
        if (pointA == null || rulerEnd() == null || ui.context == null) return null;
        // By name: the Level constants would load the game's registries.
        String dimension = ui.context.dimension() == null
                ? ""
                : ui.context.dimension().identifier().toString();
        if (dimension.equals("minecraft:the_nether"))
            return ui.tr("ruler_overworld", formatLength(rulerDistance() * 8)).getString();
        if (dimension.equals("minecraft:overworld"))
            return ui.tr("ruler_nether", formatLength(rulerDistance() / 8)).getString();
        return null;
    }

    void copyRulerDistance() {
        if (pointA == null || pointB == null) return;
        double distance = rulerDistance();
        ui.selectionPanel.copyToClipboard(
                ui.autosave.rounding() == AutosaveSettings.Rounding.EXACT
                        ? formatLength(distance)
                        : ui.selectionPanel.rounded(distance));
    }

    void renderRuler(GuiGraphics g, MapBridge.Frame frame) {
        List<GridPoint> route = rulerRoute();
        if (route.isEmpty()) return;
        for (int i = 1; i < route.size(); i++) {
            GridPoint a = route.get(i - 1), b = route.get(i);
            if (!a.equals(b))
                GeometryScreenRenderer.drawSegment(
                        g,
                        frame.screenX(a.x()),
                        frame.screenY(a.z()),
                        frame.screenX(b.x()),
                        frame.screenY(b.z()),
                        ui.screen.width,
                        ui.screen.height,
                        COLOR);
        }
        g.nextStratum();
        for (GridPoint point : route) {
            double px = frame.screenX(point.x()), py = frame.screenY(point.z());
            if (px < -5 || px > ui.screen.width + 5 || py < -5 || py > ui.screen.height + 5) continue;
            MapOverlays.marker(g, (int) px, (int) py, 2, COLOR);
        }
        if (rulerDistance() > 0) {
            GridPoint a = route.get(route.size() - 2), b = route.getLast();
            ui.mapOverlays.label(
                    g,
                    ui.tr("ruler_label", formatLength(rulerDistance())),
                    (int) ((frame.screenX(a.x()) + frame.screenX(b.x())) / 2),
                    (int) ((frame.screenY(a.z()) + frame.screenY(b.z())) / 2) - 14,
                    COLOR);
        }
    }

    void clickRuler(MapBridge.Frame frame, MouseButtonEvent event) {
        // A second click sets B and Ctrl/Cmd + click goes on from it; otherwise the press starts a new measurement,
        // and a drag sets B on release.
        boolean extend = pointB != null && (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        GridPoint point = rulerPoint(frame, event.x(), event.y(), extend ? pointB : pointB == null ? pointA : null);
        if (point == null) return;
        ui.message = "";
        if (extend) extendRoute(point);
        else if (pointA != null && pointB == null) pointB = point;
        else {
            clearRuler();
            pointA = cursor = point;
            pressed = true;
        }
    }

    private void extendRoute(GridPoint point) {
        if (point.equals(pointB)) return;
        if (rulerRoute().size() >= PolygonGeometry.MAX_VERTICES) {
            ui.say("polygon_too_many_points");
            return;
        }
        // A B on top of A has no length of its own to keep.
        if (!via.isEmpty() || !pointB.equals(pointA)) via.add(pointB);
        pointB = point;
    }

    void releaseRuler(MouseButtonEvent event) {
        pressed = false;
        MapBridge.Frame frame = ui.checkedFrame();
        if (frame != null
                && pointA != null
                && pointB == null
                && Math.hypot(event.x() - ui.mapPointer.pressX, event.y() - ui.mapPointer.pressY) >= CLICK_SLOP) {
            GridPoint point = rulerPoint(frame, event.x(), event.y(), pointA);
            if (point != null) pointB = point;
        }
    }

    void trackRulerCursor(MapBridge.Frame frame, double mouseX, double mouseY) {
        if (mode && pointA != null && pointB == null && !ui.selectionPanel.overPanel(mouseX, mouseY)) {
            GridPoint point = rulerPoint(frame, mouseX, mouseY, pointA);
            if (point != null) cursor = point;
        }
    }
}
