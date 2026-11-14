package dev.exactselection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.map.mods.SupportMods;

/**
 * Xaero's Minimap waypoints behind {@link WaypointAccess}: reads the set the World Map shows, and renames, recolors,
 * deletes and restores its waypoints, saving after every change and taking the change back when the save fails.
 * <p>
 * It uses Minimap's older waypoint accessors on purpose: they exist in every supported release from 25.3.5 on, while
 * their replacements do not.
 */
@SuppressWarnings("deprecation")
public final class XaeroWaypointBridge implements WaypointAccess {
    @Override
    public Snapshot select(MapBridge.Context context, AreaGeometry geometry) {
        return geometry == null ? null : select(context, (x, z) -> geometry.containsBlockCenter(x, z));
    }

    @Override
    public Snapshot all(MapBridge.Context context) {
        return select(context, (x, z) -> true);
    }

    @Override
    public int colorRgb(int index) {
        return WaypointColor.fromIndex(index).getHex() & 0xFFFFFF;
    }

    @FunctionalInterface
    private interface Area {
        boolean contains(int x, int z);
    }

    @FunctionalInterface
    interface Save {
        void run() throws IOException;
    }

    /**
     * The waypoint world the World Map itself draws: the minimap's current world, or the viewed map's own world when
     * Xaero's "only current map waypoints" option is on. The auto-detected map world differs from it on servers with
     * custom subworlds, which made selections find no waypoints.
     */
    private static MinimapWorld displayedWorld() {
        return SupportMods.minimap() ? SupportMods.xaeroMinimap.getWaypointWorld() : null;
    }

    /**
     * Xaero's coordinate division between the waypoint world and the viewed dimension (8 for the Overworld on a Nether
     * map).
     */
    private static double scale() {
        double scale = SupportMods.minimap() ? SupportMods.xaeroMinimap.getDimDiv() : 1;
        return scale > 0 && Double.isFinite(scale) ? scale : 1;
    }

    @Override
    public double dimensionScale() {
        return scale();
    }

    private static Snapshot select(MapBridge.Context context, Area area) {
        if (context == null) return null;
        MinimapWorld world = displayedWorld();
        if (world == null) return null;
        WaypointSet set = world.getCurrentWaypointSet();
        if (set == null) return null;
        double scale = scale();
        // Runs every frame: only the matching points are captured; captureSet records the whole set on demand.
        List<Point> points = new ArrayList<>();
        for (Waypoint w : set.getWaypoints()) {
            if (ordinary(w)
                    && area.contains(
                            WaypointAccess.mapBlock(w.getX(), scale), WaypointAccess.mapBlock(w.getZ(), scale)))
                points.add(point(w));
        }
        return new Snapshot(world, set, set.getName(), points);
    }

    @Override
    public Snapshot captureSet(Snapshot snapshot) {
        if (snapshot == null) return null;
        MinimapWorld world = (MinimapWorld) snapshot.world();
        if (!SupportMods.minimap()
                || world != SupportMods.xaeroMinimap.getWaypointWorld()
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
        return !w.isTemporary()
                && !w.isServerWaypoint()
                && !w.isDestination()
                && w.getPurpose() == WaypointPurpose.NORMAL;
    }

    private static Point point(Waypoint w) {
        return new Point(
                w,
                w.getX(),
                w.getY(),
                w.getZ(),
                w.getName(),
                w.isDisabled(),
                ordinary(w),
                w.getColor(),
                w.getSymbol(),
                w.getVisibilityType(),
                w.getInitials(),
                w.getWaypointType(),
                w.getYaw(),
                w.isRotation(),
                w.isYIncluded(),
                w.isGlobal(),
                w.getPurpose().name(),
                w.isTemporary(),
                w.isDestination(),
                w.isServerWaypoint(),
                w.getCreatedAt(),
                null,
                null);
    }

    /** Saves a waypoint world, which must still be the one the map shows; the minimap is told either way. */
    private static Save saver(Object world, Object set) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (world == null
                || set == null
                || !SupportMods.minimap()
                || session == null
                || world != SupportMods.xaeroMinimap.getWaypointWorld()
                || set != ((MinimapWorld) world).getCurrentWaypointSet())
            throw new IllegalStateException("Waypoint context changed");
        return () -> {
            try {
                session.getWorldManagerIO().saveWorld((MinimapWorld) world);
            } finally {
                SupportMods.xaeroMinimap.requestWaypointsRefresh();
            }
        };
    }

    /** Applies a change and saves it; when that fails, takes the change back and saves again. */
    private static void change(Runnable apply, Runnable undo, Save save) throws IOException {
        try {
            apply.run();
            save.run();
        } catch (Exception failure) {
            undo.run();
            // Retry the normal writer with the restored state if a write failed part-way through.
            try {
                save.run();
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    @Override
    public void delete(Snapshot snapshot) throws IOException {
        deleteAndSave(snapshot, saver(snapshot.world(), snapshot.set()));
    }

    @Override
    public void edit(Snapshot snapshot, List<Point> from, List<Point> to) throws IOException {
        editAndSave((WaypointSet) snapshot.set(), from, to, saver(snapshot.world(), snapshot.set()));
    }

    static void editAndSave(WaypointSet set, List<Point> from, List<Point> to, Save save) throws IOException {
        if (from.size() != to.size()) throw new IllegalStateException("Waypoint changed after confirmation");
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Waypoint> current = new ArrayList<>();
        set.addTo(current);
        for (int i = 0; i < from.size(); i++) {
            Point p = from.get(i), next = to.get(i);
            if (!current.contains(p.identity())
                    || !matches((Waypoint) p.identity(), p)
                    || !identities.add(p.identity())
                    || next.identity() != p.identity()
                    || next.name() == null
                    || next.name().isBlank()
                    || next.color() < 0
                    || next.color() >= COLOR_COUNT)
                throw new IllegalStateException("Waypoint changed after confirmation");
        }
        change(
                () -> {
                    for (int i = 0; i < from.size(); i++) {
                        Waypoint w = (Waypoint) from.get(i).identity();
                        w.setName(to.get(i).name());
                        w.setColor(to.get(i).color());
                    }
                },
                () -> {
                    for (Point p : from) {
                        Waypoint w = (Waypoint) p.identity();
                        w.setName(p.name());
                        w.setColor(p.color());
                    }
                },
                save);
    }

    static void deleteAndSave(Snapshot snapshot, Save save) throws IOException {
        WaypointSet set = (WaypointSet) snapshot.set();
        List<Waypoint> before = new ArrayList<>();
        set.addTo(before);
        for (Point p : snapshot.points()) {
            Waypoint w = (Waypoint) p.identity();
            if (!before.contains(w) || !matches(w, p))
                throw new IllegalStateException("Waypoint changed after confirmation");
        }
        change(
                () -> {
                    for (Point p : snapshot.points()) set.remove((Waypoint) p.identity());
                },
                () -> {
                    set.clear();
                    set.addAll(before);
                },
                save);
    }

    private static boolean matches(Waypoint waypoint, Point expected) {
        Point actual = point(waypoint);
        boolean basic = actual.x() == expected.x()
                && actual.y() == expected.y()
                && actual.z() == expected.z()
                && actual.name().equals(expected.name())
                && actual.disabled() == expected.disabled()
                && actual.editable() == expected.editable();
        return basic && (expected.purpose().isEmpty() || actual.equals(expected));
    }

    @Override
    public void restore(Snapshot snapshot) throws IOException {
        WaypointSet set = (WaypointSet) snapshot.set();
        Save save = saver(snapshot.world(), set);
        List<Waypoint> current = new ArrayList<>();
        set.addTo(current);
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
            int original = indexOfSnapshot(snapshot.setPoints(), waypoint);
            int insertion = merged.size();
            if (original >= 0) {
                for (int i = original + 1; i < snapshot.setPoints().size(); i++) {
                    Object next = snapshot.setPoints().get(i).identity();
                    int index = indexOfIdentity(merged, next);
                    if (index >= 0) {
                        insertion = index;
                        break;
                    }
                }
                if (insertion == merged.size()) {
                    for (int i = original - 1; i >= 0; i--) {
                        Object previous = snapshot.setPoints().get(i).identity();
                        int index = indexOfIdentity(merged, previous);
                        if (index >= 0) {
                            insertion = index + 1;
                            break;
                        }
                    }
                }
            }
            merged.add(Math.max(0, Math.min(insertion, merged.size())), waypoint);
        }
        merged.addAll(additions);
        change(
                () -> {
                    set.clear();
                    set.addAll(merged);
                },
                () -> {
                    set.clear();
                    set.addAll(current);
                },
                save);
    }

    @Override
    public Point create(MapBridge.Context context, int x, int y, int z, String name) throws IOException {
        if (context == null) throw new IllegalStateException("Minimap unavailable");
        MinimapWorld world = displayedWorld();
        WaypointSet set = world == null ? null : world.getCurrentWaypointSet();
        Save save = saver(world, set);
        // x/z are map blocks; place the waypoint where the map will draw it in that block.
        double scale = scale();
        if (scale != 1) {
            x = (int) Math.floor((x + 0.5) * scale);
            z = (int) Math.floor((z + 0.5) * scale);
        }
        Waypoint waypoint = new Waypoint(x, y, z, name, "", WaypointColor.YELLOW);
        change(() -> set.add(waypoint), () -> set.remove(waypoint), save);
        return point(waypoint).withOwner(world, set);
    }

    @Override
    public Point recreate(MapBridge.Context context, Point point) throws IOException {
        if (point == null) throw new IllegalStateException("Waypoint context changed");
        WaypointSet set = (WaypointSet) point.setIdentity();
        Save save = saver(point.worldIdentity(), set);
        Waypoint waypoint = new Waypoint(
                point.x(), point.y(), point.z(), point.name(), point.symbol(), WaypointColor.fromIndex(point.color()));
        waypoint.setInitials(point.initials());
        waypoint.setVisibilityType(point.visibility());
        waypoint.setType(point.type());
        waypoint.setYaw(point.yaw());
        waypoint.setRotation(point.rotation());
        waypoint.setYIncluded(point.yIncluded());
        waypoint.setDisabled(point.disabled());
        waypoint.setPurpose(WaypointPurpose.valueOf(point.purpose()));
        change(() -> set.add(waypoint), () -> set.remove(waypoint), save);
        return point(waypoint).withOwner(point.worldIdentity(), set);
    }

    @Override
    public void removeCreated(MapBridge.Context context, Point point) throws IOException {
        if (context == null || point == null) throw new IllegalStateException("Minimap unavailable");
        WaypointSet set = (WaypointSet) point.setIdentity();
        Save save = saver(point.worldIdentity(), set);
        Waypoint waypoint = (Waypoint) point.identity();
        List<Waypoint> existing = new ArrayList<>();
        set.addTo(existing);
        if (!existing.contains(waypoint) || !point(waypoint).equals(point.withOwner(null, null)))
            throw new IllegalStateException("Waypoint changed");
        change(() -> set.remove(waypoint), () -> set.add(waypoint), save);
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
