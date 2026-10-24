package dev.exactselection;

import java.io.IOException;
import java.util.List;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.set.WaypointSet;

public final class WaypointEditCheck {
    private static WaypointAccess.Point point(Waypoint w) {
        return new WaypointAccess.Point(w, w.getX(), w.getY(), w.getZ(), w.getName(), w.isDisabled(), true)
                .with(w.getName(), w.getColor());
    }
    public static void main(String[] args) throws Exception {
        Waypoint a = new Waypoint(0, 64, 0, "First", "A", 0);
        Waypoint b = new Waypoint(1, 65, 1, "Second", "B", 1);
        WaypointSet set = WaypointSet.Builder.begin().setName("test").build();
        set.addAll(List.of(a, b));
        var before = List.of(point(a), point(b));
        var after = before.stream().map(p -> p.with("Renamed", 4)).toList();
        XaeroWaypointBridge.editAndSave(set, before, after, () -> {});
        assert a.getName().equals("Renamed") && b.getColor() == 4;
        assert a.getSymbol().equals("A") && b.getSymbol().equals("B");
        XaeroWaypointBridge.editAndSave(set, after, before, () -> {});
        assert a.getName().equals("First") && b.getColor() == 1;
        int[] writes = {0};
        try {
            XaeroWaypointBridge.editAndSave(set, before, after, () -> {
                if (++writes[0] == 1) throw new IOException("full disk");
            });
            throw new AssertionError("Save failure was swallowed");
        } catch (IOException expected) { }
        assert writes[0] == 2 && a.getName().equals("First") && b.getColor() == 1;
        a.setName("External change");
        try {
            XaeroWaypointBridge.editAndSave(set, before, after, () -> { throw new AssertionError("Stale edit saved"); });
            throw new AssertionError("Stale edit accepted");
        } catch (IllegalStateException expected) { }
        assert a.getName().equals("External change");
        System.out.println("PASS: waypoint name/color editing, undo, symbol preservation, rollback and stale edit rejection");
    }
}
