package dev.exactselection;

/** Both corner blocks are included. X is width, Z is length. */
public record SelectionBounds(int x1, int z1, int x2, int z2) {
    public int minX() {
        return Math.min(x1, x2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    public long width() {
        return (long) maxX() - minX() + 1;
    }

    public long length() {
        return (long) maxZ() - minZ() + 1;
    }

    public long area() {
        return Math.multiplyExact(width(), length());
    }

    public double centerX() {
        return ((long) minX() + maxX() + 1) / 2.0;
    }

    public double centerZ() {
        return ((long) minZ() + maxZ() + 1) / 2.0;
    }

    public boolean contains(int x, int z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    public SelectionBounds translated(long dx, long dz) {
        long left = (long) minX() + dx;
        long right = (long) maxX() + dx;
        long top = (long) minZ() + dz;
        long bottom = (long) maxZ() + dz;
        checkBlock(left);
        checkBlock(right);
        checkBlock(top);
        checkBlock(bottom);
        return new SelectionBounds((int) left, (int) top, (int) right, (int) bottom);
    }

    public SelectionBounds expandedToChunks() {
        int left = Math.floorDiv(minX(), 16) * 16;
        int top = Math.floorDiv(minZ(), 16) * 16;
        int right = Math.floorDiv(maxX(), 16) * 16 + 15;
        int bottom = Math.floorDiv(maxZ(), 16) * 16 + 15;
        checkBlock(left);
        checkBlock(top);
        checkBlock(right);
        checkBlock(bottom);
        return new SelectionBounds(left, top, right, bottom);
    }

    public long chunkCount() {
        long chunksX = (long) Math.floorDiv(maxX(), 16) - Math.floorDiv(minX(), 16) + 1;
        long chunksZ = (long) Math.floorDiv(maxZ(), 16) - Math.floorDiv(minZ(), 16) + 1;
        return Math.multiplyExact(chunksX, chunksZ);
    }

    public static SelectionBounds fromLowerLeft(int x, int z, long width, long length) {
        if (width < 1 || length < 1) throw new IllegalArgumentException("Dimensions must be positive");
        checkBlock(x);
        checkBlock(z);
        long right = (long) x + width - 1;
        long top = (long) z - length + 1;
        checkBlock(right);
        checkBlock(top);
        return new SelectionBounds(x, (int) top, (int) right, z);
    }

    public static SelectionBounds aroundCenter(double x, double z, long width, long length) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || width < 1 || length < 1)
            throw new IllegalArgumentException("Invalid center or dimensions");
        long left = (long) Math.floor(x - width / 2.0);
        long top = (long) Math.floor(z - length / 2.0);
        long right = left + width - 1;
        long bottom = top + length - 1;
        checkBlock(left);
        checkBlock(top);
        checkBlock(right);
        checkBlock(bottom);
        return new SelectionBounds((int) left, (int) top, (int) right, (int) bottom);
    }

    private static void checkBlock(long coordinate) {
        if (coordinate < -WorldLimits.MAX || coordinate >= WorldLimits.MAX)
            throw new IllegalArgumentException("Coordinate outside Minecraft world bounds");
    }

    public static int block(double coordinate) {
        if (!Double.isFinite(coordinate) || coordinate < -WorldLimits.MAX || coordinate >= WorldLimits.MAX)
            throw new IllegalArgumentException("Coordinate outside Minecraft world bounds");
        return (int) Math.floor(coordinate);
    }
}
