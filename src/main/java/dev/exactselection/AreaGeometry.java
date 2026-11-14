package dev.exactselection;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.List;

/**
 * The shape of a selection or a saved area: a rectangle, a polygon or a route. Vertices are block corners, in drawing
 * order.
 */
public sealed interface AreaGeometry permits RectangleGeometry, PolygonGeometry, PolylineGeometry {
    GeometryBounds bounds();

    List<GridPoint> vertices();

    long areaTwice();

    boolean containsPoint(double x, double z);

    AreaGeometry translated(long dx, long dz);

    List<List<GridPoint>> triangulate();
    /** False for a route: its last vertex does not join the first, and it has no inside. */
    default boolean closed() {
        return true;
    }

    default double perimeter() {
        List<GridPoint> points = vertices();
        double length = 0;
        for (int i = 0; i < (closed() ? points.size() : points.size() - 1); i++) {
            GridPoint a = points.get(i), b = points.get((i + 1) % points.size());
            length += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        return length;
    }
    /** True when the shapes share a positive area; shapes that only touch along an edge do not overlap. */
    default boolean overlaps(AreaGeometry other) {
        if (!bounds().intersectsPositiveArea(other.bounds())) return false;
        if (this instanceof RectangleGeometry && other instanceof RectangleGeometry) return true;
        Area shared = new Area(outline());
        shared.intersect(new Area(other.outline()));
        return !shared.isEmpty();
    }

    /** The area shared with another shape; a route has no inside and shares none. */
    default double sharedArea(AreaGeometry other) {
        if (!closed() || !other.closed() || !bounds().intersectsPositiveArea(other.bounds())) return 0;
        Area shared = new Area(outline());
        shared.intersect(new Area(other.outline()));
        // Simple polygons share no holes, so each outline adds its area. Measuring from its first point keeps
        // precision far from the world's origin.
        double total = 0, piece = 0, startX = 0, startZ = 0, lastX = 0, lastZ = 0;
        double[] point = new double[6];
        for (PathIterator it = shared.getPathIterator(null); !it.isDone(); it.next()) {
            int kind = it.currentSegment(point);
            if (kind == PathIterator.SEG_MOVETO) {
                total += Math.abs(piece) / 2;
                piece = lastX = lastZ = 0;
                startX = point[0];
                startZ = point[1];
            } else if (kind == PathIterator.SEG_LINETO) {
                double x = point[0] - startX, z = point[1] - startZ;
                piece += lastX * z - x * lastZ;
                lastX = x;
                lastZ = z;
            }
        }
        total += Math.abs(piece) / 2;
        return Math.abs(total - Math.rint(total)) < 1e-6 ? Math.rint(total) : total;
    }

    default Path2D.Double outline() {
        List<GridPoint> points = vertices();
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO, points.size());
        path.moveTo(points.get(0).x(), points.get(0).z());
        for (int i = 1; i < points.size(); i++)
            path.lineTo(points.get(i).x(), points.get(i).z());
        path.closePath();
        return path;
    }

    default double area() {
        return areaTwice() / 2.0;
    }

    default boolean containsBlockCenter(int x, int z) {
        return containsPoint(x + 0.5, z + 0.5);
    }
}
