package dev.exactselection;

import java.util.List;

public sealed interface AreaGeometry permits RectangleGeometry, PolygonGeometry {
    GeometryBounds bounds();
    List<GridPoint> vertices();
    long areaTwice();
    boolean containsPoint(double x, double z);
    AreaGeometry translated(long dx, long dz);
    List<List<GridPoint>> triangulate();

    default double perimeter() {
        List<GridPoint> points = vertices();
        double length = 0;
        for (int i = 0; i < points.size(); i++) {
            GridPoint a = points.get(i), b = points.get((i + 1) % points.size());
            length += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        return length;
    }
    /** True when the shapes share a positive area; shapes that only touch along an edge do not overlap. */
    default boolean overlaps(AreaGeometry other) {
        if (!bounds().intersectsPositiveArea(other.bounds())) return false;
        if (this instanceof RectangleGeometry && other instanceof RectangleGeometry) return true;
        java.awt.geom.Area shared = new java.awt.geom.Area(outline());
        shared.intersect(new java.awt.geom.Area(other.outline()));
        return !shared.isEmpty();
    }

    default java.awt.geom.Path2D.Double outline() {
        List<GridPoint> points = vertices();
        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_NON_ZERO, points.size());
        path.moveTo(points.get(0).x(), points.get(0).z());
        for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x(), points.get(i).z());
        path.closePath();
        return path;
    }

    default double area() { return areaTwice() / 2.0; }
    default boolean containsBlockCenter(int x, int z) { return containsPoint(x + 0.5, z + 0.5); }
}
