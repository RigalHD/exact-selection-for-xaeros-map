package dev.exactselection;

public final class ChunkSelection {
    private ChunkSelection() {}

    public static int anchor(int originBlock, int cursorBlock) {
        int originChunk = Math.floorDiv(originBlock, 16);
        int cursorChunk = Math.floorDiv(cursorBlock, 16);
        int coordinate = originChunk * 16 + (cursorChunk < originChunk ? 15 : 0);
        return checked(coordinate);
    }

    public static int endpoint(int cursorBlock, int originBlock) {
        int originChunk = Math.floorDiv(originBlock, 16);
        int cursorChunk = Math.floorDiv(cursorBlock, 16);
        int coordinate = cursorChunk < originChunk
                ? cursorChunk * 16
                : cursorChunk * 16 + 15;
        return checked(coordinate);
    }

    private static int checked(int coordinate) {
        if (coordinate < -30_000_000 || coordinate >= 30_000_000)
            throw new IllegalArgumentException("Chunk outside world bounds");
        return coordinate;
    }
}
