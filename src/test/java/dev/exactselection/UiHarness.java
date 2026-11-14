package dev.exactselection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;

/** What the interface checks share: a fake screen, reflection into the controller and its parts, and input helpers. */
final class UiHarness {
    private UiHarness() {}

    static final class FixedFont extends Font {
        final StringSplitter widths = new StringSplitter((code, style) -> 6.0f);

        FixedFont() {
            super(null);
        }

        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int width(FormattedText text) {
            return width(text.getString());
        }

        @Override
        public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
        }

        @Override
        public String plainSubstrByWidth(String text, int width, boolean backwards) {
            int length = Math.min(text.length(), Math.max(0, width / 6));
            return backwards ? text.substring(text.length() - length) : text.substring(0, length);
        }

        @Override
        public StringSplitter getSplitter() {
            return widths;
        }
    }

    static final class ClipboardKeyboard extends KeyboardHandler {
        String copied;

        ClipboardKeyboard() {
            super(null);
        }

        @Override
        public void setClipboard(String text) {
            copied = text;
        }
    }

    record DrawEvent(String text, int stratum) {}

    static final class RecordingGraphics extends GuiGraphics implements GuiGraphicsBridge {
        Matrix3x2fStack transform;
        GuiRenderState state;
        List<DrawEvent> events;
        int stratum;

        RecordingGraphics() {
            super(null, null, 0, 0);
        }

        @Override
        public Matrix3x2fStack pose() {
            return transform;
        }

        @Override
        public GuiRenderState exactselection$renderState() {
            return state;
        }

        @Override
        public void nextStratum() {
            stratum++;
            state.nextStratum();
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int color) {
            if (color != 0xB0101820) events.add(new DrawEvent(null, stratum));
        }

        @Override
        public void renderOutline(int x, int y, int width, int height, int color) {
            events.add(new DrawEvent(null, stratum));
        }

        @Override
        public void drawCenteredString(Font font, Component text, int x, int y, int color) {
            events.add(new DrawEvent(text.getString(), stratum));
        }
    }

    /** The controller's parts; a member the checks ask of the controller may live in one of them. */
    static List<Object> holders(Object object) throws Exception {
        List<Object> holders = new ArrayList<>(List.of(object));
        if (object instanceof ExactSelectionController) {
            for (Field part : ExactSelectionController.class.getDeclaredFields()) {
                if (!UiPart.class.isAssignableFrom(part.getType())) continue;
                part.setAccessible(true);
                holders.add(part.get(object));
            }
        }
        return holders;
    }

    static Object fieldHolder(Object object, String name) throws Exception {
        for (Object holder : holders(object)) {
            try {
                field(holder.getClass(), name);
                return holder;
            } catch (NoSuchFieldException absent) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    static Object get(Object object, String name) throws Exception {
        Object holder = fieldHolder(object, name);
        return field(holder.getClass(), name).get(holder);
    }

    static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void set(Object object, String name, Object value) throws Exception {
        Object holder = fieldHolder(object, name);
        field(holder.getClass(), name).set(holder, value);
    }

    /** A method of the controller or of whichever part now does that job. */
    static Method declared(String name, Class<?>... types) throws Exception {
        List<Class<?>> classes = new ArrayList<>(List.of(ExactSelectionController.class));
        for (Field part : ExactSelectionController.class.getDeclaredFields())
            if (UiPart.class.isAssignableFrom(part.getType())) classes.add(part.getType());
        for (Class<?> type : classes) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException absent) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** The object to invoke {@code method} on: {@code object} or the part that declares it. */
    static Object receiver(Object object, Method method) throws Exception {
        for (Object holder : holders(object)) if (method.getDeclaringClass().isInstance(holder)) return holder;
        throw new AssertionError("No receiver for " + method);
    }

    static Object invoke(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        for (Object holder : holders(object)) {
            try {
                Method method = holder.getClass().getDeclaredMethod(name, types);
                method.setAccessible(true);
                Object result = method.invoke(holder, args);
                if (object instanceof ExactSelectionController controller) controller.updateVisibility();
                return result;
            } catch (NoSuchMethodException absent) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    static Object call(Object object, String name) throws Exception {
        return invoke(object, name, new Class<?>[0]);
    }

    static Object call(Object object, String name, int value) throws Exception {
        return invoke(object, name, new Class<?>[] {int.class}, value);
    }

    static Object call(Object object, String name, int a, int b, int c) throws Exception {
        return invoke(object, name, new Class<?>[] {int.class, int.class, int.class}, a, b, c);
    }

    static Object call(Object object, String name, boolean value) throws Exception {
        return invoke(object, name, new Class<?>[] {boolean.class}, value);
    }

    static void press(ExactSelectionController controller, String name) throws Exception {
        press(controller, (Button) get(controller, name), name);
    }

    static void press(ExactSelectionController controller, Button button, String name) throws Exception {
        // A press goes around the input events, so it does what they and the frame before it would.
        controller.updateVisibility();
        require(
                button.visible && button.active,
                "Button is unavailable: " + name + ", window=" + get(controller, "dialog"));
        button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        controller.updateVisibility();
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static EditBox text(ExactSelectionController controller, int index) throws Exception {
        return ((EditBox[]) get(controller, "dialogFields"))[index];
    }

    static void enter(ExactSelectionController controller, GuiMap map, int index, String value) throws Exception {
        EditBox box = text(controller, index);
        box.setValue(value);
        controller.updateVisibility();
        map.setFocused(box);
        controller.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
    }

    static String window(ExactSelectionController controller) throws Exception {
        return get(controller, "dialog").toString();
    }

    static void row(ExactSelectionController controller, int index, boolean checkbox) throws Exception {
        int[] rect = dialogRect(controller);
        controller.mouseClicked(
                new MouseButtonEvent(
                        rect[0] + (checkbox ? 17 : 54),
                        rect[1] + 54 + index * 20,
                        new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)),
                false);
        // Separate clicks, whatever time passes here between them: never the two of a double click.
        set(controller, "lastRowId", null);
    }

    /** Checks only {@code id} in the open Areas window, as a click on its row name would. */
    static void pick(ExactSelectionController controller, SavedAreaStore store, String id) throws Exception {
        List<SavedAreaStore.Area> areas = store.state().areas();
        for (int i = 0; i < areas.size(); i++)
            if (areas.get(i).id().equals(id)) {
                row(controller, i, false);
                return;
            }
        throw new AssertionError("No area " + id);
    }

    /** Legacy flows expect immediate writes: autosave every property except the territory shape. */
    static void autosaveProperties(ExactSelectionController controller) throws Exception {
        ModSettings autosave = (ModSettings) get(controller, "autosave");
        if (!autosave.enabled()) autosave.setEnabled(true);
        if (autosave.includes(ModSettings.Field.GEOMETRY)) autosave.toggle(ModSettings.Field.GEOMETRY);
    }

    static void checkLayout(ExactSelectionController controller) throws Exception {
        call(controller, "layoutDialog");
        int[] rect = dialogRect(controller);
        List<AbstractWidget> visible = ((List<?>) get(controller, "widgets"))
                .stream().map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (AbstractWidget widget : visible) {
            require(
                    widget.getX() >= rect[0]
                            && widget.getY() >= rect[1]
                            && widget.getX() + widget.getWidth() <= rect[0] + rect[2]
                            && widget.getY() + widget.getHeight() <= rect[1] + rect[3] - 2,
                    "Widget outside " + window(controller) + ": "
                            + widget.getMessage().getString());
            for (AbstractWidget other : visible) {
                if (widget == other) continue;
                boolean overlap = widget.getX() < other.getX() + other.getWidth()
                        && widget.getX() + widget.getWidth() > other.getX()
                        && widget.getY() < other.getY() + other.getHeight()
                        && widget.getY() + widget.getHeight() > other.getY();
                require(
                        !overlap,
                        "Overlapping widgets in " + window(controller) + ": "
                                + widget.getMessage().getString() + " / "
                                + other.getMessage().getString());
            }
        }
    }

    static void checkMainLayout(ExactSelectionController controller, GuiMap map) throws Exception {
        int width = (Integer) get(controller, "panelWidth"), height = (Integer) get(controller, "panelHeight");
        require(34 + width <= map.width && 26 + height <= map.height, "Main panel exceeds screen");
        List<AbstractWidget> visible = ((List<?>) get(controller, "widgets"))
                .stream()
                        .map(AbstractWidget.class::cast)
                        .filter(w -> w.visible && w != uncheckedGet(controller, "toggle"))
                        .toList();
        for (AbstractWidget widget : visible) {
            require(
                    widget.getX() >= 34
                            && widget.getY() >= 26
                            && widget.getX() + widget.getWidth() <= 34 + width
                            && widget.getY() + widget.getHeight() <= 26 + height,
                    "Main widget outside panel: " + widget.getMessage().getString());
            for (AbstractWidget other : visible) {
                if (widget == other) continue;
                require(
                        widget.getX() >= other.getX() + other.getWidth()
                                || widget.getX() + widget.getWidth() <= other.getX()
                                || widget.getY() >= other.getY() + other.getHeight()
                                || widget.getY() + widget.getHeight() <= other.getY(),
                        "Main panel widgets overlap: " + widget.getMessage().getString() + " " + widget.getRectangle()
                                + " / " + other.getMessage().getString() + " " + other.getRectangle());
            }
        }
    }

    static Object polygon(Object controller) throws Exception {
        return get(controller, "polygonTool");
    }

    static Object ruler(Object controller) throws Exception {
        return get(controller, "rulerTool");
    }

    static Object uncheckedGet(Object object, String name) {
        try {
            return get(object, name);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static MouseButtonEvent left(int x, int y, int modifiers) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers));
    }

    static MouseButtonEvent right(int x, int y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0));
    }

    static void clickMap(ExactSelectionController controller, int x, int y, int modifiers) {
        require(controller.mouseClicked(left(x, y, modifiers), false), "Map click was not captured");
        controller.mouseReleased(left(x, y, modifiers));
    }

    static void key(ExactSelectionController controller, int key, int modifiers) {
        controller.keyPressed(new KeyEvent(key, 0, modifiers));
    }

    static void doubleClickMap(ExactSelectionController controller, int x, int y, int modifiers) {
        clickMap(controller, x, y, modifiers);
        require(controller.mouseClicked(left(x, y, modifiers), true), "Double click was not captured");
        controller.mouseReleased(left(x, y, modifiers));
    }

    static SelectionBounds dragHandle(
            ExactSelectionController controller, SelectionBounds start, int fromX, int fromY, int toX, int toY) {
        SelectionState selection = (SelectionState) uncheckedGet(controller, "state");
        selection.set(start);
        controller.mouseClicked(left(fromX, fromY, 0), false);
        controller.mouseReleased(left(toX, toY, 0));
        return selection.bounds();
    }

    static final class FakeWaypoints implements WaypointAccess {
        final Object world = new Object(), set = new Object();
        final List<Point> points = new ArrayList<>();

        FakeWaypoints() {
            points.add(new Point(new Object(), 0, 64, 0, "First", false, true));
            points.add(new Point(new Object(), 10, 64, 10, "Second", false, true));
            points.add(new Point(new Object(), 100, 64, 100, "Outside", false, true));
        }

        @Override
        public Snapshot all(MapBridge.Context context) {
            return new Snapshot(world, set, "test", points);
        }

        @Override
        public Snapshot select(MapBridge.Context context, AreaGeometry geometry) {
            return new Snapshot(
                    world,
                    set,
                    "test",
                    points.stream()
                            .filter(p -> geometry.containsBlockCenter(p.x(), p.z()))
                            .toList());
        }

        @Override
        public Snapshot captureSet(Snapshot snapshot) {
            return snapshot == null ? null : new Snapshot(world, set, "test", snapshot.points(), points);
        }

        @Override
        public int colorRgb(int index) {
            return index * 0x10101;
        }

        @Override
        public void edit(Snapshot snapshot, List<Point> from, List<Point> to) {
            for (Point p : from) if (!points.contains(p)) throw new IllegalStateException("stale point");
            for (int i = 0; i < from.size(); i++) points.set(points.indexOf(from.get(i)), to.get(i));
        }

        @Override
        public void delete(Snapshot snapshot) {
            points.removeAll(snapshot.points());
        }

        @Override
        public void restore(Snapshot snapshot) {
            points.addAll(snapshot.points());
        }

        @Override
        public Point create(MapBridge.Context c, int x, int y, int z, String n) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Point recreate(MapBridge.Context c, Point p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeCreated(MapBridge.Context c, Point p) {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object dialog(ExactSelectionController controller, String name) throws Exception {
        return Enum.valueOf((Class) field(controller.getClass(), "dialog").getType(), name);
    }

    /** A rectangle of the color picker for the window at {@code r}, as {x, y, width, height}. */
    static int[] pickerRect(String name, int[] r) throws Exception {
        Method method = declared(name, int.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return array((Rect) method.invoke(null, r[0], r[1], r[2], r[3]));
    }

    /** The Areas window's copy buttons in the order of {@code AreasWindow.AreaCopy}. */
    @SuppressWarnings("unchecked")
    static Button[] areaCopies(Object controller) throws Exception {
        return ((java.util.Map<?, Button>) get(controller, "areaCopies"))
                .values()
                .toArray(Button[]::new);
    }

    /** The open window's rectangle as {x, y, width, height}. */
    static int[] dialogRect(Object controller) throws Exception {
        return array((Rect) call(controller, "dialogRect"));
    }

    private static int[] array(Rect r) {
        return new int[] {r.x(), r.y(), r.width(), r.height()};
    }

    static void mapClick(ExactSelectionController controller, double x, double y, int button) throws Exception {
        controller.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
        controller.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)));
    }

    /**
     * A controller with the tool on, its own widgets alone on the map, and one saved 100 x 100 area at the map center.
     */
    static ExactSelectionController toolOn(
            GuiMap map,
            MapBridge bridge,
            Minecraft minecraft,
            Path directory,
            MapBridge.Context context,
            boolean showOnMap,
            String folder)
            throws Exception {
        SavedAreaStore store = new SavedAreaStore(Files.createDirectories(directory.resolve(folder)));
        store.useContext(context);
        store.replace(new SavedAreaStore.State(
                List.of(SavedAreaStore.create("Saved", new SelectionBounds(0, 0, 99, 99), 0x123456, 25, null)),
                List.of(),
                showOnMap,
                showOnMap));
        set(map, "children", new ArrayList<>());
        ExactSelectionController controller = new ExactSelectionController(map, bridge, minecraft, store, null);
        controller.init();
        call(controller, "toggle");
        call(controller, "updateVisibility");
        return controller;
    }

    /** A bridge that reports what the tool asked the map to do, and can stand for a screen before its first frame. */
    static final class RecordingBridge implements MapBridge {
        final MapBridge map;
        boolean noFrame, closed;
        double[] camera;

        RecordingBridge(MapBridge map) {
            this.map = map;
        }

        @Override
        public Frame exactselection$frame() {
            return noFrame ? null : map.exactselection$frame();
        }

        @Override
        public MapProcessor exactselection$processor() {
            return map.exactselection$processor();
        }

        @Override
        public void exactselection$pan(double dx, double dy) {}

        @Override
        public void exactselection$clearNativeInput() {}

        @Override
        public void exactselection$moveCamera(double x, double z, double pixelsPerBlock) {
            camera = new double[] {x, z, pixelsPerBlock};
        }

        @Override
        public void exactselection$closeMap() {
            closed = true;
        }
    }

    static List<?> draft(ExactSelectionController controller) throws Exception {
        return (List<?>) get(polygon(controller), "draft");
    }

    static void drawContour(ExactSelectionController controller) throws Exception {
        for (int[] point : new int[][] {{100, 300}, {160, 300}, {220, 320}, {200, 345}, {120, 345}})
            mapClick(controller, point[0], point[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        require(draft(controller).size() == 5, "The contour was not drawn");
    }

    static boolean drawsLabel(ExactSelectionController controller, Unsafe unsafe, String text) throws Exception {
        return drawsLabel(controller, unsafe, text, 600, 330);
    }

    static boolean drawsLabel(ExactSelectionController controller, Unsafe unsafe, String text, int mouseX, int mouseY)
            throws Exception {
        RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
        graphics.events = new ArrayList<>();
        graphics.transform = new Matrix3x2fStack(16);
        graphics.state = new GuiRenderState();
        controller.renderMapOverlays(graphics, mouseX, mouseY);
        return graphics.events.stream()
                .anyMatch(event -> event.text() != null && event.text().contains(text));
    }

    static void reopen(GuiMap map, ExactSelectionController controller, MapBridge bridge) throws Exception {
        controller.suspend();
        declared("useScreen", GuiMap.class, MapBridge.class).invoke(controller, map, bridge);
        set(map, "children", new ArrayList<>());
        controller.init();
    }

    static Path areaFile(SavedAreaStore store) throws Exception {
        try (var files = Files.list(store.root().resolve("xaero-exact-selection"))) {
            return files.filter(path -> path.toString().endsWith(".json"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    static boolean exportTooltipSet(ExactSelectionController controller) throws Exception {
        Object holder = field(AbstractWidget.class, "tooltip").get(get(controller, "openExportButton"));
        return holder.getClass().getMethod("get").invoke(holder) != null;
    }

    static void clickRow(ExactSelectionController controller, int index, int modifiers) throws Exception {
        int[] rect = dialogRect(controller);
        controller.mouseClicked(
                new MouseButtonEvent(
                        rect[0] + 54,
                        rect[1] + 54 + index * 20,
                        new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers)),
                false);
    }

    static Set<String> checkedNames(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        Set<?> ids = (Set<?>) get(controller, "selectedAreaIds");
        Set<String> names = new java.util.TreeSet<>();
        for (SavedAreaStore.Area area : store.state().areas()) if (ids.contains(area.id())) names.add(area.name());
        return names;
    }
}
