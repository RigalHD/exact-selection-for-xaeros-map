package dev.exactselection;

import java.util.List;

public record RectangleGeometry(SelectionBounds blocks) implements AreaGeometry {
    public RectangleGeometry {
        if (blocks == null) throw new IllegalArgumentException("Rectangle bounds are required");
    }

    @Override
    public GeometryBounds bounds() {
        return new GeometryBounds(blocks.minX(), blocks.minZ(), (long) blocks.maxX() + 1, (long) blocks.maxZ() + 1);
    }

    @Override
    public List<GridPoint> vertices() {
        GeometryBounds b = bounds();
        return List.of(
                new GridPoint(b.minX(), b.minZ()),
                new GridPoint(b.maxX(), b.minZ()),
                new GridPoint(b.maxX(), b.maxZ()),
                new GridPoint(b.minX(), b.maxZ()));
    }

    @Override
    public long areaTwice() {
        return Math.multiplyExact(blocks.area(), 2L);
    }

    @Override
    public boolean containsPoint(double x, double z) {
        return bounds().contains(x, z);
    }

    @Override
    public AreaGeometry translated(long dx, long dz) {
        return new RectangleGeometry(blocks.translated(dx, dz));
    }

    @Override
    public List<List<GridPoint>> triangulate() {
        List<GridPoint> v = vertices();
        return List.of(List.of(v.get(0), v.get(1), v.get(2)), List.of(v.get(0), v.get(2), v.get(3)));
    }
}
