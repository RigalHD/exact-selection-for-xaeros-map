package dev.exactselection;

import java.util.List;

/** An unsupported Minimap version turns waypoint features off instead of crashing the game. */
public final class SafeWaypointAccessCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Behaves like a bridge linked against a Minimap whose internals were renamed. */
    private static final class Renamed implements WaypointAccess {
        int calls;
        private <T> T fail() { calls++; throw new NoSuchMethodError("xaero.hud.minimap.waypoint.set.WaypointSet.getWaypoints"); }
        @Override public Snapshot select(MapBridge.Context context, SelectionBounds bounds) { return fail(); }
        @Override public Snapshot all(MapBridge.Context context) { return fail(); }
        @Override public double dimensionScale() { return fail(); }
        @Override public void edit(Snapshot snapshot, List<Point> from, List<Point> to) { fail(); }
        @Override public int colorRgb(int index) { return fail(); }
        @Override public Snapshot captureSet(Snapshot snapshot) { return fail(); }
        @Override public void delete(Snapshot snapshot) { fail(); }
        @Override public void restore(Snapshot snapshot) { fail(); }
        @Override public Point create(MapBridge.Context context, int x, int y, int z, String name) { return fail(); }
        @Override public Point recreate(MapBridge.Context context, Point point) { return fail(); }
        @Override public void removeCreated(MapBridge.Context context, Point point) { fail(); }
    }

    public static void main(String[] args) throws Exception {
        require(SafeWaypointAccess.create(() -> { throw new NoClassDefFoundError("xaero/hud/minimap/BuiltInHudModules"); }) == null,
                "A Minimap that cannot load the bridge did not turn waypoints off");

        Renamed renamed = new Renamed();
        WaypointAccess safe = SafeWaypointAccess.create(() -> renamed);
        require(safe != null, "A loadable bridge was dropped");
        require(safe.select(null, new SelectionBounds(0, 0, 9, 9)) == null, "Missing Minimap method crashed waypoint selection");
        require(safe.all(null) == null && safe.dimensionScale() == 1 && safe.colorRgb(3) == 0xFFFFFF,
                "Queries after an unsupported Minimap did not fall back");
        require(renamed.calls == 1, "The bridge was called again after it failed to link");
        for (Runnable action : List.<Runnable>of(
                () -> { try { safe.delete(null); } catch (Exception expected) { throw new IllegalStateException(expected); } },
                () -> { try { safe.create(null, 0, 64, 0, "Point"); } catch (Exception expected) { throw new IllegalStateException(expected); } })) {
            try {
                action.run();
                throw new AssertionError("Waypoint action pretended to succeed on an unsupported Minimap");
            } catch (IllegalStateException expected) {
                // The controller reports this like any other failed waypoint action.
            }
        }
        System.out.println("PASS: unsupported Minimap versions turn waypoint features off without crashing");
    }
}
