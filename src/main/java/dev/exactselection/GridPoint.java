package dev.exactselection;

/**
 * A block corner of the world: the point where four blocks meet. Polygon vertices and route points are block corners.
 */
public record GridPoint(long x, long z) {
    public GridPoint {
        if (x < -WorldLimits.MAX || x > WorldLimits.MAX || z < -WorldLimits.MAX || z > WorldLimits.MAX)
            throw new IllegalArgumentException("Vertex outside Minecraft world bounds");
    }
}
