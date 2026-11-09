package dev.exactselection;

import java.io.IOException;
import java.util.List;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.set.WaypointSet;

// Xaero's older waypoint accessors, as the bridge under test uses them.
@SuppressWarnings("deprecation")
public final class WaypointDeletionCheck {
    public static void main(String[] args) throws Exception {
        // Identical names must not cause deletion of unselected objects.
        Waypoint a = new Waypoint(0, 64, 0, "same", "A", 0);
        Waypoint b = new Waypoint(1, 80, 1, "same", "B", 0);
        Waypoint c = new Waypoint(100, 64, 100, "outside", "C", 0);
        b.setDisabled(true);
        WaypointSet set = WaypointSet.Builder.begin().setName("test").build();
        set.addAll(List.of(a, b, c));
        var snapshot = new WaypointAccess.Snapshot(null, set, "test", List.of(point(a), point(b)));
        int[] writes = {0};
        XaeroWaypointBridge.deleteAndSave(snapshot, () -> {
            writes[0]++;
            assert set.size() == 1 && set.get(0) == c;
        });
        assert writes[0] == 1 && set.size() == 1 && set.get(0) == c;

        set.clear(); set.addAll(List.of(a, b, c)); writes[0] = 0;
        try {
            XaeroWaypointBridge.deleteAndSave(snapshot, () -> {
                if (++writes[0] == 1) throw new IOException("simulated full disk");
                assert set.get(0) == a && set.get(1) == b && set.get(2) == c;
            });
            throw new AssertionError("Save failure was swallowed");
        } catch (IOException expected) { }
        assert writes[0] == 2 && set.size() == 3;
        assert set.get(0) == a && set.get(1) == b && set.get(2) == c;

        // Do not accept a stale confirmation when the point was edited.
        a.setX(999);
        try {
            XaeroWaypointBridge.deleteAndSave(snapshot, () -> { throw new AssertionError("Must not write stale selection"); });
            throw new AssertionError("Changed waypoint was accepted");
        } catch (IllegalStateException expected) { }
        assert set.size() == 3;
        a.setX(0);
        try {
            XaeroWaypointBridge.deleteAndSave(snapshot, () -> { throw new IOException("disk unavailable"); });
            throw new AssertionError("Persistent save failure was swallowed");
        } catch (IOException expected) { assert expected.getSuppressed().length == 1; }
        assert set.size() == 3 && set.get(0) == a && set.get(1) == b && set.get(2) == c;
        System.out.println("PASS: identity deletion, hidden points, failed-save restoration, stale-confirmation rejection");
    }

    private static WaypointAccess.Point point(Waypoint w) {
        return new WaypointAccess.Point(w, w.getX(), w.getY(), w.getZ(), w.getName(), w.isDisabled(), true);
    }
}
