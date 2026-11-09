package dev.exactselection;

public record GridPoint(long x, long z) {
    public GridPoint {
        if (x < -WorldLimits.MAX || x > WorldLimits.MAX || z < -WorldLimits.MAX || z > WorldLimits.MAX)
            throw new IllegalArgumentException("Vertex outside Minecraft world bounds");
    }
}
