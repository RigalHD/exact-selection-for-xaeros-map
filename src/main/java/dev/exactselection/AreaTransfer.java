package dev.exactselection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Area exchange files: the saved-area schema with a format marker, written to and read from one folder. */
final class AreaTransfer {
    static final String FORMAT = "xaero-exact-selection-areas";

    record Merge(SavedAreaStore.State state, int added, int skipped, int groupsAdded) {}

    private AreaTransfer() {}

    /** {@code <root>/xaero-exact-selection/exports}: the folder exports go to and imports are listed from. */
    static Path folder(Path root) { return root.resolve("xaero-exact-selection").resolve("exports"); }

    /** Writes {@code areas} and the groups they use; returns the new file. */
    static Path export(Path folder, String label, List<SavedAreaStore.Area> areas, List<SavedAreaStore.Group> groups)
            throws IOException {
        Set<String> used = new HashSet<>();
        for (SavedAreaStore.Area area : areas) if (area.groupId() != null) used.add(area.groupId());
        SavedAreaStore.State state = new SavedAreaStore.State(areas,
                groups.stream().filter(group -> used.contains(group.id())).toList());
        JsonObject json = SavedAreaStore.encode(state);
        json.addProperty("format", FORMAT);
        json.remove("showOnMap"); json.remove("showOnMinimap");
        Files.createDirectories(folder);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        Path file = unique(folder, safe(label) + "-" + stamp);
        Path temp = folder.resolve("." + file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file); }
        } finally { Files.deleteIfExists(temp); }
        return file;
    }

    /** Exchange files in the folder, newest first; an absent folder has none. */
    static List<Path> list(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path path) -> {
                        try { return Files.getLastModifiedTime(path).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }).reversed().thenComparing(Path::toString))
                    .toList();
        }
    }

    /** Reads and validates an exchange file with the same rules as saved-area files. */
    static SavedAreaStore.State read(Path file) throws IOException {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("format") || !FORMAT.equals(json.get("format").getAsString()))
                throw new IOException("Not an Exact Selection area file");
            return SavedAreaStore.decode(json);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid area file", e);
        }
    }

    /**
     * Adds {@code incoming} to {@code current}. Groups are matched by name, missing ones are created; areas get new
     * ids, and an area identical in name, shape, color, fill and group to an existing one is skipped.
     */
    static Merge merge(SavedAreaStore.State current, SavedAreaStore.State incoming) {
        List<SavedAreaStore.Group> groups = new ArrayList<>(current.groups());
        Map<String, String> groupIds = new HashMap<>();
        int groupsAdded = 0;
        for (SavedAreaStore.Group group : incoming.groups()) {
            SavedAreaStore.Group existing = groups.stream().filter(g -> g.name().equals(group.name())).findFirst().orElse(null);
            if (existing == null) {
                existing = new SavedAreaStore.Group(UUID.randomUUID().toString(), group.name(), group.visible());
                groups.add(existing);
                groupsAdded++;
            }
            groupIds.put(group.id(), existing.id());
        }
        List<SavedAreaStore.Area> areas = new ArrayList<>(current.areas());
        int added = 0, skipped = 0;
        for (SavedAreaStore.Area area : incoming.areas()) {
            String groupId = area.groupId() == null ? null : groupIds.get(area.groupId());
            boolean duplicate = areas.stream().anyMatch(a -> a.name().equals(area.name()) && a.color() == area.color()
                    && a.opacity() == area.opacity() && Objects.equals(a.groupId(), groupId)
                    && a.geometry().equals(area.geometry()));
            if (duplicate) { skipped++; continue; }
            areas.add(new SavedAreaStore.Area(UUID.randomUUID().toString(), area.name(), area.x1(), area.z1(),
                    area.x2(), area.z2(), area.color(), area.opacity(), groupId, area.visible(), area.vertices()));
            added++;
        }
        return new Merge(new SavedAreaStore.State(areas, groups, current.showOnMap(), current.showOnMinimap()),
                added, skipped, groupsAdded);
    }

    private static String safe(String label) {
        String cleaned = label.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^_+|_+$", "");
        cleaned = cleaned.isEmpty() ? "areas" : cleaned;
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private static Path unique(Path folder, String base) {
        Path file = folder.resolve(base + ".json");
        for (int i = 2; Files.exists(file); i++) file = folder.resolve(base + "-" + i + ".json");
        return file;
    }
}
