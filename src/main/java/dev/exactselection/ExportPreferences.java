package dev.exactselection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static dev.exactselection.ModLog.LOG;

/** The PNG export window's last choices, shared by all worlds: folder, day or night, scale, areas, labels and the exported area's own fill. */
final class ExportPreferences {
    static final int[] SCALES = {1, 2, 4, 8};
    private final Path file;
    /** Empty means Xaero's own export folder. */
    String folder = "";
    boolean night;
    int scale = 4;
    boolean labels = true;
    boolean areas = true;
    boolean ownFill = true;

    private ExportPreferences(Path file) { this.file = file; }

    static ExportPreferences load(Path root) {
        ExportPreferences preferences = new ExportPreferences(ModFiles.directory(root).resolve("export.json"));
        if (!Files.exists(preferences.file)) return preferences;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(preferences.file, StandardCharsets.UTF_8)).getAsJsonObject();
            preferences.folder = json.has("folder") ? json.get("folder").getAsString() : "";
            preferences.night = json.has("night") && json.get("night").getAsBoolean();
            int scale = json.has("scale") ? json.get("scale").getAsInt() : 4;
            preferences.scale = Arrays.stream(SCALES).anyMatch(s -> s == scale) ? scale : 4;
            preferences.labels = !json.has("labels") || json.get("labels").getAsBoolean();
            preferences.areas = !json.has("areas") || json.get("areas").getAsBoolean();
            preferences.ownFill = !json.has("ownFill") || json.get("ownFill").getAsBoolean();
        } catch (Exception e) {
            LOG.warn("Could not read export preferences; using defaults", e);
        }
        return preferences;
    }

    void save() {
        JsonObject json = new JsonObject();
        json.addProperty("folder", folder);
        json.addProperty("night", night);
        json.addProperty("scale", scale);
        json.addProperty("labels", labels);
        json.addProperty("areas", areas);
        json.addProperty("ownFill", ownFill);
        try {
            SavedAreaStore.writeAtomically(file, json.toString());
        } catch (IOException e) {
            LOG.warn("Could not save export preferences", e);
        }
    }
}
