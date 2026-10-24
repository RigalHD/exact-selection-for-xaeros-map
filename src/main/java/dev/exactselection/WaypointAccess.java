package dev.exactselection;

import java.util.List;

/** Optional Minimap integration stays out of the controller's class signatures. */
public interface WaypointAccess {
    int COLOR_COUNT = 16;
    record Point(Object identity, int x, int y, int z, String name, boolean disabled, boolean editable,
                 int color, String symbol, int visibility, String initials, int type, int yaw,
                 boolean rotation, boolean yIncluded, boolean global, String purpose,
                 boolean temporary, boolean destination, boolean serverWaypoint, long createdAt,
                 Object worldIdentity, Object setIdentity) {
        public Point(Object identity, int x, int y, int z, String name, boolean disabled, boolean editable) {
            this(identity, x, y, z, name, disabled, editable, 0, "", 0, "", 0, 0,
                    false, true, false, "", false, false, false, 0, null, null);
        }
        public Point with(String newName, int newColor) {
            return new Point(identity, x, y, z, newName, disabled, editable, newColor, symbol, visibility,
                    initials, type, yaw, rotation, yIncluded, global, purpose, temporary, destination,
                    serverWaypoint, createdAt, worldIdentity, setIdentity);
        }
    }
    record Snapshot(Object world, Object set, String setName, List<Point> points, List<Point> setPoints) {
        public Snapshot {
            points = List.copyOf(points);
            setPoints = List.copyOf(setPoints);
        }
        public Snapshot(Object world, Object set, String setName, List<Point> points) {
            this(world, set, setName, points, List.of());
        }
    }
    Snapshot select(MapBridge.Context context, SelectionBounds bounds);
    default Snapshot select(MapBridge.Context context, AreaGeometry geometry) {
        if (geometry == null) return null;
        GeometryBounds b = geometry.bounds();
        return select(context, new SelectionBounds((int) b.minX(), (int) b.minZ(),
                (int) (b.maxX() - 1), (int) (b.maxZ() - 1)));
    }
    Snapshot all(MapBridge.Context context);
    /** Xaero's division from waypoint coordinates to map blocks; 1 when the waypoints belong to the viewed dimension. */
    default double dimensionScale() { return 1; }
    /** The map block where Xaero draws a waypoint coordinate. */
    static int mapBlock(int coordinate, double scale) {
        return scale == 1 ? coordinate : (int) Math.floor(coordinate / scale);
    }
    void edit(Snapshot snapshot, List<Point> from, List<Point> to) throws Exception;
    int colorRgb(int index);
    Snapshot captureSet(Snapshot snapshot);
    void delete(Snapshot snapshot) throws Exception;
    void restore(Snapshot snapshot) throws Exception;
    Point create(MapBridge.Context context, int x, int y, int z, String name) throws Exception;
    Point recreate(MapBridge.Context context, Point point) throws Exception;
    void removeCreated(MapBridge.Context context, Point point) throws Exception;
}
