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
    default double area() { return areaTwice() / 2.0; }
    default boolean containsBlockCenter(int x, int z) { return containsPoint(x + 0.5, z + 0.5); }
}
