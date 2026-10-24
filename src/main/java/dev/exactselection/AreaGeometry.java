package dev.exactselection;

import java.util.List;

public sealed interface AreaGeometry permits RectangleGeometry, PolygonGeometry {
    GeometryBounds bounds();
    List<GridPoint> vertices();
    long areaTwice();
    boolean containsPoint(double x, double z);
    AreaGeometry translated(long dx, long dz);
    List<List<GridPoint>> triangulate();

    default double area() { return areaTwice() / 2.0; }
    default boolean containsBlockCenter(int x, int z) { return containsPoint(x + 0.5, z + 0.5); }
}
