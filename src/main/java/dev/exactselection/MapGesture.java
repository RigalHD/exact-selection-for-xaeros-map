package dev.exactselection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The one map gesture in progress: a held rectangle handle drag, Shift-move or polygon vertex drag, or a two-click
 * selection waiting for its second corner. The controller holds at most one. {@link #finish} and {@link #cancel}
 * first release the gesture from the controller and do nothing when it is no longer the current one, so a gesture
 * that a nested disable() or reset() already dropped cannot be committed or restored from stale data.
 */
abstract sealed class MapGesture {
    /** The controller side of a gesture. */
    interface Host {
        MapGesture gesture();

        void clearGesture();

        SelectionState state();
        /** The live polygon draft. */
        List<GridPoint> draft();

        boolean chunks();
        /** Records {@code before} to the current selection under {@code historyKey} and refreshes the panel. */
        void commit(SelectionState.Snapshot before, String historyKey);

        void recordDraftChange(List<GridPoint> before);
        /** The selection changed under a held gesture: resync coordinate fields and selected waypoints. */
        void selectionMoved();
        /** Resyncs the panel after a cancel restored the selection. */
        void refresh();

        void message(String key);
        /** The point of a saved area the pointer sticks to, or null. */
        GridPoint snap(MapBridge.Frame frame, double x, double y);

        boolean shiftDown();
    }

    enum Handle {
        MOVE(0, 0),
        WEST(-1, 0),
        EAST(1, 0),
        NORTH(0, -1),
        SOUTH(0, 1),
        NORTH_WEST(-1, -1),
        NORTH_EAST(1, -1),
        SOUTH_WEST(-1, 1),
        SOUTH_EAST(1, 1);

        /** -1 when the west (north) edge is dragged, 1 for the east (south) edge, 0 when that axis stays. */
        final int x, z;

        Handle(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    /** The selection when the gesture started: finish records history from it, cancel restores it. */
    final SelectionState.Snapshot before;

    private MapGesture(SelectionState.Snapshot before) {
        this.before = before;
    }

    /** Ends the gesture and keeps its result; false when it had already ended. */
    abstract boolean finish(Host host);

    /** Ends the gesture and restores what it changed; false when it had already ended. */
    boolean cancel(Host host) {
        if (!release(host)) return false;
        host.state().restore(before);
        return true;
    }

    final boolean release(Host host) {
        if (host.gesture() != this) return false;
        host.clearGesture();
        return true;
    }

    /** A gesture that lasts while the left button is held; it follows the pointer until release. */
    abstract static sealed class Held extends MapGesture {
        private Held(SelectionState.Snapshot before) {
            super(before);
        }

        abstract void update(Host host, MapBridge.Frame frame, double mouseX, double mouseY);
    }

    /** A rectangle handle drag, or a Shift-move of the whole rectangle or polygon. */
    static final class Drag extends Held {
        final Handle handle;
        private final int startX, startZ;
        private final double offsetX, offsetZ;

        private Drag(
                SelectionState.Snapshot before, Handle handle, int startX, int startZ, double offsetX, double offsetZ) {
            super(before);
            this.handle = handle;
            this.startX = startX;
            this.startZ = startZ;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        /** The offsets keep a grabbed edge where it was when the press landed a little off it. */
        static Drag start(
                SelectionState.Snapshot before, Handle handle, MapBridge.Frame frame, double mouseX, double mouseY) {
            SelectionBounds b = before.bounds();
            double pointerX = frame.worldX(mouseX), pointerZ = frame.worldZ(mouseY);
            double offsetX = handle.x < 0 ? pointerX - b.minX() : handle.x > 0 ? pointerX - ((long) b.maxX() + 1) : 0;
            double offsetZ = handle.z < 0 ? pointerZ - b.minZ() : handle.z > 0 ? pointerZ - ((long) b.maxZ() + 1) : 0;
            return new Drag(
                    before, handle, SelectionBounds.block(pointerX), SelectionBounds.block(pointerZ), offsetX, offsetZ);
        }

        /** The handle within 7 GUI pixels of the pointer, or null. */
        static Handle handleAt(MapBridge.Frame frame, SelectionBounds b, double x, double y) {
            double left = frame.screenX(b.minX()), right = frame.screenX((long) b.maxX() + 1);
            double top = frame.screenY(b.minZ()), bottom = frame.screenY((long) b.maxZ() + 1);
            double midX = (left + right) / 2, midY = (top + bottom) / 2;
            double[][] pts = {
                {left, top},
                {midX, top},
                {right, top},
                {left, midY},
                {right, midY},
                {left, bottom},
                {midX, bottom},
                {right, bottom}
            };
            Handle[] kinds = {
                Handle.NORTH_WEST,
                Handle.NORTH,
                Handle.NORTH_EAST,
                Handle.WEST,
                Handle.EAST,
                Handle.SOUTH_WEST,
                Handle.SOUTH,
                Handle.SOUTH_EAST
            };
            for (int i = 0; i < pts.length; i++)
                if (Math.abs(x - pts[i][0]) <= 7 && Math.abs(y - pts[i][1]) <= 7) return kinds[i];
            return null;
        }

        @Override
        void update(Host host, MapBridge.Frame frame, double mouseX, double mouseY) {
            if (host.gesture() != this) return;
            boolean chunks = host.chunks();
            AreaGeometry startGeometry = before.geometry();
            try {
                double pointerX = frame.worldX(mouseX) - offsetX;
                double pointerZ = frame.worldZ(mouseY) - offsetZ;
                int x = SelectionBounds.block(frame.worldX(mouseX)), z = SelectionBounds.block(frame.worldZ(mouseY));
                int dx = x - startX, dz = z - startZ;
                SelectionBounds b = before.bounds();
                if (chunks && handle == Handle.MOVE && !(startGeometry instanceof PolygonGeometry)) {
                    dx = (int) (Math.round(dx / 16.0) * 16);
                    dz = (int) (Math.round(dz / 16.0) * 16);
                }
                // A moved shape sticks by the first of its corners that comes within reach of a saved area.
                if (handle == Handle.MOVE)
                    for (GridPoint corner : startGeometry.vertices()) {
                        GridPoint stuck =
                                host.snap(frame, frame.screenX(corner.x() + dx), frame.screenY(corner.z() + dz));
                        if (stuck != null) {
                            dx = Math.toIntExact(stuck.x() - corner.x());
                            dz = Math.toIntExact(stuck.z() - corner.z());
                            break;
                        }
                    }
                if (handle == Handle.MOVE && startGeometry instanceof PolygonGeometry) {
                    host.state().setGeometry(startGeometry.translated(dx, dz));
                    host.selectionMoved();
                    return;
                }
                GridPoint stuck = handle == Handle.MOVE
                        ? null
                        : host.snap(frame, frame.screenX(pointerX), frame.screenY(pointerZ));
                if (stuck != null) {
                    if (handle.x != 0) pointerX = stuck.x();
                    if (handle.z != 0) pointerZ = stuck.z();
                }
                long minX = b.minX(), maxX = b.maxX(), minZ = b.minZ(), maxZ = b.maxZ();
                if (handle == Handle.MOVE) {
                    minX += dx;
                    maxX += dx;
                    minZ += dz;
                    maxZ += dz;
                }
                Edges xs = handle.x < 0
                        ? dragEdge(pointerX, (long) b.maxX() + 1, true, chunks)
                        : handle.x > 0 ? dragEdge(pointerX, b.minX(), false, chunks) : null;
                Edges zs = handle.z < 0
                        ? dragEdge(pointerZ, (long) b.maxZ() + 1, true, chunks)
                        : handle.z > 0 ? dragEdge(pointerZ, b.minZ(), false, chunks) : null;
                if (xs != null) {
                    minX = xs.low();
                    maxX = xs.high() - 1;
                }
                if (zs != null) {
                    minZ = zs.low();
                    maxZ = zs.high() - 1;
                }
                if (minX < -WorldLimits.MAX
                        || minZ < -WorldLimits.MAX
                        || maxX >= WorldLimits.MAX
                        || maxZ >= WorldLimits.MAX) return;
                host.state().set(new SelectionBounds((int) minX, (int) minZ, (int) maxX, (int) maxZ));
                host.selectionMoved();
            } catch (IllegalArgumentException ignored) {
            }
        }

        @Override
        boolean finish(Host host) {
            if (!release(host)) return false;
            host.commit(before, "drag");
            return true;
        }

        /**
         * Outer edges [low, high) after one side is dragged to {@code pointer} while the opposite edge stays at
         * {@code fixed}. Working with outer edges keeps the size right when the dragged side crosses the fixed one:
         * the block under the pointer is always included, and chunk mode keeps whole 16-block chunks.
         */
        private static Edges dragEdge(double pointer, long fixed, boolean lowSide, boolean chunks) {
            long low = lowerBoundary(pointer, chunks), high = upperBoundary(pointer, chunks), unit = chunks ? 16 : 1;
            if (lowSide) return low < fixed ? new Edges(low, fixed) : new Edges(fixed, Math.max(high, fixed + unit));
            return high > fixed ? new Edges(fixed, high) : new Edges(Math.min(low, fixed - unit), fixed);
        }

        /**
         * The outer edges of a rectangle along one axis: the blocks from {@code low} up to, not including, {@code
         * high}.
         */
        private record Edges(long low, long high) {}

        private static long lowerBoundary(double pointer, boolean chunks) {
            int block = SelectionBounds.block(pointer);
            return chunks ? Math.floorDiv(block, 16) * 16L : block;
        }

        private static long upperBoundary(double pointer, boolean chunks) {
            int block = blockBeforeBoundary(pointer);
            return chunks ? Math.floorDiv(block, 16) * 16L + 16 : block + 1L;
        }

        private static int blockBeforeBoundary(double coordinate) {
            if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Invalid block coordinate");
            double nearest = Math.rint(coordinate);
            if (Math.abs(coordinate - nearest) < 1.0E-6) coordinate = nearest;
            long block = (long) Math.ceil(coordinate) - 1;
            return SelectionBounds.block(block);
        }
    }

    /** A Ctrl/Cmd drag of one vertex of the polygon draft or of the closed polygon selection. */
    static final class VertexDrag extends Held {
        final int index;
        private final long pointerX, pointerZ;
        /** The draft before the drag, or null when the closed polygon in {@link #before} is edited. */
        private final List<GridPoint> draftBefore;
        /** The last pointer position gave a duplicate vertex or an invalid polygon. */
        boolean invalid;

        VertexDrag(
                SelectionState.Snapshot before, int index, long pointerX, long pointerZ, List<GridPoint> draftBefore) {
            super(before);
            this.index = index;
            this.pointerX = pointerX;
            this.pointerZ = pointerZ;
            this.draftBefore = draftBefore;
        }

        @Override
        void update(Host host, MapBridge.Frame frame, double x, double y) {
            if (host.gesture() != this) return;
            List<GridPoint> original =
                    draftBefore != null ? draftBefore : before.geometry().vertices();
            try {
                GridPoint start = original.get(index);
                GridPoint free = new GridPoint(
                        start.x() + Math.round(frame.worldX(x)) - pointerX,
                        start.z() + Math.round(frame.worldZ(y)) - pointerZ);
                GridPoint stuck = host.shiftDown() ? null : host.snap(frame, x, y);
                GridPoint point = host.shiftDown() ? aligned(original, free) : stuck != null ? stuck : free;
                if (point.equals(start) && draftBefore == null) {
                    invalid = false;
                    host.state().restore(before);
                    host.selectionMoved();
                    return;
                }
                List<GridPoint> moved = new ArrayList<>(original);
                moved.set(index, point);
                if (new HashSet<>(moved).size() != moved.size()) throw new IllegalArgumentException();
                if (draftBefore != null) {
                    host.draft().clear();
                    host.draft().addAll(moved);
                } else {
                    PolygonGeometry next = new PolygonGeometry(moved);
                    next.triangulate();
                    host.state().setGeometry(next);
                    host.selectionMoved();
                }
                invalid = false;
                host.message("polygon_vertex_drag");
            } catch (IllegalArgumentException | IllegalStateException e) {
                invalid = true;
                host.message("polygon_vertex_invalid");
            }
        }

        /** Shift straightens a side: the point on the nearest axis line through a neighbouring vertex. */
        private GridPoint aligned(List<GridPoint> points, GridPoint free) {
            GridPoint best = free;
            long reach = Long.MAX_VALUE;
            for (int i : new int[] {index - 1, index + 1}) {
                // A contour still being drawn has no side from its last vertex back to the first.
                if (draftBefore != null && (i < 0 || i >= points.size())) continue;
                GridPoint neighbour = points.get(Math.floorMod(i, points.size()));
                long dx = Math.abs(free.x() - neighbour.x()), dz = Math.abs(free.z() - neighbour.z());
                if (dx < reach) {
                    reach = dx;
                    best = new GridPoint(neighbour.x(), free.z());
                }
                if (dz < reach) {
                    reach = dz;
                    best = new GridPoint(free.x(), neighbour.z());
                }
            }
            return best;
        }

        @Override
        boolean finish(Host host) {
            if (invalid) {
                if (!cancel(host)) return false;
                host.refresh();
                host.message("polygon_vertex_rejected");
            } else {
                if (!release(host)) return false;
                if (draftBefore == null) host.commit(before, "vertex");
                else if (!draftBefore.equals(host.draft())) host.recordDraftChange(draftBefore);
                host.message("polygon_vertex_moved");
            }
            return true;
        }

        @Override
        boolean cancel(Host host) {
            if (!release(host)) return false;
            if (draftBefore != null) {
                host.draft().clear();
                host.draft().addAll(draftBefore);
            } else host.state().restore(before);
            return true;
        }
    }

    /** The first click of a two-click selection; the PREVIEW phase follows the pointer until the second click. */
    static final class Corners extends MapGesture {
        /** The first-corner block; chunk mode anchors the preview to its chunk. */
        final int originX, originZ;

        Corners(SelectionState.Snapshot before, int originX, int originZ) {
            super(before);
            this.originX = originX;
            this.originZ = originZ;
        }

        @Override
        boolean finish(Host host) {
            if (!release(host)) return false;
            host.commit(before, "select");
            return true;
        }
    }
}
