package dev.exactselection;

public record GridPoint(long x, long z) {
    public GridPoint {
        if (x < -30_000_000L || x > 30_000_000L || z < -30_000_000L || z > 30_000_000L)
            throw new IllegalArgumentException("Vertex outside Minecraft world bounds");
    }
}
