package dev.exactselection;

import static dev.exactselection.ModLog.LOG;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the interface remembers between game sessions, shared by all worlds: the panel's view, the units, the style
 * of the next saved area and the choices of the Size and Move windows.
 */
final class UiState {
    /** Null keeps the state in memory only, as the checks need: they share one folder between controllers. */
    private final Path file;

    boolean compactPanel = true;
    boolean chunks;
    int newAreaColor = AreaPalette.DEFAULT;
    int newAreaOpacity = 25;
    boolean sizeFromCenter;
    boolean moveInChunks;

    private UiState(Path file) {
        this.file = file;
    }

    /** A state that is never read from or written to disk. */
    static UiState inMemory() {
        return new UiState(null);
    }

    /** Reads {@code <root>/xaero-exact-selection/ui.json}; a missing or unreadable file gives the defaults. */
    static UiState load(Path root) {
        UiState state = new UiState(ModFiles.directory(root).resolve("ui.json"));
        if (!Files.exists(state.file)) return state;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(state.file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (json.has("compactPanel"))
                state.compactPanel = json.get("compactPanel").getAsBoolean();
            if (json.has("chunks")) state.chunks = json.get("chunks").getAsBoolean();
            if (json.has("newAreaColor"))
                state.newAreaColor = json.get("newAreaColor").getAsInt() & 0xFFFFFF;
            if (json.has("newAreaOpacity"))
                state.newAreaOpacity =
                        Math.max(0, Math.min(100, json.get("newAreaOpacity").getAsInt()));
            if (json.has("sizeFromCenter"))
                state.sizeFromCenter = json.get("sizeFromCenter").getAsBoolean();
            if (json.has("moveInChunks"))
                state.moveInChunks = json.get("moveInChunks").getAsBoolean();
        } catch (Exception e) {
            LOG.warn("Could not read the interface state; using defaults", e);
        }
        return state;
    }

    void save() {
        if (file == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("compactPanel", compactPanel);
        json.addProperty("chunks", chunks);
        json.addProperty("newAreaColor", newAreaColor);
        json.addProperty("newAreaOpacity", newAreaOpacity);
        json.addProperty("sizeFromCenter", sizeFromCenter);
        json.addProperty("moveInChunks", moveInChunks);
        try {
            SavedAreaStore.writeAtomically(file, json.toString());
        } catch (IOException e) {
            LOG.warn("Could not save the interface state", e);
        }
    }
}
