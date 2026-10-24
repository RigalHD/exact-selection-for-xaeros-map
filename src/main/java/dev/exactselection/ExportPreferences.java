package dev.exactselection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.LoggerFactory;

/** The PNG export window's last choices, shared by all worlds: folder, day or night, scale and labels. */
final class ExportPreferences {
    static final int[] SCALES = {1, 2, 4, 8};
    private final Path file;
    /** Empty means Xaero's own export folder. */
    String folder = "";
    boolean night;
    int scale = 4;
    boolean labels = true;

    private ExportPreferences(Path file) { this.file = file; }

    static ExportPreferences load(Path root) {
        ExportPreferences preferences = new ExportPreferences(root.resolve("xaero-exact-selection").resolve("export.json"));
        if (!Files.exists(preferences.file)) return preferences;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(preferences.file, StandardCharsets.UTF_8)).getAsJsonObject();
            preferences.folder = json.has("folder") ? json.get("folder").getAsString() : "";
            preferences.night = json.has("night") && json.get("night").getAsBoolean();
            int scale = json.has("scale") ? json.get("scale").getAsInt() : 4;
            preferences.scale = java.util.Arrays.stream(SCALES).anyMatch(s -> s == scale) ? scale : 4;
            preferences.labels = !json.has("labels") || json.get("labels").getAsBoolean();
        } catch (Exception e) {
            LoggerFactory.getLogger("xaero-exact-selection").warn("Could not read export preferences; using defaults", e);
        }
        return preferences;
    }

    void save() {
        JsonObject json = new JsonObject();
        json.addProperty("folder", folder);
        json.addProperty("night", night);
        json.addProperty("scale", scale);
        json.addProperty("labels", labels);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try {
                Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) {
            LoggerFactory.getLogger("xaero-exact-selection").warn("Could not save export preferences", e);
        }
    }
}
