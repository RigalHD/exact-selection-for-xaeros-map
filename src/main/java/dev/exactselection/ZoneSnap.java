package dev.exactselection;

import dev.exactselection.SavedAreaStore.Area;
import java.util.List;

/** Sticking to saved areas, so neighbours share a border instead of leaving a gap or an overlap. Alt turns it off. */
final class ZoneSnap extends UiPart {
    ZoneSnap(ExactSelectionController ui) {
        super(ui);
    }

    /** A vertex, or else a point on a side, of a shown area within reach of the pointer; null when there is none. */
    GridPoint point(MapBridge.Frame frame, double x, double y) {
        if (ui.altDown || ui.chunks) return null;
        GridPoint side = null;
        for (Area area : ui.areas()) {
            // An area being edited would only pull its own vertices back.
            if (area.id().equals(ui.state.source()) || !ui.areasWindow.shownOnMap(area)) continue;
            // Most areas are nowhere near the pointer: their bounds rule them out before any vertex is measured.
            GeometryBounds b = area.geometry().bounds();
            if (x < frame.screenX(b.minX()) - PolygonTool.HIT_RADIUS
                    || x > frame.screenX(b.maxX()) + PolygonTool.HIT_RADIUS
                    || y < frame.screenY(b.minZ()) - PolygonTool.HIT_RADIUS
                    || y > frame.screenY(b.maxZ()) + PolygonTool.HIT_RADIUS) continue;
            List<GridPoint> vertices = area.geometry().vertices();
            int vertex = ui.polygonTool.hitVertex(frame, vertices, x, y);
            if (vertex >= 0) return vertices.get(vertex);
            int edge = ui.polygonTool.hitPolygonEdge(
                    frame, vertices, x, y, area.geometry().closed());
            if (edge >= 0)
                side = PolygonTool.onSide(frame, vertices.get(edge), vertices.get((edge + 1) % vertices.size()), x, y);
        }
        return side;
    }

    /** A block of the world by its X and Z. */
    record Block(int x, int z) {}

    /**
     * The block for a rectangle's corner: the one touching an area in reach on the pointer's side, so their edges meet.
     */
    Block block(MapBridge.Frame frame, double x, double y) {
        double worldX = frame.worldX(x), worldZ = frame.worldZ(y);
        GridPoint stuck = point(frame, x, y);
        if (stuck == null) return new Block(SelectionBounds.block(worldX), SelectionBounds.block(worldZ));
        return new Block(
                SelectionBounds.block(stuck.x() - (worldX < stuck.x() ? 1 : 0)),
                SelectionBounds.block(stuck.z() - (worldZ < stuck.z() ? 1 : 0)));
    }
}
