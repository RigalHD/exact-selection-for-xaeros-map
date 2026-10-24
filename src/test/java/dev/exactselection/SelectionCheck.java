package dev.exactselection;

public final class SelectionCheck {
    public static void main(String[] args) {
        var single = new SelectionBounds(10, 20, 10, 20);
        assert single.width() == 1 && single.length() == 1 && single.area() == 1;
        var chunk = new SelectionBounds(0, 0, 15, 15);
        assert chunk.width() == 16 && chunk.length() == 16 && chunk.area() == 256;
        var negative = new SelectionBounds(-2, -3, 2, 3);
        var reverse = new SelectionBounds(2, 3, -2, -3);
        assert negative.width() == 5 && negative.length() == 7 && negative.area() == 35;
        assert reverse.area() == negative.area();
        assert negative.contains(-2, -3) && negative.contains(2, 3);
        assert !negative.contains(3, 3) && !negative.contains(-2, -4);
        assert new SelectionBounds(0, 0, 99_999, 99_999).area() == 10_000_000_000L;
        assert new SelectionBounds(-30_000_000, -30_000_000, 29_999_999, 29_999_999).area() == 3_600_000_000_000_000L;
        var reverseChunk = new SelectionBounds(ChunkSelection.anchor(180, 160), ChunkSelection.anchor(80, 60),
                ChunkSelection.endpoint(160, 180), ChunkSelection.endpoint(60, 80));
        assert reverseChunk.equals(new SelectionBounds(191, 95, 160, 48));
        assert reverseChunk.chunkCount() == 6 && reverseChunk.area() == 32L * 48;
        var forwardChunk = new SelectionBounds(ChunkSelection.anchor(-17, -1), ChunkSelection.anchor(16, 31),
                ChunkSelection.endpoint(-1, -17), ChunkSelection.endpoint(31, 16));
        assert forwardChunk.equals(new SelectionBounds(-32, 16, -1, 31));
        var sameChunk = new SelectionBounds(ChunkSelection.anchor(33, 47), ChunkSelection.anchor(-1, -14),
                ChunkSelection.endpoint(47, 33), ChunkSelection.endpoint(-14, -1));
        assert sameChunk.equals(new SelectionBounds(32, -16, 47, -1));
        var cachedArea = SavedAreaStore.create("Cached", new PolygonGeometry(java.util.List.of(
                new GridPoint(0, 0), new GridPoint(16, 0), new GridPoint(16, 16), new GridPoint(0, 16))),
                0x59E3CE, 30, null);
        assert cachedArea.geometry() == cachedArea.geometry();
        assert SelectionBounds.block(-0.2) == -1 && SelectionBounds.block(0.2) == 0;
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -30_000_001, 30_000_000}) {
            try { SelectionBounds.block(bad); throw new AssertionError("Accepted " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        var state = new SelectionState();
        state.click(1, 2);
        assert state.phase() == SelectionState.Phase.OFF && state.bounds() == null;
        state.enable(); state.click(1, 2); state.hover(-5, -8);
        assert state.phase() == SelectionState.Phase.PREVIEW && state.bounds().x2() == -5;
        state.click(-5, -8); state.hover(99, 99);
        assert state.phase() == SelectionState.Phase.SELECTED && state.bounds().x2() == -5;
        state.click(6, 7);
        assert state.phase() == SelectionState.Phase.PREVIEW && state.bounds().area() == 1;
        state.escape();
        assert state.phase() == SelectionState.Phase.READY && state.bounds() == null;
        state.escape(); assert !state.enabled();
        // Same block at different zoom levels, odd framebuffer dimensions and GUI scales.
        for (double guiScale : new double[]{1, 2, 3, 4}) {
            for (double zoom : new double[]{0.0625, 0.5, 1, 3.25, 50}) {
                var frame = new MapBridge.Frame(null, -17.25, 250.75, zoom, guiScale, 1921, 1081);
                for (double x : new double[]{-29999999.5, -0.2, 0.5, 128.5, 29999999.5}) {
                    double restoredX = frame.worldX(frame.screenX(x));
                    double restoredZ = frame.worldZ(frame.screenY(x));
                    assert Math.abs(restoredX - x) < 0.00001;
                    assert Math.abs(restoredZ - x) < 0.00001;
                    assert SelectionBounds.block(restoredX) == SelectionBounds.block(x);
                }
            }
        }
        assert new RectangleGeometry(new SelectionBounds(0, 0, 2, 5)).perimeter() == 18.0;
        var triangle = new PolygonGeometry(java.util.List.of(new GridPoint(0, 0), new GridPoint(3, 0), new GridPoint(0, 4)));
        assert triangle.perimeter() == 12.0;
        assert AreaPalette.COLORS.length == 24;
        assert AreaPalette.parse("#abc") == 0xAABBCC;
        assert AreaPalette.parse("59E3CE") == AreaPalette.DEFAULT;
        assert AreaPalette.parse("garbage") == -1;
        assert AreaPalette.parse(null) == -1;
        state.load(triangle, "saved-area");
        var linked = state.snapshot();
        state.set(new SelectionBounds(1, 2, 3, 4));
        assert state.source().equals("saved-area");
        state.setGeometry(triangle);
        assert state.source().equals("saved-area");
        state.reset(); assert state.source() == null;
        state.restore(linked); assert state.source().equals("saved-area");
        state.disable(); assert state.source() == null;
        // Xaero draws a waypoint in floor(coordinate / dimDiv), e.g. Overworld waypoints on a Nether map (dimDiv 8).
        assert WaypointAccess.mapBlock(15, 1) == 15 && WaypointAccess.mapBlock(15, 8) == 1;
        assert WaypointAccess.mapBlock(-1, 8) == -1 && WaypointAccess.mapBlock(-9, 8) == -2 && WaypointAccess.mapBlock(4, 0.125) == 32;
        System.out.println("PASS: inclusive bounds, negative coordinates, long area, state transitions, map transforms");
    }
}
