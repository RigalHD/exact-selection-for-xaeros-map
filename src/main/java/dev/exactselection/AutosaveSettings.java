package dev.exactselection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Locale;
import org.slf4j.LoggerFactory;

/** Which saved-area changes are written immediately instead of waiting for Save; shared by all worlds. */
public final class AutosaveSettings {
    public enum Field { NAME, GROUP, COLOR, OPACITY, GEOMETRY }

    private final Path file;
    private boolean enabled;
    private final EnumSet<Field> fields = EnumSet.allOf(Field.class);

    private AutosaveSettings(Path file) { this.file = file; }

    /** Reads {@code <root>/xaero-exact-selection/settings.json}; a missing or unreadable file gives the defaults. */
    public static AutosaveSettings load(Path root) {
        AutosaveSettings settings = new AutosaveSettings(root.resolve("xaero-exact-selection").resolve("settings.json"));
        if (!Files.exists(settings.file)) return settings;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(settings.file, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("autosave");
            settings.enabled = json.get("enabled").getAsBoolean();
            settings.fields.clear();
            for (JsonElement field : json.getAsJsonArray("fields")) {
                try { settings.fields.add(Field.valueOf(field.getAsString().toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException unknown) { /* written by a newer version */ }
            }
        } catch (Exception e) {
            settings.enabled = false;
            settings.fields.addAll(EnumSet.allOf(Field.class));
            LoggerFactory.getLogger("xaero-exact-selection").warn("Could not read autosave settings; using defaults", e);
        }
        return settings;
    }

    public boolean enabled() { return enabled; }
    /** Whether {@code field} is part of autosave, regardless of the main switch. */
    public boolean includes(Field field) { return fields.contains(field); }
    /** Whether changes to {@code field} are written immediately. */
    public boolean covers(Field field) { return enabled && fields.contains(field); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; save(); }

    public void toggle(Field field) {
        if (!fields.remove(field)) fields.add(field);
        save();
    }

    private void save() {
        JsonObject autosave = new JsonObject();
        autosave.addProperty("enabled", enabled);
        JsonArray list = new JsonArray();
        for (Field field : fields) list.add(field.name().toLowerCase(Locale.ROOT));
        autosave.add("fields", list);
        JsonObject root = new JsonObject();
        root.add("autosave", autosave);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try {
                Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) {
            // A preference that fails to persist still applies for this session.
            LoggerFactory.getLogger("xaero-exact-selection").warn("Could not save autosave settings", e);
        }
    }
}
