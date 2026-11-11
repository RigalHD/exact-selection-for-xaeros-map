package dev.exactselection;

import static dev.exactselection.ModLog.LOG;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;

/**
 * The mod's settings, shared by all worlds and also shown in Mod Menu: which saved-area changes are written immediately instead of
 * waiting for Save, and how copied area and perimeter are rounded.
 */
public final class ModSettings {
    public enum Field {
        NAME,
        GROUP,
        COLOR,
        OPACITY,
        GEOMETRY
    }
    /** How copied area and perimeter are rounded to whole blocks; EXACT keeps their fractions. */
    public enum Rounding {
        DOWN,
        UP,
        EXACT
    }

    private final Path file;
    private boolean enabled;
    private Rounding rounding = Rounding.DOWN;
    private final EnumSet<Field> fields = EnumSet.allOf(Field.class);

    private ModSettings(Path file) {
        this.file = file;
    }

    /** Reads {@code <root>/xaero-exact-selection/settings.json}; a missing or unreadable file gives the defaults. */
    public static ModSettings load(Path root) {
        ModSettings settings = new ModSettings(ModFiles.directory(root).resolve("settings.json"));
        if (!Files.exists(settings.file)) return settings;
        try {
            JsonObject stored = JsonParser.parseString(Files.readString(settings.file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (stored.has("copyRounding")) {
                try {
                    settings.rounding = Rounding.valueOf(
                            stored.get("copyRounding").getAsString().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    /* written by a newer version */
                }
            } else if (stored.has("exactCopy") && stored.get("exactCopy").getAsBoolean()) {
                settings.rounding = Rounding.EXACT; // 1.3.2 stored only an exact-copy switch
            }
            JsonObject json = stored.getAsJsonObject("autosave");
            settings.enabled = json.get("enabled").getAsBoolean();
            settings.fields.clear();
            for (JsonElement field : json.getAsJsonArray("fields")) {
                try {
                    settings.fields.add(Field.valueOf(field.getAsString().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException unknown) {
                    /* written by a newer version */
                }
            }
        } catch (Exception e) {
            settings.enabled = false;
            settings.fields.addAll(EnumSet.allOf(Field.class));
            LOG.warn("Could not read autosave settings; using defaults", e);
        }
        return settings;
    }

    public boolean enabled() {
        return enabled;
    }
    /** Whether {@code field} is part of autosave, regardless of the main switch. */
    public boolean includes(Field field) {
        return fields.contains(field);
    }
    /** Whether changes to {@code field} are written immediately. */
    public boolean covers(Field field) {
        return enabled && fields.contains(field);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public Rounding rounding() {
        return rounding;
    }

    public void setRounding(Rounding rounding) {
        this.rounding = rounding;
        save();
    }

    /** Steps through down, up and exact. */
    public void cycleRounding() {
        setRounding(Rounding.values()[(rounding.ordinal() + 1) % Rounding.values().length]);
    }

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
        root.addProperty("copyRounding", rounding.name().toLowerCase(Locale.ROOT));
        try {
            SavedAreaStore.writeAtomically(file, root.toString());
        } catch (IOException e) {
            // A preference that fails to persist still applies for this session.
            LOG.warn("Could not save autosave settings", e);
        }
    }
}
