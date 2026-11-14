package dev.exactselection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import dev.exactselection.SavedAreaStore.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    record Merge(State state, int added, int skipped, int groupsAdded) {}

    private AreaTransfer() {}

    /** {@code <root>/xaero-exact-selection/exports}: the folder exports go to and imports are listed from. */
    static Path folder(Path root) {
        return ModFiles.directory(root).resolve("exports");
    }

    /** Writes {@code areas} and the groups they use; returns the new file. */
    static Path export(Path folder, String label, List<Area> areas, List<Group> groups) throws IOException {
        Set<String> used = new HashSet<>();
        for (Area area : areas) if (area.groupId() != null) used.add(area.groupId());
        State state = new State(
                areas,
                groups.stream().filter(group -> used.contains(group.id())).toList());
        JsonObject json = SavedAreaStore.encode(state);
        json.addProperty("format", FORMAT);
        json.remove("showOnMap");
        json.remove("showOnMinimap");
        json.remove("showNames");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        Path file = unique(folder, safe(label) + "-" + stamp);
        SavedAreaStore.writeAtomically(file, json.toString());
        return file;
    }

    /** Exchange files in the folder, newest first; an absent folder has none. */
    static List<Path> list(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(path -> path.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path path) -> {
                                try {
                                    return Files.getLastModifiedTime(path).toMillis();
                                } catch (IOException e) {
                                    return 0L;
                                }
                            })
                            .reversed()
                            .thenComparing(Path::toString))
                    .toList();
        }
    }

    /** Reads and validates an exchange file with the same rules as saved-area files. */
    static State read(Path file) throws IOException {
        // Files come from other people; the largest valid one (5000 areas of 256 vertices) is about 40 MB.
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Area file is too large");
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
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
    static Merge merge(State current, State incoming) {
        List<Group> groups = new ArrayList<>(current.groups());
        Map<String, String> groupIds = new HashMap<>();
        int groupsAdded = 0;
        for (Group group : incoming.groups()) {
            String groupName = clean(group.name());
            Group existing = groups.stream()
                    .filter(g -> g.name().equals(groupName))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                existing = new Group(UUID.randomUUID().toString(), groupName, group.visible());
                groups.add(existing);
                groupsAdded++;
            }
            groupIds.put(group.id(), existing.id());
        }
        List<Area> areas = new ArrayList<>(current.areas());
        int added = 0, skipped = 0;
        for (Area incomingArea : incoming.areas()) {
            Area area = incomingArea.withName(clean(incomingArea.name()));
            String groupId = area.groupId() == null ? null : groupIds.get(area.groupId());
            boolean duplicate = areas.stream()
                    .anyMatch(a -> a.name().equals(area.name())
                            && a.color() == area.color()
                            && a.opacity() == area.opacity()
                            && Objects.equals(a.groupId(), groupId)
                            // The kind and the outline, not the geometry itself: a rectangle in memory may keep its
                            // corners in the order they were drawn.
                            && a.geometry().getClass() == area.geometry().getClass()
                            && a.geometry().vertices().equals(area.geometry().vertices()));
            if (duplicate) {
                skipped++;
                continue;
            }
            areas.add(area.withId(UUID.randomUUID().toString()).withGroup(groupId));
            added++;
        }
        return new Merge(
                new State(areas, groups, current.showOnMap(), current.showOnMinimap(), current.showNames()),
                added,
                skipped,
                groupsAdded);
    }

    /** A name from someone else's file without control characters and the game's formatting sign. */
    static String clean(String name) {
        String cleaned = name.replaceAll("[\\p{Cntrl}\u00A7]", "").trim();
        return cleaned.isEmpty() ? "?" : cleaned;
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
