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

public final class SavedAreaStore {
    public static final class Area {
        private final String id;
        private final String name;
        private final int x1, z1, x2, z2;
        private final int color, opacity;
        private final String groupId;
        private final boolean visible;
        private final List<GridPoint> vertices;
        private final AreaGeometry geometry;

        public Area(String id, String name, int x1, int z1, int x2, int z2,
                    int color, int opacity, String groupId, boolean visible, List<GridPoint> vertices) {
            this(id, name, x1, z1, x2, z2, color, opacity, groupId, visible, vertices, null);
        }

        private Area(String id, String name, int x1, int z1, int x2, int z2,
                     int color, int opacity, String groupId, boolean visible, List<GridPoint> vertices,
                     AreaGeometry geometry) {
            this.id = id;
            this.name = name;
            this.x1 = x1; this.z1 = z1; this.x2 = x2; this.z2 = z2;
            this.color = color; this.opacity = opacity;
            this.groupId = groupId;
            this.visible = visible;
            this.vertices = vertices == null ? List.of() : List.copyOf(vertices);
            this.geometry = geometry != null ? geometry
                    : this.vertices.isEmpty() ? new RectangleGeometry(new SelectionBounds(x1, z1, x2, z2))
                    : new PolygonGeometry(this.vertices);
        }
        public Area(String id, String name, int x1, int z1, int x2, int z2,
                    int color, int opacity, String groupId, boolean visible) {
            this(id, name, x1, z1, x2, z2, color, opacity, groupId, visible, List.of());
        }
        public String id() { return id; }
        public String name() { return name; }
        public int x1() { return x1; }
        public int z1() { return z1; }
        public int x2() { return x2; }
        public int z2() { return z2; }
        public int color() { return color; }
        public int opacity() { return opacity; }
        public String groupId() { return groupId; }
        public boolean visible() { return visible; }
        public List<GridPoint> vertices() { return vertices; }
        public AreaGeometry geometry() { return geometry; }
        public SelectionBounds bounds() {
            return vertices.isEmpty() ? new SelectionBounds(x1, z1, x2, z2) : geometry().bounds().blocks();
        }
        private Area copy(String id, String name, int color, int opacity, String groupId, boolean visible) {
            return new Area(id, name, x1, z1, x2, z2, color, opacity, groupId, visible, vertices, geometry);
        }
        public Area withId(String id) { return copy(id, name, color, opacity, groupId, visible); }
        public Area withName(String name) { return copy(id, name, color, opacity, groupId, visible); }
        public Area withColor(int color) { return copy(id, name, color, opacity, groupId, visible); }
        public Area withOpacity(int opacity) { return copy(id, name, color, opacity, groupId, visible); }
        public Area withGroup(String groupId) { return copy(id, name, color, opacity, groupId, visible); }
        public Area withVisible(boolean visible) { return copy(id, name, color, opacity, groupId, visible); }
        public Area withGeometry(AreaGeometry geometry) {
            SelectionBounds b = geometry.bounds().blocks();
            List<GridPoint> points = geometry instanceof PolygonGeometry ? geometry.vertices() : List.of();
            return new Area(id, name, b.x1(), b.z1(), b.x2(), b.z2(), color, opacity, groupId, visible, points, geometry);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Area area)) return false;
            return x1 == area.x1 && z1 == area.z1 && x2 == area.x2 && z2 == area.z2
                    && color == area.color && opacity == area.opacity && visible == area.visible
                    && Objects.equals(id, area.id) && Objects.equals(name, area.name)
                    && Objects.equals(groupId, area.groupId) && vertices.equals(area.vertices);
        }

        @Override public int hashCode() {
            return Objects.hash(id, name, x1, z1, x2, z2, color, opacity, groupId, visible, vertices);
        }

        @Override public String toString() {
            return "Area[id=" + id + ", name=" + name + ", x1=" + x1 + ", z1=" + z1
                    + ", x2=" + x2 + ", z2=" + z2 + ", color=" + color + ", opacity=" + opacity
                    + ", groupId=" + groupId + ", visible=" + visible + ", vertices=" + vertices + "]";
        }
    }
    public record Group(String id, String name, boolean visible) {}
    /** {@code showNames}: names of saved areas on the World Map; files written before it existed show them. */
    public record State(List<Area> areas, List<Group> groups, boolean showOnMap, boolean showOnMinimap, boolean showNames) {
        public State { areas = List.copyOf(areas); groups = List.copyOf(groups); }
        public State(List<Area> areas, List<Group> groups, boolean showOnMap, boolean showOnMinimap) {
            this(areas, groups, showOnMap, showOnMinimap, true);
        }
        public State(List<Area> areas, List<Group> groups) { this(areas, groups, false, false); }
        public static State empty() { return new State(List.of(), List.of()); }
    }

    static final int MAX_AREAS = 1000, MAX_GROUPS = 200;
    private static final ConcurrentHashMap<Path, Long> GENERATIONS = new ConcurrentHashMap<>();
    private final Path root;
    private State state = State.empty();
    private String contextKey;
    private Path file;
    private boolean loadFailed;
    private long loadedGeneration = -1;

    public SavedAreaStore(Path root) { this.root = root; }
    /** The config directory the per-world area files live under. */
    public Path root() { return root; }
    public State state() { return state; }
    public boolean loadFailed() { return loadFailed; }

    public void useContext(MapBridge.Context context) throws IOException {
        String key = key(context);
        // Called for every minimap frame: the file name is worked out only when the context changes.
        if (!Objects.equals(contextKey, key)) {
            contextKey = key;
            file = root.resolve("xaero-exact-selection").resolve("areas-" + digest(key) + ".json");
            loadedGeneration = -1;
        }
        long generation = GENERATIONS.getOrDefault(file, 0L);
        if (loadedGeneration == generation) return;
        state = State.empty();
        loadFailed = false;
        if (!Files.exists(file)) { loadedGeneration = generation; return; }
        try {
            state = decode(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            loadedGeneration = generation;
        } catch (Exception e) {
            loadFailed = true;
            loadedGeneration = generation;
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
    }

    /** The version before the last write; {@link #replace} refuses a broken file, so this one was readable. */
    private Path backup() { return file.resolveSibling(file.getFileName() + ".bak"); }

    /** Reads the file again on the next {@link #useContext}: it may have been edited outside the game. */
    void reload() { contextKey = null; }

    boolean canRestore() { return loadFailed && Files.exists(backup()); }

    /** Puts the previous version back, keeping the broken file beside it; the next {@link #useContext} reads it. */
    void restoreBackup() throws IOException {
        Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(backup(), file);
        GENERATIONS.merge(file, 1L, Long::sum);
        reload();
    }

    /** Writes through a temporary file and a rename, so a failed write never leaves a half-written file. */
    static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            // Without this a power cut after the rename can leave an empty file.
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
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
        return new Area(UUID.randomUUID().toString(), name, bounds.x1(), bounds.z1(), bounds.x2(), bounds.z2(),
                color, opacity, groupId, true);
    }

    public static Area create(String name, AreaGeometry geometry, int color, int opacity, String groupId) {
        SelectionBounds bounds = geometry.bounds().blocks();
        List<GridPoint> vertices = geometry instanceof RectangleGeometry ? List.of() : geometry.vertices();
        return new Area(UUID.randomUUID().toString(), name, bounds.x1(), bounds.z1(), bounds.x2(), bounds.z2(),
                color, opacity, groupId, true, vertices, geometry);
    }

    private static String key(MapBridge.Context c) {
        return String.valueOf(c.worldId()) + "\u0000" + c.dimension() + "\u0000" + c.subworld();
    }

    private static String digest(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static JsonObject encode(State state) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 3);
        root.addProperty("showOnMap", state.showOnMap());
        root.addProperty("showOnMinimap", state.showOnMinimap());
        root.addProperty("showNames", state.showNames());
        JsonArray areas = new JsonArray();
        for (Area a : state.areas()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id()); o.addProperty("name", a.name());
            JsonObject geometry = new JsonObject();
            if (a.vertices().isEmpty()) {
                geometry.addProperty("type", "rectangle");
                geometry.addProperty("x1", a.x1()); geometry.addProperty("z1", a.z1());
                geometry.addProperty("x2", a.x2()); geometry.addProperty("z2", a.z2());
            } else {
                geometry.addProperty("type", a.geometry() instanceof PolylineGeometry ? "polyline" : "polygon");
                JsonArray vertices = new JsonArray();
                for (GridPoint point : a.vertices()) {
                    JsonObject vertex = new JsonObject();
                    vertex.addProperty("x", point.x()); vertex.addProperty("z", point.z());
                    vertices.add(vertex);
                }
                geometry.add("vertices", vertices);
            }
            o.add("geometry", geometry);
            o.addProperty("color", a.color()); o.addProperty("opacity", a.opacity());
            if (a.groupId() != null) o.addProperty("groupId", a.groupId());
            o.addProperty("visible", a.visible()); areas.add(o);
        }
        JsonArray groups = new JsonArray();
        for (Group g : state.groups()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", g.id()); o.addProperty("name", g.name()); o.addProperty("visible", g.visible());
            groups.add(o);
        }
        root.add("areas", areas); root.add("groups", groups);
        return root;
    }

    static State decode(JsonObject root) {
        int schema = root.get("schema").getAsInt();
        if (schema < 1 || schema > 3) throw new IllegalArgumentException("Unsupported area file schema");
        List<Area> areas = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("areas")) areas.add(decodeArea(e.getAsJsonObject(), schema));
        List<Group> groups = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("groups")) {
            JsonObject o = e.getAsJsonObject();
            groups.add(new Group(o.get("id").getAsString(), o.get("name").getAsString(),
                    !o.has("visible") || o.get("visible").getAsBoolean()));
        }
        State state = new State(areas, groups,
                schema >= 2 && root.has("showOnMap") && root.get("showOnMap").getAsBoolean(),
                schema >= 2 && root.has("showOnMinimap") && root.get("showOnMinimap").getAsBoolean(),
                !root.has("showNames") || root.get("showNames").getAsBoolean());
        try { validate(state); }
        catch (IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
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
            return new Area(id, name, geometry.get("x1").getAsInt(), geometry.get("z1").getAsInt(),
                    geometry.get("x2").getAsInt(), geometry.get("z2").getAsInt(), color, opacity, groupId, visible);
        if (!type.equals("polygon") && !type.equals("polyline")) throw new IllegalArgumentException("Unknown saved geometry type");
        List<GridPoint> points = new ArrayList<>();
        for (JsonElement vertex : geometry.getAsJsonArray("vertices")) {
            JsonObject p = vertex.getAsJsonObject();
            points.add(new GridPoint(p.get("x").getAsLong(), p.get("z").getAsLong()));
        }
        AreaGeometry shape = type.equals("polygon") ? new PolygonGeometry(points) : new PolylineGeometry(points);
        SelectionBounds bounds = shape.bounds().blocks();
        return new Area(id, name, bounds.x1(), bounds.z1(), bounds.x2(), bounds.z2(), color, opacity, groupId, visible, points, shape);
    }

    private static void validate(State state) throws IOException {
        if (state.areas().size() > MAX_AREAS || state.groups().size() > MAX_GROUPS)
            throw new IOException("Too many saved areas or groups");
        Set<String> groupIds = new HashSet<>(), areaIds = new HashSet<>();
        for (Group group : state.groups()) {
            if (group.id() == null || group.id().isBlank() || group.name() == null || group.name().isBlank()
                    || group.name().length() > 128 || !groupIds.add(group.id()))
                throw new IOException("Invalid saved group");
        }
        for (Area area : state.areas()) {
            if (area.id() == null || area.id().isBlank() || !areaIds.add(area.id())
                    || area.name() == null || area.name().isBlank() || area.name().length() > 128
                    || area.opacity() < 0 || area.opacity() > 100 || area.color() < 0 || area.color() > 0xFFFFFF
                    || area.groupId() != null && !groupIds.contains(area.groupId()))
                throw new IOException("Invalid saved area");
            try {
                AreaGeometry geometry = area.geometry();
                if (area.vertices().size() > PolygonGeometry.MAX_VERTICES)
                    throw new IllegalArgumentException("Polygon has too many vertices");
                geometry.triangulate();
                GeometryBounds bounds = geometry.bounds();
                if (bounds.minX() < -30_000_000L || bounds.minZ() < -30_000_000L
                        || bounds.maxX() > 30_000_000L || bounds.maxZ() > 30_000_000L)
                    throw new IllegalArgumentException("Geometry outside Minecraft world bounds");
            } catch (RuntimeException e) { throw new IOException("Invalid saved area geometry", e); }
        }
    }
}
