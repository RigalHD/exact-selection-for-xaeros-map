package dev.exactselection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class SavedAreaStoreCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** A file from a newer version of the mod is intact: it offers no restore, and a forced one changes nothing. */
    private static void checkRestoreOfANewerFile(Path directory) throws Exception {
        MapBridge.Context context = new MapBridge.Context(new Object(), "newer-check", null, "main");
        SavedAreaStore store = new SavedAreaStore(directory);
        store.useContext(context);
        var area = SavedAreaStore.create("Area", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
        store.replace(new SavedAreaStore.State(List.of(area), List.of()));
        store.replace(new SavedAreaStore.State(List.of(area, area.withId("second")), List.of()));
        Path file;
        try (var files = Files.list(directory.resolve("xaero-exact-selection"))) {
            file = files.filter(path -> path.toString().endsWith(".json") && readsAs(path, "newer-check"))
                    .findFirst()
                    .orElseThrow();
        }
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        String newest = Files.readString(file).replace("\"schema\":3", "\"schema\":4");
        Files.writeString(file, newest);
        Files.writeString(backup, Files.readString(backup).replace("\"schema\":3", "\"schema\":4"));
        SavedAreaStore newer = new SavedAreaStore(directory);
        try {
            newer.useContext(context);
            throw new AssertionError("A file of a newer schema was read");
        } catch (IOException expected) {
            require(
                    newer.loadFailed() && newer.newerFile() && !newer.canRestore(),
                    "A newer file is offered a restore");
        }
        for (int i = 0; i < 2; i++) {
            try {
                newer.restoreBackup();
                throw new AssertionError("An unreadable previous version was restored");
            } catch (IOException expected) {
            }
        }
        require(Files.readString(file).equals(newest), "A restore that could not work changed the area file");
        require(
                !Files.exists(file.resolveSibling(file.getFileName() + ".broken")),
                "A restore that could not work moved the area file");
        // A second damaged file never replaces the first one kept beside it.
        Files.writeString(backup, Files.readString(backup).replace("\"schema\":4", "\"schema\":3"));
        for (String damage : List.of("{first", "{second")) {
            Files.writeString(file, damage);
            newer.reload();
            try {
                newer.useContext(context);
            } catch (IOException expected) {
            }
            newer.restoreBackup();
            newer.useContext(context);
        }
        require(
                Files.readString(file.resolveSibling(file.getFileName() + ".broken"))
                                .equals("{first")
                        && Files.readString(file.resolveSibling(file.getFileName() + ".broken-2"))
                                .equals("{second"),
                "A later damaged file replaced an earlier one");
    }

    private static boolean readsAs(Path file, String world) {
        try {
            return Files.readString(file).contains(world);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * An area is its geometry: a route stays a route, and a polygon is never taken for a route with the same points.
     */
    private static void checkShapes() {
        List<GridPoint> points = List.of(new GridPoint(0, 0), new GridPoint(10, 0), new GridPoint(10, 10));
        var rectangle = SavedAreaStore.create("Shape", new SelectionBounds(0, 0, 5, 5), 1, 25, null);
        var route = rectangle.withGeometry(new PolylineGeometry(points));
        String stored = SavedAreaStore.encode(new SavedAreaStore.State(List.of(route), List.of()))
                .toString();
        require(
                stored.contains("\"polyline\"")
                        && SavedAreaStore.decode(com.google.gson.JsonParser.parseString(stored)
                                                .getAsJsonObject())
                                        .areas()
                                        .get(0)
                                        .geometry()
                                instanceof PolylineGeometry,
                "A route given to an area was stored as another shape");
        var polygon = rectangle.withGeometry(new PolygonGeometry(points));
        require(!polygon.equals(route), "A polygon equals a route through the same points");
        var merged = AreaTransfer.merge(
                new SavedAreaStore.State(List.of(polygon), List.of()),
                new SavedAreaStore.State(List.of(route), List.of()));
        require(
                merged.added() == 1 && merged.skipped() == 0,
                "Importing a route over a polygon with the same points skipped it");
        var again = AreaTransfer.merge(merged.state(), new SavedAreaStore.State(List.of(route), List.of()));
        require(again.added() == 0 && again.skipped() == 1, "Importing the same route twice added a copy");
        // A road through a selection overlaps it even with no point of its own inside.
        var box = new RectangleGeometry(new SelectionBounds(0, 0, 5, 5));
        require(
                new PolylineGeometry(List.of(new GridPoint(-10, 3), new GridPoint(20, 3))).overlaps(box),
                "A route crossing a selection is not in it");
        require(
                !new PolylineGeometry(List.of(new GridPoint(-10, 30), new GridPoint(20, 30))).overlaps(box),
                "A route beside a selection is in it");
        // The export limit counts the whole chunks Xaero renders, as the export itself does.
        var unaligned = new GeometryBounds(1, 1, 2829, 2829);
        require(
                unaligned.width() * unaligned.length() <= AreaExportService.MAX_PIXELS
                        && !AreaExportService.fits(unaligned, 1),
                "An area whose chunks exceed the export limit is offered for export");
        require(
                AreaExportService.fits(new GeometryBounds(0, 0, 2816, 2816), 1),
                "A chunk-aligned area within the limit is refused");
        // Names from someone else's file lose control characters and the formatting sign.
        var styled = new SavedAreaStore.State(List.of(rectangle.withName("\u00A7cRed\n zone")), List.of());
        require(
                AreaTransfer.merge(SavedAreaStore.State.empty(), styled)
                        .state()
                        .areas()
                        .get(0)
                        .name()
                        .equals("cRed zone"),
                "An imported name kept its formatting sign or line break");
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("exact-selection-store-");
        try {
            MapBridge.Context context = new MapBridge.Context(new Object(), "store-check", null, "main");
            SavedAreaStore store = new SavedAreaStore(directory);
            store.useContext(context);
            var first = SavedAreaStore.create("First", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
            SavedAreaStore.State saved = new SavedAreaStore.State(List.of(first), List.of(), true, false);
            store.replace(saved);
            Path file;
            try (var files = Files.list(directory.resolve("xaero-exact-selection"))) {
                file = files.filter(path -> path.toString().endsWith(".json"))
                        .findFirst()
                        .orElseThrow();
            }
            String content = Files.readString(file);

            // A directory in place of the temporary file makes the write itself fail before the atomic move.
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectory(temp);
            var second = SavedAreaStore.create("Second", new SelectionBounds(20, 20, 30, 30), 0x654321, 50, null);
            boolean failed = false;
            try {
                store.replace(new SavedAreaStore.State(List.of(first, second), List.of(), true, false));
            } catch (IOException expected) {
                failed = true;
            }
            require(failed, "Writing over a directory unexpectedly succeeded");
            require(!Files.exists(temp), "A failed write left the temporary file behind");
            require(Files.readString(file).equals(content), "A failed write changed the saved file");
            require(store.state().equals(saved), "A failed write changed the in-memory areas");

            SavedAreaStore reloaded = new SavedAreaStore(directory);
            reloaded.useContext(context);
            require(reloaded.state().equals(saved), "Saved areas did not survive the failed write");
            // The names switch is saved; files written before it existed keep names shown.
            SavedAreaStore.State hidden = new SavedAreaStore.State(saved.areas(), saved.groups(), true, false, false);
            require(
                    !SavedAreaStore.decode(SavedAreaStore.encode(hidden)).showNames(),
                    "Hidden area names were not saved");
            com.google.gson.JsonObject old = SavedAreaStore.encode(hidden);
            old.remove("showNames");
            require(SavedAreaStore.decode(old).showNames(), "An older area file lost its area names");
            // The file names its world, and a damaged file can be replaced by the version before its last write.
            require(
                    Files.readString(file).contains("store-check"),
                    "The area file does not say which world it belongs to");
            store.replace(new SavedAreaStore.State(List.of(first, second), List.of(), true, false));
            Files.writeString(file, "{damaged");
            SavedAreaStore damaged = new SavedAreaStore(directory);
            try {
                damaged.useContext(context);
                throw new AssertionError("A damaged file was read");
            } catch (IOException expected) {
                require(damaged.canRestore(), "A damaged file offers no way back");
            }
            damaged.restoreBackup();
            damaged.useContext(context);
            require(damaged.state().equals(saved) && !damaged.loadFailed(), "The previous version did not come back");
            require(
                    Files.readString(file.resolveSibling(file.getFileName() + ".broken"))
                            .equals("{damaged"),
                    "The damaged file was not kept");
            reloaded.useContext(context);
            require(reloaded.state().equals(saved), "The minimap's store did not see the restored file");
            checkRestoreOfANewerFile(directory);
            checkShapes();
            // An oversized exchange file is refused before it is read into memory (a sparse file, so nothing is
            // written).
            Path huge = directory.resolve("huge.json");
            try (var sparse = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
                sparse.setLength(AreaTransfer.MAX_FILE_BYTES + 1);
            }
            try {
                AreaTransfer.read(huge);
                throw new AssertionError("An oversized area file was read");
            } catch (IOException expected) {
                require(
                        expected.getMessage().contains("too large"),
                        "Oversized file failed for another reason: " + expected);
            }
            System.out.println(
                    "PASS: failed area writes keep the saved file and leave no temporary file; oversized imports are refused");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
