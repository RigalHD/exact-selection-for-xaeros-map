package dev.exactselection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The saved areas and groups of one world, dimension and subworld, kept in one JSON file under the config directory.
 * Every change replaces the whole {@link State}; a file that cannot be read is never overwritten.
 */
public final class SavedAreaStore {
    /** The newest file schema this version reads and the one it writes. */
    static final int SCHEMA = 3;

    /**
     * The file was written by a newer version of the mod: it is intact, so it is neither overwritten nor "restored".
     */
    static final class NewerSchemaException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        NewerSchemaException() {
            super("Area file was written by a newer version of the mod");
        }
    }

    /**
     * What a store read at one generation of a file, with the file's time and size then; stores of the same file
     * share it instead of reading an unchanged file again.
     */
    private record Loaded(long generation, String stamp, State state) {}
    /**
     * A saved shape with its name and style. The geometry is the one source of its corners and vertices, so a
     * rectangle, a polygon and a route cannot be confused with each other.
     */
    public record Area(
            String id, String name, AreaGeometry geometry, int color, int opacity, String groupId, boolean visible) {
        public Area {
            if (geometry == null) throw new IllegalArgumentException("An area needs a shape");
        }

        /** A rectangle with its corners in the order they were drawn. */
        public Area(
                String id,
                String name,
                int x1,
                int z1,
                int x2,
                int z2,
                int color,
                int opacity,
                String groupId,
                boolean visible) {
            this(
                    id,
                    name,
                    new RectangleGeometry(new SelectionBounds(x1, z1, x2, z2)),
                    color,
                    opacity,
                    groupId,
                    visible);
        }

        /** The corner blocks: a rectangle's own, or the bounding box of a polygon or a route. */
        public SelectionBounds bounds() {
            return geometry instanceof RectangleGeometry rectangle
                    ? rectangle.blocks()
                    : geometry.bounds().blocks();
        }

        public int x1() {
            return bounds().x1();
        }

        public int z1() {
            return bounds().z1();
        }

        public int x2() {
            return bounds().x2();
        }

        public int z2() {
            return bounds().z2();
        }
        /** The vertices of a polygon or a route; a rectangle has none of its own. */
        public List<GridPoint> vertices() {
            return geometry instanceof RectangleGeometry ? List.of() : geometry.vertices();
        }

        public Area withId(String id) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }

        public Area withName(String name) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }

        public Area withColor(int color) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }

        public Area withOpacity(int opacity) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }

        public Area withGroup(String groupId) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }

        public Area withVisible(boolean visible) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }

        public Area withGeometry(AreaGeometry geometry) {
            return new Area(id, name, geometry, color, opacity, groupId, visible);
        }
    }

    public record Group(String id, String name, boolean visible) {}
    /** {@code showNames}: names of saved areas on the World Map; files written before it existed show them. */
    public record State(
            List<Area> areas, List<Group> groups, boolean showOnMap, boolean showOnMinimap, boolean showNames) {
        public State {
            areas = List.copyOf(areas);
            groups = List.copyOf(groups);
        }

        public State(List<Area> areas, List<Group> groups, boolean showOnMap, boolean showOnMinimap) {
            this(areas, groups, showOnMap, showOnMinimap, true);
        }

        public State(List<Area> areas, List<Group> groups) {
            this(areas, groups, false, false);
        }

        public static State empty() {
            return new State(List.of(), List.of());
        }
    }

    static final int MAX_AREAS = 5000, MAX_GROUPS = 1000;
    private static final ConcurrentHashMap<Path, Long> GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, Loaded> LOADED = new ConcurrentHashMap<>();
    private final Path root;
    private State state = State.empty();
    private String contextKey;
    private Path file;
    private boolean loadFailed;
    private boolean newerFile;
    private long loadedGeneration = -1;
    /** The file's time and size when this store last read or wrote it. */
    private String loadedStamp;

    public SavedAreaStore(Path root) {
        this.root = root;
    }
    /** The config directory the per-world area files live under. */
    public Path root() {
        return root;
    }

    public State state() {
        return state;
    }

    public boolean loadFailed() {
        return loadFailed;
    }
    /** The file could not be read because a newer version of the mod wrote it. */
    public boolean newerFile() {
        return newerFile;
    }

    public void useContext(MapBridge.Context context) throws IOException {
        String key = key(context);
        // Called for every minimap frame: the file name is worked out only when the context changes.
        if (!Objects.equals(contextKey, key)) {
            contextKey = key;
            file = ModFiles.directory(root).resolve("areas-" + digest(key) + ".json");
            loadedGeneration = -1;
        }
        long generation = GENERATIONS.getOrDefault(file, 0L);
        if (loadedGeneration == generation) return;
        state = State.empty();
        loadFailed = newerFile = false;
        loadedGeneration = generation;
        loadedStamp = stamp();
        Loaded shared = LOADED.get(file);
        if (shared != null
                && shared.generation() == generation
                && shared.stamp().equals(loadedStamp)) {
            state = shared.state();
            return;
        }
        if (!Files.exists(file)) return;
        try {
            state = decode(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject());
            LOADED.put(file, new Loaded(generation, loadedStamp, state));
        } catch (Exception e) {
            loadFailed = true;
            newerFile = e instanceof NewerSchemaException;
            throw new IOException("Saved areas could not be read; the existing file was kept", e);
        }
    }

    public void replace(State next) throws IOException {
        if (file == null) throw new IOException("No active map context");
        if (loadFailed) throw new IOException("Saved areas were not loaded; refusing to overwrite them");
        validate(next);
        JsonObject json = encode(next);
        // The file's own name is only a hash; this says which world it belongs to.
        json.addProperty("context", contextKey.replace('\u0000', '|'));
        if (Files.exists(file)) Files.copy(file, backup(), StandardCopyOption.REPLACE_EXISTING);
        writeAtomically(file, json.toString());
        state = next;
        loadedGeneration = GENERATIONS.merge(file, 1L, Long::sum);
        loadedStamp = stamp();
        LOADED.put(file, new Loaded(loadedGeneration, loadedStamp, next));
    }

    /** The version before the last write; {@link #replace} refuses a broken file, so this one was readable. */
    private Path backup() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /**
     * The file may have been edited outside the game: when its time or size changed, or it could not be read, the
     * next {@link #useContext} of every store reads it again. An untouched file is not read again.
     */
    void reload() {
        if (file != null && (loadFailed || !Objects.equals(loadedStamp, stamp())))
            GENERATIONS.merge(file, 1L, Long::sum);
    }

    private String stamp() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file) : "absent";
        } catch (IOException e) {
            return "unreadable:" + System.nanoTime();
        }
    }

    boolean canRestore() {
        return loadFailed && !newerFile && Files.exists(backup());
    }

    /**
     * Puts the previous version back, keeping the broken file beside it; the next {@link #useContext} reads it.
     * A previous version that cannot be read either changes nothing, and an earlier broken file is never overwritten.
     */
    void restoreBackup() throws IOException {
        try {
            decode(JsonParser.parseString(Files.readString(backup(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
        } catch (Exception e) {
            throw new IOException("The previous version cannot be read either; nothing was changed", e);
        }
        String name = file.getFileName() + ".broken";
        Path broken = file.resolveSibling(name);
        for (int i = 2; Files.exists(broken); i++) broken = file.resolveSibling(name + "-" + i);
        Files.move(file, broken);
        Files.copy(backup(), file);
        GENERATIONS.merge(file, 1L, Long::sum);
    }

    /** Writes through a temporary file and a rename, so a failed write never leaves a half-written file. */
    static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            // Without this a power cut after the rename can leave an empty file.
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static Area create(String name, SelectionBounds bounds, int color, int opacity, String groupId) {
        return new Area(
                UUID.randomUUID().toString(),
                name,
                bounds.x1(),
                bounds.z1(),
                bounds.x2(),
                bounds.z2(),
                color,
                opacity,
                groupId,
                true);
    }

    public static Area create(String name, AreaGeometry geometry, int color, int opacity, String groupId) {
        return new Area(UUID.randomUUID().toString(), name, geometry, color, opacity, groupId, true);
    }

    private static String key(MapBridge.Context c) {
        return String.valueOf(c.worldId()) + "\u0000" + c.dimension() + "\u0000" + c.subworld();
    }

    private static String digest(String key) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject encode(State state) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("showOnMap", state.showOnMap());
        root.addProperty("showOnMinimap", state.showOnMinimap());
        root.addProperty("showNames", state.showNames());
        JsonArray areas = new JsonArray();
        for (Area a : state.areas()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("name", a.name());
            JsonObject geometry = new JsonObject();
            if (a.geometry() instanceof RectangleGeometry) {
                geometry.addProperty("type", "rectangle");
                geometry.addProperty("x1", a.x1());
                geometry.addProperty("z1", a.z1());
                geometry.addProperty("x2", a.x2());
                geometry.addProperty("z2", a.z2());
            } else {
                geometry.addProperty("type", a.geometry() instanceof PolylineGeometry ? "polyline" : "polygon");
                JsonArray vertices = new JsonArray();
                for (GridPoint point : a.vertices()) {
                    JsonObject vertex = new JsonObject();
                    vertex.addProperty("x", point.x());
                    vertex.addProperty("z", point.z());
                    vertices.add(vertex);
                }
                geometry.add("vertices", vertices);
            }
            o.add("geometry", geometry);
            o.addProperty("color", a.color());
            o.addProperty("opacity", a.opacity());
            if (a.groupId() != null) o.addProperty("groupId", a.groupId());
            o.addProperty("visible", a.visible());
            areas.add(o);
        }
        JsonArray groups = new JsonArray();
        for (Group g : state.groups()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", g.id());
            o.addProperty("name", g.name());
            o.addProperty("visible", g.visible());
            groups.add(o);
        }
        root.add("areas", areas);
        root.add("groups", groups);
        return root;
    }

    static State decode(JsonObject root) {
        int schema = root.get("schema").getAsInt();
        if (schema > SCHEMA) throw new NewerSchemaException();
        if (schema < 1) throw new IllegalArgumentException("Unsupported area file schema");
        List<Area> areas = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("areas")) areas.add(decodeArea(e.getAsJsonObject(), schema));
        List<Group> groups = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("groups")) {
            JsonObject o = e.getAsJsonObject();
            groups.add(new Group(
                    o.get("id").getAsString(),
                    o.get("name").getAsString(),
                    !o.has("visible") || o.get("visible").getAsBoolean()));
        }
        State state = new State(
                areas,
                groups,
                schema >= 2 && root.has("showOnMap") && root.get("showOnMap").getAsBoolean(),
                schema >= 2
                        && root.has("showOnMinimap")
                        && root.get("showOnMinimap").getAsBoolean(),
                !root.has("showNames") || root.get("showNames").getAsBoolean());
        try {
            validate(state);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return state;
    }

    private static Area decodeArea(JsonObject o, int schema) {
        String id = o.get("id").getAsString(), name = o.get("name").getAsString();
        int color = o.get("color").getAsInt(), opacity = o.get("opacity").getAsInt();
        String groupId = o.has("groupId") ? o.get("groupId").getAsString() : null;
        boolean visible = !o.has("visible") || o.get("visible").getAsBoolean();
        // Before schema 3 every area was a rectangle with its corners in the area itself.
        JsonObject geometry = schema >= 3 ? o.getAsJsonObject("geometry") : o;
        String type = schema >= 3 ? geometry.get("type").getAsString() : "rectangle";
        if (type.equals("rectangle"))
            return new Area(
                    id,
                    name,
                    geometry.get("x1").getAsInt(),
                    geometry.get("z1").getAsInt(),
                    geometry.get("x2").getAsInt(),
                    geometry.get("z2").getAsInt(),
                    color,
                    opacity,
                    groupId,
                    visible);
        if (!type.equals("polygon") && !type.equals("polyline"))
            throw new IllegalArgumentException("Unknown saved geometry type");
        List<GridPoint> points = new ArrayList<>();
        for (JsonElement vertex : geometry.getAsJsonArray("vertices")) {
            JsonObject p = vertex.getAsJsonObject();
            points.add(new GridPoint(p.get("x").getAsLong(), p.get("z").getAsLong()));
        }
        AreaGeometry shape = type.equals("polygon") ? new PolygonGeometry(points) : new PolylineGeometry(points);
        return new Area(id, name, shape, color, opacity, groupId, visible);
    }

    private static void validate(State state) throws IOException {
        if (state.areas().size() > MAX_AREAS || state.groups().size() > MAX_GROUPS)
            throw new IOException("Too many saved areas or groups");
        Set<String> groupIds = new HashSet<>(), areaIds = new HashSet<>();
        for (Group group : state.groups()) {
            if (group.id() == null
                    || group.id().isBlank()
                    || group.name() == null
                    || group.name().isBlank()
                    || group.name().length() > 128
                    || !groupIds.add(group.id())) throw new IOException("Invalid saved group");
        }
        for (Area area : state.areas()) {
            if (area.id() == null
                    || area.id().isBlank()
                    || !areaIds.add(area.id())
                    || area.name() == null
                    || area.name().isBlank()
                    || area.name().length() > 128
                    || area.opacity() < 0
                    || area.opacity() > 100
                    || area.color() < 0
                    || area.color() > 0xFFFFFF
                    || area.groupId() != null && !groupIds.contains(area.groupId()))
                throw new IOException("Invalid saved area");
            try {
                AreaGeometry geometry = area.geometry();
                if (area.vertices().size() > PolygonGeometry.MAX_VERTICES)
                    throw new IllegalArgumentException("Polygon has too many vertices");
                geometry.triangulate();
                GeometryBounds bounds = geometry.bounds();
                if (bounds.minX() < -WorldLimits.MAX
                        || bounds.minZ() < -WorldLimits.MAX
                        || bounds.maxX() > WorldLimits.MAX
                        || bounds.maxZ() > WorldLimits.MAX)
                    throw new IllegalArgumentException("Geometry outside Minecraft world bounds");
            } catch (RuntimeException e) {
                throw new IOException("Invalid saved area geometry", e);
            }
        }
    }
}
