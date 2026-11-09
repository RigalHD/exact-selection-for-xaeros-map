package dev.exactselection;

import static dev.exactselection.ModLog.LOG;

import java.util.List;
import java.util.function.Supplier;

/**
 * Keeps an unsupported Xaero's Minimap version from crashing the game: the bridge links against Minimap internals,
 * so a renamed class or method surfaces as a LinkageError on first use. After one, waypoint features report
 * "unavailable" (null snapshots) and actions fail with a normal exception that the controller already shows.
 */
final class SafeWaypointAccess implements WaypointAccess {
    private final WaypointAccess delegate;
    private boolean broken;

    private SafeWaypointAccess(WaypointAccess delegate) {
        this.delegate = delegate;
    }

    /** The Minimap bridge, or null when the installed Minimap cannot even load it. */
    static WaypointAccess create(Supplier<WaypointAccess> bridge) {
        try {
            return new SafeWaypointAccess(bridge.get());
        } catch (LinkageError unsupported) {
            log(unsupported);
            return null;
        }
    }

    private static void log(LinkageError error) {
        LOG.warn("This Xaero's Minimap version is not supported; waypoint features are turned off", error);
    }

    private <T> T query(Supplier<T> call, T fallback) {
        if (broken) return fallback;
        try {
            return call.get();
        } catch (LinkageError error) {
            broken = true;
            log(error);
            return fallback;
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private interface Result<T> {
        T get() throws Exception;
    }

    private <T> T action(Result<T> call) throws Exception {
        if (broken) throw new IllegalStateException("Unsupported Xaero's Minimap version");
        try {
            return call.get();
        } catch (LinkageError error) {
            broken = true;
            log(error);
            throw new IllegalStateException("Unsupported Xaero's Minimap version", error);
        }
    }

    private void action(Action call) throws Exception {
        action(() -> {
            call.run();
            return null;
        });
    }

    @Override
    public Snapshot select(MapBridge.Context context, AreaGeometry geometry) {
        return query(() -> delegate.select(context, geometry), null);
    }

    @Override
    public Snapshot all(MapBridge.Context context) {
        return query(() -> delegate.all(context), null);
    }

    @Override
    public double dimensionScale() {
        return query(delegate::dimensionScale, 1.0);
    }

    @Override
    public int colorRgb(int index) {
        return query(() -> delegate.colorRgb(index), 0xFFFFFF);
    }

    @Override
    public Snapshot captureSet(Snapshot snapshot) {
        return query(() -> delegate.captureSet(snapshot), snapshot);
    }

    @Override
    public void edit(Snapshot snapshot, List<Point> from, List<Point> to) throws Exception {
        action(() -> delegate.edit(snapshot, from, to));
    }

    @Override
    public void delete(Snapshot snapshot) throws Exception {
        action(() -> delegate.delete(snapshot));
    }

    @Override
    public void restore(Snapshot snapshot) throws Exception {
        action(() -> delegate.restore(snapshot));
    }

    @Override
    public Point create(MapBridge.Context context, int x, int y, int z, String name) throws Exception {
        return action(() -> delegate.create(context, x, y, z, name));
    }

    @Override
    public Point recreate(MapBridge.Context context, Point point) throws Exception {
        return action(() -> delegate.recreate(context, point));
    }

    @Override
    public void removeCreated(MapBridge.Context context, Point point) throws Exception {
        action(() -> delegate.removeCreated(context, point));
    }
}
