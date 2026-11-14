package dev.exactselection;

import static dev.exactselection.Theme.ACCENT;

import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import xaero.map.WorldMap;
import xaero.map.common.config.option.WorldMapProfiledConfigOptions;

/** What the tool draws over the map: saved areas with their names, and the current selection with its handles. */
final class MapOverlays extends UiPart {
    record MapLabel(Component text, int x, int y, int color) {}

    record ScreenPoint(double x, double y) {}

    MapOverlays(ExactSelectionController ui) {
        super(ui);
    }

    void renderSavedAreas(GuiGraphics g, MapBridge.Frame frame, List<MapLabel> mapLabels) {
        Map<String, Group> groups = new HashMap<>();
        for (Group group : ui.groups()) groups.put(group.id(), group);
        boolean names = ui.areaStore.state().showNames();
        // One draw for all of them, in layer order.
        GeometryScreenRenderer.Batch shapes =
                new GeometryScreenRenderer.Batch(g.pose(), ui.screen.width, ui.screen.height);
        for (Area area : ui.areas()) {
            if (!area.visible()) continue;
            Group group = groups.get(area.groupId());
            if (group != null && !group.visible()) continue;
            AreaGeometry geometry = area.geometry();
            GeometryBounds geometryBounds = geometry.bounds();
            // Cull by screen bounds first: projecting and clipping every triangle of an off-screen polygon is costly.
            if (!onScreen(frame, geometryBounds)) continue;
            // The stored shape of the territory being edited fades behind its live bounds, so old and new are told
            // apart.
            boolean superseded = ui.enabled()
                    && ui.state.phase() == SelectionState.Phase.SELECTED
                    && Objects.equals(area.id(), ui.state.source());
            int rgb = area.color() & 0xFFFFFF;
            int fillAlpha = area.opacity() * 255 / 100 / (superseded ? 3 : 1);
            int fill = (fillAlpha << 24) | rgb, outline = (superseded ? 0x60000000 : 0xFF000000) | rgb;
            int labelColor = (superseded ? 0x99000000 : 0xFF000000) | rgb;
            if (!area.vertices().isEmpty()) {
                shapes.add(frame, geometry, fill, outline, superseded ? 1.0 : 1.5);
                ScreenPoint anchor = geometry.closed()
                        ? polygonLabelAnchor(frame, geometry, geometryBounds)
                        : routeLabelAnchor(frame, geometry);
                if (anchor != null && names)
                    mapLabels.add(new MapLabel(
                            Component.literal(area.name()),
                            clamp(anchor.x(), 0, ui.screen.width - 1),
                            clamp(anchor.y(), 0, ui.screen.height - 1),
                            labelColor));
                continue;
            }
            SelectionBounds b = area.bounds();
            double left = frame.screenX(b.minX()), top = frame.screenY(b.minZ());
            double right = frame.screenX((long) b.maxX() + 1), bottom = frame.screenY((long) b.maxZ() + 1);
            int x1 = clamp(left, 0, ui.screen.width), y1 = clamp(top, 0, ui.screen.height);
            int x2 = clamp(right, 0, ui.screen.width), y2 = clamp(bottom, 0, ui.screen.height);
            shapes.add(frame, geometry, fill, outline, 1.0);
            if (names && (x2 - x1) > 50 && (y2 - y1) > 14)
                mapLabels.add(new MapLabel(Component.literal(area.name()), (x1 + x2) / 2, y1 + 3, labelColor));
        }
        shapes.submit(g);
    }

    boolean onScreen(MapBridge.Frame frame, GeometryBounds bounds) {
        return frame.screenX(bounds.maxX()) > 0
                && frame.screenX(bounds.minX()) < ui.screen.width
                && frame.screenY(bounds.maxZ()) > 0
                && frame.screenY(bounds.minZ()) < ui.screen.height;
    }

    /** A route's name sits on its middle segment, once the route is long enough on screen to carry one. */
    private ScreenPoint routeLabelAnchor(MapBridge.Frame frame, AreaGeometry route) {
        if (route.perimeter() * frame.scale() / frame.guiScale() <= 50) return null;
        int middle = (route.vertices().size() - 1) / 2;
        GridPoint a = route.vertices().get(middle), b = route.vertices().get(middle + 1);
        return new ScreenPoint(
                (frame.screenX(a.x()) + frame.screenX(b.x())) / 2, (frame.screenY(a.z()) + frame.screenY(b.z())) / 2);
    }

    private ScreenPoint polygonLabelAnchor(MapBridge.Frame frame, AreaGeometry geometry, GeometryBounds bounds) {
        double left = frame.screenX(bounds.minX()), right = frame.screenX(bounds.maxX());
        double top = frame.screenY(bounds.minZ()), bottom = frame.screenY(bounds.maxZ());
        // Same minimum visible size as rectangle labels, so zoomed-out maps are not covered by names.
        if (clamp(right, 0, ui.screen.width) - clamp(left, 0, ui.screen.width) <= 50
                || clamp(bottom, 0, ui.screen.height) - clamp(top, 0, ui.screen.height) <= 14) return null;
        double bestArea = 0;
        ScreenPoint best = null;
        for (List<GridPoint> triangle : geometry.triangulate()) {
            List<ScreenPoint> visible = new ArrayList<>(3);
            for (GridPoint point : triangle)
                visible.add(new ScreenPoint(frame.screenX(point.x()), frame.screenY(point.z())));
            for (int edge = 0; edge < 4 && !visible.isEmpty(); edge++) visible = clipLabelPolygon(visible, edge);
            if (visible.size() < 3) continue;
            double twiceArea = 0, centerX = 0, centerY = 0;
            for (int i = 0; i < visible.size(); i++) {
                ScreenPoint a = visible.get(i), b = visible.get((i + 1) % visible.size());
                double cross = a.x() * b.y() - b.x() * a.y();
                twiceArea += cross;
                centerX += (a.x() + b.x()) * cross;
                centerY += (a.y() + b.y()) * cross;
            }
            double area = Math.abs(twiceArea) / 2.0;
            if (area > bestArea && Math.abs(twiceArea) > 1.0E-9) {
                bestArea = area;
                best = new ScreenPoint(centerX / (3 * twiceArea), centerY / (3 * twiceArea));
            }
        }
        return best;
    }

    private List<ScreenPoint> clipLabelPolygon(List<ScreenPoint> input, int edge) {
        double boundary = edge == 0 ? 0 : edge == 1 ? ui.screen.width : edge == 2 ? 0 : ui.screen.height;
        List<ScreenPoint> output = new ArrayList<>();
        ScreenPoint previous = input.getLast();
        boolean previousInside = insideLabelBoundary(previous, edge, boundary);
        for (ScreenPoint current : input) {
            boolean currentInside = insideLabelBoundary(current, edge, boundary);
            if (currentInside != previousInside) {
                double t = edge < 2
                        ? (boundary - previous.x()) / (current.x() - previous.x())
                        : (boundary - previous.y()) / (current.y() - previous.y());
                output.add(
                        edge < 2
                                ? new ScreenPoint(boundary, previous.y() + (current.y() - previous.y()) * t)
                                : new ScreenPoint(previous.x() + (current.x() - previous.x()) * t, boundary));
            }
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean insideLabelBoundary(ScreenPoint point, int edge, double boundary) {
        return switch (edge) {
            case 0 -> point.x() >= boundary;
            case 1 -> point.x() <= boundary;
            case 2 -> point.y() >= boundary;
            default -> point.y() <= boundary;
        };
    }

    void renderBounds(GuiGraphics g, MapBridge.Frame frame) {
        SelectionBounds b = ui.state.bounds();
        if (ui.state.geometry() instanceof PolygonGeometry polygon) {
            GeometryScreenRenderer.draw(g, frame, polygon, ui.screen.width, ui.screen.height, 0x4059E3CE, ACCENT, 2.0);
            for (int i = 0; i < polygon.vertices().size(); i++) {
                GridPoint point = polygon.vertices().get(i);
                double px = frame.screenX(point.x()), py = frame.screenY(point.z());
                if (px < -5 || px > ui.screen.width + 5 || py < -5 || py > ui.screen.height + 5) continue;
                int x = (int) Math.floor(px), y = (int) Math.floor(py);
                marker(g, x, y, 3, vertexColor(i, ACCENT));
            }
        }
        double left = frame.screenX(b.minX()), top = frame.screenY(b.minZ());
        double right = frame.screenX((long) b.maxX() + 1), bottom = frame.screenY((long) b.maxZ() + 1);
        int x1 = clamp(left, 0, ui.screen.width), y1 = clamp(top, 0, ui.screen.height);
        int x2 = clamp(right, 0, ui.screen.width), y2 = clamp(bottom, 0, ui.screen.height);
        if (!(ui.state.geometry() instanceof PolygonGeometry)) {
            GeometryScreenRenderer.draw(
                    g, frame, ui.state.geometry(), ui.screen.width, ui.screen.height, 0x2459E3CE, ACCENT, 1.0);
        }
        if (left < ui.screen.width && right > 0 && top < ui.screen.height && bottom > 0 && x1 == x2 && y1 == y2) {
            int markerX = clamp((left + right) / 2, 0, ui.screen.width - 1);
            int markerY = clamp((top + bottom) / 2, 0, ui.screen.height - 1);
            g.fill(markerX, markerY, markerX + 1, markerY + 1, ACCENT);
        }
        if (!(ui.state.geometry() instanceof PolygonGeometry) && x1 < x2 && y1 < y2) {
            label(g, ui.tr("width", b.width()), (x1 + x2) / 2, Math.max(2, y1 + 3));
            label(g, ui.tr("length", b.length()), Math.min(ui.screen.width - 60, x2 - 36), (y1 + y2) / 2);
        }
        if (!(ui.state.geometry() instanceof PolygonGeometry)) renderHandles(g, frame, b);
        Area editing = ui.areasWindow.editedArea();
        // Above the frame; with no room there, below the width label instead of over it.
        if (editing != null && x1 < x2 && y1 < y2)
            label(g, ui.tr("editing_area", editing.name()), (x1 + x2) / 2, y1 >= 16 ? y1 - 14 : y1 + 16, Theme.WARNING);
        if (ui.selected != null) {
            g.enableScissor(0, 0, ui.screen.width, ui.screen.height);
            // Xaero draws the icon above its point, 30 (43 for longer symbols) by 43 units, in window pixels scaled
            // by its waypoint scale and by the window size above 1080; the frame goes around that icon.
            double unit = Math.max(1, Math.min(frame.pixelWidth(), frame.pixelHeight()) / 1080.0)
                    * waypointIconScale()
                    / frame.guiScale();
            for (WaypointAccess.Point point : ui.selected.points()) {
                double scale = ui.waypoints.dimensionScale();
                double px = frame.screenX(WaypointAccess.mapBlock(point.x(), scale) + 0.5);
                double py = frame.screenY(WaypointAccess.mapBlock(point.z(), scale) + 0.5);
                double half = (point.symbol() != null && point.symbol().length() > 1 ? 43 : 30) / 2.0 * unit;
                int iconLeft = (int) Math.floor(px - half) - 1, iconRight = (int) Math.ceil(px + half) + 1;
                int iconTop = (int) Math.floor(py - 42 * unit) - 1, iconBottom = (int) Math.ceil(py + unit) + 1;
                if (iconRight < 0 || iconLeft > ui.screen.width || iconBottom < 0 || iconTop > ui.screen.height)
                    continue;
                g.renderOutline(iconLeft, iconTop, iconRight - iconLeft, iconBottom - iconTop, Theme.WARNING);
            }
            g.disableScissor();
        }
    }

    /** Xaero's world map waypoint scale setting; 1 when it cannot be read. */
    private static double waypointIconScale() {
        try {
            return ((Number) WorldMap.INSTANCE
                            .getConfigs()
                            .getClientConfigManager()
                            .getEffective(WorldMapProfiledConfigOptions.WAYPOINT_SCALE))
                    .doubleValue();
        } catch (Throwable unavailable) {
            return 1;
        }
    }

    /** White for the dragged vertex, red while its position is rejected. */
    int vertexColor(int index, int idle) {
        return ui.gesture instanceof MapGesture.VertexDrag vertex && vertex.index == index
                ? vertex.invalid ? 0xFFFF5573 : -1
                : idle;
    }

    private static int clamp(double value, int min, int max) {
        return (int) Math.max(min, Math.min(max, Math.floor(value)));
    }

    void label(GuiGraphics g, Component text, int x, int y) {
        label(g, text, x, y, ACCENT);
    }

    void label(GuiGraphics g, Component text, int x, int y, int color) {
        int w = ui.mc.font.width(text);
        x = Math.max(w / 2 + 2, Math.min(ui.screen.width - w / 2 - 2, x));
        g.fill(x - w / 2 - 2, y - 1, x + (w + 1) / 2 + 2, y + 10, 0xB0101820);
        g.drawCenteredString(ui.mc.font, text, x, y, color);
    }

    private void renderHandles(GuiGraphics g, MapBridge.Frame frame, SelectionBounds b) {
        double left = frame.screenX(b.minX()), right = frame.screenX((long) b.maxX() + 1);
        double top = frame.screenY(b.minZ()), bottom = frame.screenY((long) b.maxZ() + 1);
        int[][] points = {
            {(int) left, (int) top},
            {(int) ((left + right) / 2), (int) top},
            {(int) right, (int) top},
            {(int) left, (int) ((top + bottom) / 2)},
            {(int) right, (int) ((top + bottom) / 2)},
            {(int) left, (int) bottom},
            {(int) ((left + right) / 2), (int) bottom},
            {(int) right, (int) bottom}
        };
        for (int[] p : points)
            if (p[0] >= -5 && p[0] <= ui.screen.width + 5 && p[1] >= -5 && p[1] <= ui.screen.height + 5)
                marker(g, p[0], p[1], 3, ACCENT);
    }

    /** A vertex, handle or ruler point: a dark square in a colored frame. */
    static void marker(GuiGraphics g, int x, int y, int radius, int color) {
        g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, Theme.MARKER);
        g.renderOutline(x - radius, y - radius, 2 * radius + 1, 2 * radius + 1, color);
    }
}
