package dev.exactselection;

public record GeometryBounds(long minX, long minZ, long maxX, long maxZ) {
    public GeometryBounds {
        if (minX >= maxX || minZ >= maxZ) throw new IllegalArgumentException("Geometry must have positive bounds");
    }

    /** The blocks these outer edges enclose, both corner blocks included. */
    public SelectionBounds blocks() {
        return new SelectionBounds((int) minX, (int) minZ, (int) (maxX - 1), (int) (maxZ - 1));
    }
    public long width() { return maxX - minX; }
    public long length() { return maxZ - minZ; }
    public double centerX() { return (minX + maxX) / 2.0; }
    public double centerZ() { return (minZ + maxZ) / 2.0; }
    public boolean contains(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    public boolean intersectsPositiveArea(GeometryBounds other) {
        return Math.max(minX, other.minX) < Math.min(maxX, other.maxX)
                && Math.max(minZ, other.minZ) < Math.min(maxZ, other.maxZ);
    }
}
