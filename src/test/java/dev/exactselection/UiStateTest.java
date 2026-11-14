package dev.exactselection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the interface remembers must survive a restart of the game: it is written to disk and read back. */
class UiStateTest {
    @Test
    void savedStateIsReadBackAfterARestart(@TempDir Path config) {
        UiState before = UiState.load(config);
        before.compactPanel = false;
        before.chunks = true;
        before.newAreaColor = 0x33AA66;
        before.newAreaOpacity = 75;
        before.sizeFromCenter = true;
        before.moveInChunks = true;
        before.save();

        UiState after = UiState.load(config);
        assertFalse(after.compactPanel);
        assertTrue(after.chunks);
        assertEquals(0x33AA66, after.newAreaColor);
        assertEquals(75, after.newAreaOpacity);
        assertTrue(after.sizeFromCenter);
        assertTrue(after.moveInChunks);
    }

    @Test
    void aMissingOrDamagedFileGivesTheDefaults(@TempDir Path config) throws Exception {
        UiState fresh = UiState.load(config);
        assertTrue(fresh.compactPanel);
        assertFalse(fresh.chunks);
        assertEquals(AreaPalette.DEFAULT, fresh.newAreaColor);
        assertEquals(25, fresh.newAreaOpacity);

        Path file = ModFiles.directory(config).resolve("ui.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"compactPanel\": fal");
        UiState damaged = UiState.load(config);
        assertTrue(damaged.compactPanel);
        assertEquals(25, damaged.newAreaOpacity);
    }

    @Test
    void valuesOutOfRangeAreBroughtBack(@TempDir Path config) throws Exception {
        Path file = ModFiles.directory(config).resolve("ui.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"newAreaOpacity\": 400, \"newAreaColor\": -1}");
        UiState state = UiState.load(config);
        assertEquals(100, state.newAreaOpacity);
        assertEquals(0xFFFFFF, state.newAreaColor);
    }
}
