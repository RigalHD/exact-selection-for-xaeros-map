package dev.exactselection;

import java.util.ArrayList;
import java.util.List;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.map.mods.SupportMods;

public final class XaeroWaypointBridge implements WaypointAccess {
    @Override
    public Snapshot select(MapBridge.Context context, SelectionBounds bounds) {
        return bounds == null ? null : select(context, new RectangleGeometry(bounds));
    }

    @Override
    public Snapshot select(MapBridge.Context context, AreaGeometry geometry) {
        if (!SupportMods.minimap() || context == null || geometry == null) return null;
        var support = SupportMods.xaeroMinimap;
        // Respect Xaero's current waypoint availability; never select projected dimensions.
        if (support.getWaypointWorld() == null) return null;
        MinimapWorld world = support.getMapWaypointWorld();
        if (world == null || !context.dimension().equals(world.getDimId())) return null;
        WaypointSet set = world.getCurrentWaypointSet();
        if (set == null) return null;
        // Runs every frame: only the matching points are captured; captureSet records the whole set on demand.
        List<Point> points = new ArrayList<>();
        for (Waypoint w : set.getWaypoints()) {
            if (ordinary(w) && geometry.containsBlockCenter(w.getX(), w.getZ())) points.add(point(w));
        }
        return new Snapshot(world, set, set.getName(), points);
    }

    @Override
    public Snapshot captureSet(Snapshot snapshot) {
        if (snapshot == null) return null;
        MinimapWorld world = (MinimapWorld) snapshot.world();
        if (!SupportMods.minimap() || world != SupportMods.xaeroMinimap.getMapWaypointWorld()
                || snapshot.set() != world.getCurrentWaypointSet()) return null;
        List<Point> setPoints = new ArrayList<>();
        for (Waypoint w : ((WaypointSet) snapshot.set()).getWaypoints()) setPoints.add(point(w));
        List<Point> points = new ArrayList<>();
        for (Point p : snapshot.points()) {
            Waypoint w = (Waypoint) p.identity();
            if (!setPoints.contains(point(w))) return null;
            points.add(point(w));
        }
        return new Snapshot(world, snapshot.set(), snapshot.setName(), points, setPoints);
    }

    private static boolean ordinary(Waypoint w) {
        return !w.isTemporary() && !w.isServerWaypoint() && !w.isDestination()
                && w.getPurpose() == WaypointPurpose.NORMAL;
    }

    private static Point point(Waypoint w) {
        return new Point(w, w.getX(), w.getY(), w.getZ(), w.getName(), w.isDisabled(), ordinary(w),
                w.getColor(), w.getSymbol(), w.getVisibilityType(), w.getInitials(), w.getWaypointType(),
                w.getYaw(), w.isRotation(), w.isYIncluded(), w.isGlobal(), w.getPurpose().name(),
                w.isTemporary(), w.isDestination(), w.isServerWaypoint(), w.getCreatedAt(), null, null);
    }

    @Override
    public void delete(Snapshot snapshot) throws Exception {
        MinimapWorld world = (MinimapWorld) snapshot.world();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || world != SupportMods.xaeroMinimap.getMapWaypointWorld()
                || snapshot.set() != world.getCurrentWaypointSet())
            throw new IllegalStateException("Waypoint context changed");
        try {
            deleteAndSave(snapshot, () -> session.getWorldManagerIO().saveWorld(world));
        } finally {
            SupportMods.xaeroMinimap.requestWaypointsRefresh();
        }
    }

    @FunctionalInterface interface Save { void run() throws Exception; }

    static void deleteAndSave(Snapshot snapshot, Save save) throws Exception {
        WaypointSet set = (WaypointSet) snapshot.set();
        List<Waypoint> before = new ArrayList<>();
        set.addTo(before);
        for (Point p : snapshot.points()) {
            Waypoint w = (Waypoint) p.identity();
            if (!before.contains(w) || !matches(w, p))
                throw new IllegalStateException("Waypoint changed after confirmation");
        }
        try {
            for (Point p : snapshot.points()) set.remove((Waypoint) p.identity());
            save.run();
        } catch (Exception failure) {
            set.clear();
            set.addAll(before);
            // Retry the normal writer with the restored set if a write failed part-way through.
            try { save.run(); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        }
    }

    private static boolean matches(Waypoint waypoint, Point expected) {
        Point actual = point(waypoint);
        boolean basic = actual.x() == expected.x() && actual.y() == expected.y() && actual.z() == expected.z()
                && actual.name().equals(expected.name()) && actual.disabled() == expected.disabled()
                && actual.editable() == expected.editable();
        return basic && (expected.purpose().isEmpty() || actual.equals(expected));
    }

    @Override
    public void restore(Snapshot snapshot) throws Exception {
        MinimapWorld world = (MinimapWorld) snapshot.world();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || world != SupportMods.xaeroMinimap.getMapWaypointWorld()
                || snapshot.set() != world.getCurrentWaypointSet())
            throw new IllegalStateException("Waypoint context changed");
        WaypointSet set = (WaypointSet) snapshot.set();
        List<Waypoint> current = new ArrayList<>(); set.addTo(current);
        for (Point p : snapshot.points()) {
            Waypoint waypoint = (Waypoint) p.identity();
            if (current.contains(waypoint)) throw new IllegalStateException("Waypoint already exists");
        }
        List<Waypoint> merged = new ArrayList<>(), additions = new ArrayList<>();
        for (Waypoint waypoint : current) {
            if (indexOfSnapshot(snapshot.setPoints(), waypoint) >= 0) merged.add(waypoint);
            else additions.add(waypoint);
        }
        for (Point p : snapshot.points()) {
            Waypoint waypoint = (Waypoint) p.identity();
            if (!point(waypoint).equals(p)) throw new IllegalStateException("Deleted waypoint changed");
            int original = -1;
            for (int i = 0; i < snapshot.setPoints().size(); i++)
                if (snapshot.setPoints().get(i).identity() == waypoint) { original = i; break; }
            int insertion = merged.size();
            if (original >= 0) {
                for (int i = original + 1; i < snapshot.setPoints().size(); i++) {
                    Object next = snapshot.setPoints().get(i).identity();
                    int index = indexOfIdentity(merged, next);
                    if (index >= 0) { insertion = index; break; }
                }
                if (insertion == merged.size()) {
                    for (int i = original - 1; i >= 0; i--) {
                        Object previous = snapshot.setPoints().get(i).identity();
                        int index = indexOfIdentity(merged, previous);
                        if (index >= 0) { insertion = index + 1; break; }
                    }
                }
            }
            merged.add(Math.max(0, Math.min(insertion, merged.size())), waypoint);
        }
        merged.addAll(additions);
        List<Waypoint> before = new ArrayList<>(current);
        try {
            set.clear(); set.addAll(merged);
            session.getWorldManagerIO().saveWorld(world);
        } catch (Exception failure) {
            set.clear(); set.addAll(before);
            try { session.getWorldManagerIO().saveWorld(world); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            SupportMods.xaeroMinimap.requestWaypointsRefresh();
        }
    }

    @Override
    public Point create(MapBridge.Context context, int x, int y, int z, String name) throws Exception {
        if (!SupportMods.minimap() || context == null) throw new IllegalStateException("Minimap unavailable");
        var support = SupportMods.xaeroMinimap;
        if (support.getWaypointWorld() == null) throw new IllegalStateException("Waypoints unavailable");
        MinimapWorld world = support.getMapWaypointWorld();
        if (world == null || !context.dimension().equals(world.getDimId()))
            throw new IllegalStateException("Waypoint context changed");
        WaypointSet set = world.getCurrentWaypointSet();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (set == null || session == null) throw new IllegalStateException("Waypoint set unavailable");
        Waypoint waypoint = new Waypoint(x, y, z, name, "", WaypointColor.YELLOW);
        set.add(waypoint);
        try { session.getWorldManagerIO().saveWorld(world); }
        catch (Exception failure) {
            set.remove(waypoint);
            try { session.getWorldManagerIO().saveWorld(world); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally { support.requestWaypointsRefresh(); }
        Point p = point(waypoint);
        return new Point(p.identity(), p.x(), p.y(), p.z(), p.name(), p.disabled(), p.editable(), p.color(), p.symbol(),
                p.visibility(), p.initials(), p.type(), p.yaw(), p.rotation(), p.yIncluded(), p.global(), p.purpose(),
                p.temporary(), p.destination(), p.serverWaypoint(), p.createdAt(), world, set);
    }

    @Override
    public Point recreate(MapBridge.Context context, Point point) throws Exception {
        if (point == null || point.worldIdentity() == null || point.setIdentity() == null)
            throw new IllegalStateException("Waypoint context changed");
        MinimapWorld world = (MinimapWorld) point.worldIdentity();
        WaypointSet set = (WaypointSet) point.setIdentity();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (!SupportMods.minimap() || session == null || world != SupportMods.xaeroMinimap.getMapWaypointWorld()
                || set != world.getCurrentWaypointSet() || !context.dimension().equals(world.getDimId()))
            throw new IllegalStateException("Waypoint context changed");
        Waypoint waypoint = new Waypoint(point.x(), point.y(), point.z(), point.name(), point.symbol(),
                WaypointColor.fromIndex(point.color()));
        waypoint.setInitials(point.initials()); waypoint.setVisibilityType(point.visibility());
        waypoint.setType(point.type()); waypoint.setYaw(point.yaw()); waypoint.setRotation(point.rotation());
        waypoint.setYIncluded(point.yIncluded()); waypoint.setDisabled(point.disabled());
        waypoint.setPurpose(WaypointPurpose.valueOf(point.purpose()));
        set.add(waypoint);
        try { session.getWorldManagerIO().saveWorld(world); }
        catch (Exception failure) {
            set.remove(waypoint);
            try { session.getWorldManagerIO().saveWorld(world); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally { SupportMods.xaeroMinimap.requestWaypointsRefresh(); }
        Point created = point(waypoint);
        return new Point(created.identity(), created.x(), created.y(), created.z(), created.name(), created.disabled(),
                created.editable(), created.color(), created.symbol(), created.visibility(), created.initials(), created.type(),
                created.yaw(), created.rotation(), created.yIncluded(), created.global(), created.purpose(),
                created.temporary(), created.destination(), created.serverWaypoint(), created.createdAt(), world, set);
    }

    @Override
    public void removeCreated(MapBridge.Context context, Point point) throws Exception {
        if (!SupportMods.minimap() || context == null || point == null) throw new IllegalStateException("Minimap unavailable");
        var support = SupportMods.xaeroMinimap;
        MinimapWorld world = (MinimapWorld) point.worldIdentity();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (world == null || session == null || world != support.getMapWaypointWorld()
                || !context.dimension().equals(world.getDimId()))
            throw new IllegalStateException("Waypoint context changed");
        WaypointSet set = (WaypointSet) point.setIdentity();
        if (set == null || set != world.getCurrentWaypointSet()) throw new IllegalStateException("Waypoint set changed");
        Waypoint waypoint = (Waypoint) point.identity();
        List<Waypoint> existing = new ArrayList<>(); set.addTo(existing);
        if (!existing.contains(waypoint) || !point(waypoint).equals(withoutOwner(point))) throw new IllegalStateException("Waypoint changed");
        set.remove(waypoint);
        try { session.getWorldManagerIO().saveWorld(world); }
        catch (Exception failure) {
            set.add(waypoint);
            try { session.getWorldManagerIO().saveWorld(world); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally { support.requestWaypointsRefresh(); }
    }

    private static Point withoutOwner(Point p) {
        return new Point(p.identity(), p.x(), p.y(), p.z(), p.name(), p.disabled(), p.editable(), p.color(), p.symbol(),
                p.visibility(), p.initials(), p.type(), p.yaw(), p.rotation(), p.yIncluded(), p.global(), p.purpose(),
                p.temporary(), p.destination(), p.serverWaypoint(), p.createdAt(), null, null);
    }

    private static int indexOfIdentity(List<Waypoint> list, Object identity) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == identity) return i;
        return -1;
    }

    private static int indexOfSnapshot(List<Point> points, Object identity) {
        for (int i = 0; i < points.size(); i++) if (points.get(i).identity() == identity) return i;
        return -1;
    }
}
