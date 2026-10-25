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
                file = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
            }
            String content = Files.readString(file);

            // A directory in place of the temporary file makes the write itself fail before the atomic move.
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectory(temp);
            var second = SavedAreaStore.create("Second", new SelectionBounds(20, 20, 30, 30), 0x654321, 50, null);
            boolean failed = false;
            try { store.replace(new SavedAreaStore.State(List.of(first, second), List.of(), true, false)); }
            catch (IOException expected) { failed = true; }
            require(failed, "Writing over a directory unexpectedly succeeded");
            require(!Files.exists(temp), "A failed write left the temporary file behind");
            require(Files.readString(file).equals(content), "A failed write changed the saved file");
            require(store.state().equals(saved), "A failed write changed the in-memory areas");

            SavedAreaStore reloaded = new SavedAreaStore(directory);
            reloaded.useContext(context);
            require(reloaded.state().equals(saved), "Saved areas did not survive the failed write");
            // An oversized exchange file is refused before it is read into memory (a sparse file, so nothing is written).
            Path huge = directory.resolve("huge.json");
            try (var sparse = new java.io.RandomAccessFile(huge.toFile(), "rw")) { sparse.setLength(AreaTransfer.MAX_FILE_BYTES + 1); }
            try { AreaTransfer.read(huge); throw new AssertionError("An oversized area file was read"); }
            catch (IOException expected) { require(expected.getMessage().contains("too large"), "Oversized file failed for another reason: " + expected); }
            System.out.println("PASS: failed area writes keep the saved file and leave no temporary file; oversized imports are refused");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
