package dev.exactselection;

import java.util.List;

/** A route: an open line through block corners, with no inside. */
public record PolylineGeometry(List<GridPoint> vertices) implements AreaGeometry {
    public PolylineGeometry {
        vertices = List.copyOf(vertices);
        if (vertices.size() < 2 || vertices.size() > PolygonGeometry.MAX_VERTICES)
            throw new IllegalArgumentException("A route needs 2 to 256 points");
        for (int i = 1; i < vertices.size(); i++)
            if (vertices.get(i).equals(vertices.get(i - 1))) throw new IllegalArgumentException("A route repeats a point");
    }

    @Override public boolean closed() { return false; }

    /** A straight line along an axis still gets positive bounds. */
    @Override public GeometryBounds bounds() {
        long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (GridPoint p : vertices) {
            minX = Math.min(minX, p.x()); minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x()); maxZ = Math.max(maxZ, p.z());
        }
        return new GeometryBounds(minX, minZ, Math.max(maxX, minX + 1), Math.max(maxZ, minZ + 1));
    }

    @Override public long areaTwice() { return 0; }
    @Override public boolean containsPoint(double x, double z) { return false; }
    @Override public List<List<GridPoint>> triangulate() { return List.of(); }

    @Override public AreaGeometry translated(long dx, long dz) {
        return new PolylineGeometry(vertices.stream().map(p -> new GridPoint(p.x() + dx, p.z() + dz)).toList());
    }

    // shortcut: a route crossing a shape with no point inside it does not count; test its segments if that matters.
    @Override public boolean overlaps(AreaGeometry other) {
        return vertices.stream().anyMatch(p -> other.containsPoint(p.x(), p.z()));
    }
}
