package dev.exactselection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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

public final class ManagementUiCheck {
    private static final class FixedFont extends Font {
        private final StringSplitter widths = new StringSplitter((code, style) -> 6.0f);
        FixedFont() { super(null); }
        @Override public int width(String text) { return text.length() * 6; }
        @Override public int width(FormattedText text) { return width(text.getString()); }
        @Override public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
        }
        @Override public String plainSubstrByWidth(String text, int width, boolean backwards) {
            int length = Math.min(text.length(), Math.max(0, width / 6));
            return backwards ? text.substring(text.length() - length) : text.substring(0, length);
        }
        @Override public StringSplitter getSplitter() { return widths; }
    }

    private record DrawEvent(String text, int stratum) {}

    private static final class RecordingGraphics extends GuiGraphics implements GuiGraphicsBridge {
        private Matrix3x2fStack transform;
        private GuiRenderState state;
        private List<DrawEvent> events;
        private int stratum;
        private RecordingGraphics() { super(null, null, 0, 0); }
        @Override public Matrix3x2fStack pose() { return transform; }
        @Override public GuiRenderState exactselection$renderState() { return state; }
        @Override public void nextStratum() { stratum++; state.nextStratum(); }
        @Override public void fill(int x1, int y1, int x2, int y2, int color) {
            if (color != 0xB0101820) events.add(new DrawEvent(null, stratum));
        }
        @Override public void renderOutline(int x, int y, int width, int height, int color) {
            events.add(new DrawEvent(null, stratum));
        }
        @Override public void drawCenteredString(Font font, Component text, int x, int y, int color) {
            events.add(new DrawEvent(text.getString(), stratum));
        }
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = field(object.getClass(), name);
        return field.get(object);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        field(object.getClass(), name).set(object, value);
    }

    private static Object call(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(object);
    }

    private static void press(ExactSelectionController controller, String name) throws Exception {
        Button button = (Button) get(controller, name);
        require(button.visible && button.active, "Button is unavailable: " + name + ", window=" + get(controller, "dialog"));
        button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        call(controller, "layoutDialog");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static EditBox text(ExactSelectionController controller, int index) throws Exception {
        return ((EditBox[]) get(controller, "dialogFields"))[index];
    }

    private static void enter(ExactSelectionController controller, GuiMap map, int index, String value) throws Exception {
        EditBox box = text(controller, index);
        box.setValue(value);
        map.setFocused(box);
        controller.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        call(controller, "layoutDialog");
    }

    private static String window(ExactSelectionController controller) throws Exception {
        return get(controller, "dialog").toString();
    }

    private static void row(ExactSelectionController controller, int index, boolean checkbox) throws Exception {
        int[] rect = (int[]) call(controller, "dialogRect");
        controller.mouseClicked(new MouseButtonEvent(rect[0] + (checkbox ? 17 : 54), rect[1] + 54 + index * 20,
                new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
        call(controller, "layoutDialog");
    }

    private static void checkLayout(ExactSelectionController controller) throws Exception {
        call(controller, "layoutDialog");
        int[] rect = (int[]) call(controller, "dialogRect");
        List<AbstractWidget> visible = ((List<?>) get(controller, "widgets")).stream()
                .map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (AbstractWidget widget : visible) {
            require(widget.getX() >= rect[0] && widget.getY() >= rect[1]
                    && widget.getX() + widget.getWidth() <= rect[0] + rect[2]
                    && widget.getY() + widget.getHeight() <= rect[1] + rect[3] - 2,
                    "Widget outside " + window(controller) + ": " + widget.getMessage().getString());
            for (AbstractWidget other : visible) {
                if (widget == other) continue;
                boolean overlap = widget.getX() < other.getX() + other.getWidth()
                        && widget.getX() + widget.getWidth() > other.getX()
                        && widget.getY() < other.getY() + other.getHeight()
                        && widget.getY() + widget.getHeight() > other.getY();
                require(!overlap, "Overlapping widgets in " + window(controller) + ": "
                        + widget.getMessage().getString() + " / " + other.getMessage().getString());
            }
        }
    }

    private static void checkMainLayout(ExactSelectionController controller, GuiMap map) throws Exception {
        int width = (Integer) get(controller, "panelWidth"), height = (Integer) get(controller, "panelHeight");
        require(34 + width <= map.width && 26 + height <= map.height, "Main panel exceeds screen");
        List<AbstractWidget> visible = ((List<?>) get(controller, "widgets")).stream()
                .map(AbstractWidget.class::cast).filter(w -> w.visible && w != uncheckedGet(controller, "toggle")).toList();
        for (AbstractWidget widget : visible) {
            require(widget.getX() >= 34 && widget.getY() >= 26
                    && widget.getX() + widget.getWidth() <= 34 + width
                    && widget.getY() + widget.getHeight() <= 26 + height,
                    "Main widget outside panel: " + widget.getMessage().getString());
            for (AbstractWidget other : visible) {
                if (widget == other) continue;
                require(widget.getX() >= other.getX() + other.getWidth()
                        || widget.getX() + widget.getWidth() <= other.getX()
                        || widget.getY() >= other.getY() + other.getHeight()
                        || widget.getY() + widget.getHeight() <= other.getY(), "Main panel widgets overlap");
            }
        }
    }

    private static Object uncheckedGet(Object object, String name) {
        try { return get(object, name); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static void checkSelectionPanel(ExactSelectionController controller, GuiMap map) throws Exception {
        require((Boolean) get(controller, "compact"), "Compact panel is not the default");
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot snapshot = selection.snapshot();
        require(!((Button) get(controller, "selectionExportButton")).visible, "Export leaked into compact panel");
        checkMainLayout(controller, map);
        press(controller, "panelModeButton");
        Button export = (Button) get(controller, "selectionExportButton");
        require(export.visible && export.active, "Finished unsaved selection cannot be exported");
        for (String name : List.of("dirty", "dragging", "loadFailed")) {
            set(controller, name, true); call(controller, "updateVisibility");
            require(!export.active, "Export enabled while " + name);
            set(controller, name, false);
        }
        selection.reset(); call(controller, "updateVisibility");
        require(!export.active, "Empty selection can be exported");
        selection.click(0, 0); selection.hover(10, 10); call(controller, "updateVisibility");
        require(!export.active, "Unfinished rectangle can be exported");
        selection.setGeometry(new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(10, 0), new GridPoint(0, 10))));
        call(controller, "updateVisibility");
        require(export.active, "Finished polygon cannot be exported");
        List<GridPoint> draft = (List<GridPoint>) get(controller, "polygonDraft");
        draft.add(new GridPoint(1, 1)); call(controller, "updateVisibility");
        require(!export.active, "Unfinished polygon can export an old selection");
        draft.clear(); selection.restore(snapshot);
        EditBox[] coordinates = (EditBox[]) get(controller, "fields");
        coordinates[0].setValue("12345");
        String value = coordinates[0].getValue();
        press(controller, "panelModeButton");
        press(controller, "panelModeButton");
        require(value.equals(coordinates[0].getValue()) && (Boolean) get(controller, "dirty"),
                "Changing panel mode lost coordinate draft");
        require(selection.snapshot().equals(snapshot), "Changing panel mode changed selection");
        set(controller, "dirty", false); call(controller, "syncFields");
        for (int[] size : new int[][]{{320, 240}, {480, 270}, {960, 540}}) {
            map.width = size[0]; map.height = size[1];
            call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
            checkMainLayout(controller, map);
            boolean exportReached = export.visible;
            for (int i = 0; i < 20; i++) {
                require(controller.mouseScrolled(40, 70, -1), "Panel scroll leaked into native map zoom");
                checkMainLayout(controller, map);
                exportReached |= export.visible;
            }
            require(exportReached, "Export is inaccessible on a small screen");
            press(controller, "panelModeButton"); checkMainLayout(controller, map);
            require(!controller.mouseScrolled(40, 220, -1), "Compact panel consumes scrolling below its bounds");
            press(controller, "panelModeButton");
        }
        press(controller, "panelModeButton");
        map.width = 640; map.height = 360;
        call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
        System.out.println("PASS: compact default, reversible panel toggle, unsaved selection export guards, responsive panel scrolling");
    }

    private static void checkExportNotice(ExactSelectionController controller, GuiMap map, Path directory) throws Exception {
        Button open = (Button) get(controller, "openExportButton");
        require(!open.visible, "File button is visible before an export");
        Method completed = ExactSelectionController.class.getDeclaredMethod("exportCompleted", AreaExportService.Result.class);
        completed.setAccessible(true);
        Path first = directory.resolve("export with spaces.png");
        Files.write(first, new byte[]{1});
        completed.invoke(controller, new AreaExportService.Result(first, 100, 200));
        require(open.visible && open.active, "Successful export did not show the file button");
        require(get(controller, "lastExport").equals(first.toAbsolutePath()), "Wrong file attached to the export button");
        require(!get(controller, "message").toString().contains(directory.toString()), "Full export path leaked into panel text");
        checkMainLayout(controller, map);
        press(controller, "panelModeButton");
        for (int[] size : new int[][]{{320, 240}, {480, 270}, {960, 540}}) {
            map.width = size[0]; map.height = size[1];
            call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
            boolean reached = open.visible;
            for (int i = 0; i < 20; i++) {
                controller.mouseScrolled(40, 70, -1); checkMainLayout(controller, map);
                reached |= open.visible;
            }
            require(reached, "File button cannot be reached on a small screen");
        }
        press(controller, "panelModeButton");
        press(controller, "areasButton");
        completed.invoke(controller, new AreaExportService.Result(first, 100, 200));
        for (int[] size : new int[][]{{320, 240}, {480, 270}, {960, 540}}) {
            map.width = size[0]; map.height = size[1];
            call(controller, "layoutDialog"); call(controller, "updateVisibility");
            require(open.visible, "Saved-area export did not show the file button");
            checkLayout(controller);
        }
        set(controller, "areaDeleteConfirmation", true); call(controller, "updateVisibility");
        require(!open.visible, "File button leaked into deletion confirmation");
        set(controller, "areaDeleteConfirmation", false); call(controller, "updateVisibility");
        press(controller, "managementClose");
        call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
        require(open.visible, "File button was lost on returning from saved areas");
        checkMainLayout(controller, map);
        Path second = directory.resolve("new export.png");
        Files.write(second, new byte[]{2});
        completed.invoke(controller, new AreaExportService.Result(second, 300, 400));
        require(get(controller, "lastExport").equals(second.toAbsolutePath()), "Export button still points to previous PNG");
        Files.delete(second);
        press(controller, "openExportButton");
        require(!open.visible && get(controller, "message").toString().contains("export_file_missing"),
                "Missing file did not produce a useful message");
        set(controller, "message", "");
        map.width = 640; map.height = 360;
        call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
        System.out.println("PASS: export file button, latest PNG path, both panels, responsive layouts, and missing-file handling");
    }

    private static MouseButtonEvent left(int x, int y, int modifiers) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers));
    }

    private static void clickMap(ExactSelectionController controller, int x, int y, int modifiers) {
        require(controller.mouseClicked(left(x, y, modifiers), false), "Map click was not captured");
        controller.mouseReleased(left(x, y, modifiers));
    }

    private static void key(ExactSelectionController controller, int key, int modifiers) {
        controller.keyPressed(new KeyEvent(key, 0, modifiers));
    }

    private static void checkPolygonEditing(ExactSelectionController controller, GuiMap map, SavedAreaStore store,
                                            Path directory, MapBridge.Context context) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        selection.reset(); call(controller, "syncFields"); call(controller, "updateVisibility");
        clickMap(controller, 390, 50, 0);
        require(selection.phase() == SelectionState.Phase.PREVIEW, "Rectangle preview did not start");
        press(controller, "polygonModeButton");
        require(selection.phase() == SelectionState.Phase.READY && get(controller, "gestureBefore") == null,
                "Entering polygon mode kept a stale rectangle gesture");
        clickMap(controller, 400, 60, 0); clickMap(controller, 560, 60, 0);
        clickMap(controller, 560, 260, 0); clickMap(controller, 400, 260, 0);
        List<GridPoint> draft = (List<GridPoint>) get(controller, "polygonDraft");
        require(draft.size() == 4 && selection.geometry() == null, "Draft became a complete area too early");
        require(!((Button) get(controller, "areasButton")).active
                && !((Button) get(controller, "selectionExportButton")).active,
                "Unfinished contour can operate on an old saved/exported selection");
        List<GridPoint> original = List.copyOf(draft);
        controller.mouseClicked(left(400, 60, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(420, 80);
        require(draft.size() == 4 && draft.get(0).equals(new GridPoint(100, -100)), "Ctrl did not drag the draft vertex");
        controller.mouseReleased(left(420, 80, GLFW.GLFW_MOD_CONTROL));
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(draft.equals(original), "Undo draft vertex movement removed a point instead of restoring its position");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_SUPER);
        require(draft.get(0).equals(new GridPoint(100, -100)), "Cmd+Y did not redo draft vertex movement");
        controller.mouseClicked(left(560, 60, GLFW.GLFW_MOD_SUPER), false);
        controller.mouseReleased(left(580, 70, GLFW.GLFW_MOD_SUPER));
        require(draft.get(1).equals(new GridPoint(260, -110)), "Cmd drag missed the final release position");
        List<GridPoint> beforeCancel = List.copyOf(draft);
        controller.mouseClicked(left(560, 260, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(590, 280);
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        controller.mouseReleased(left(590, 280, 0));
        require(draft.equals(beforeCancel) && (Boolean) get(controller, "polygonMode"), "Esc cancelled the entire path instead of just the vertex drag");
        key(controller, GLFW.GLFW_KEY_BACKSPACE, 0);
        require(draft.size() == 3, "Backspace did not remove the last vertex");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(draft.equals(beforeCancel), "Undo did not restore the removed vertex");
        clickMap(controller, 620, 320, GLFW.GLFW_MOD_CONTROL);
        require(draft.equals(beforeCancel), "Ctrl click away from a vertex added an unintended point");
        clickMap(controller, 420, 80, 0);
        require(selection.phase() == SelectionState.Phase.SELECTED && selection.geometry() instanceof PolygonGeometry,
                "Clicking the first vertex did not create a complete polygon area");
        PolygonGeometry closed = (PolygonGeometry) selection.geometry();
        require(closed.vertices().equals(beforeCancel) && !closed.triangulate().isEmpty() && closed.area() > 0,
                "Closed polygon lost vertices or cannot be filled");
        require((Boolean) get(controller, "polygonMode") && draft.isEmpty(), "Closing the path disabled the polygon tool or left a draft behind");
        call(controller, "updateVisibility");
        require(((Button) get(controller, "areasButton")).active && (Boolean) call(controller, "canExportSelection"),
                "Completed polygon cannot be saved or exported");
        controller.mouseClicked(left(580, 70, GLFW.GLFW_MOD_CONTROL), false);
        controller.mouseReleased(left(590, 90, GLFW.GLFW_MOD_CONTROL));
        PolygonGeometry moved = (PolygonGeometry) selection.geometry();
        require(moved.vertices().get(1).equals(new GridPoint(270, -90))
                && moved.vertices().get(2).equals(closed.vertices().get(2)), "Closed vertex edit changed other vertices");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(closed), "Undo did not restore the complete polygon");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
        require(selection.geometry().equals(moved), "Redo did not restore the edited polygon");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_SUPER), false);
        controller.beforeRender(400, 260);
        require((Boolean) get(controller, "vertexDragInvalid"), "Duplicate closed vertex was accepted");
        controller.mouseReleased(left(400, 260, GLFW.GLFW_MOD_SUPER));
        require(selection.geometry().equals(moved), "Rejected vertex edit did not restore original geometry");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(450, 290);
        require((Boolean) get(controller, "vertexDragInvalid"), "Self-intersecting vertex move was accepted");
        controller.mouseReleased(left(450, 290, GLFW.GLFW_MOD_CONTROL));
        require(selection.geometry().equals(moved), "Self-intersection rejection changed the completed area");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(30_000_400, 100);
        require((Boolean) get(controller, "vertexDragInvalid"), "World-boundary vertex move was accepted");
        controller.mouseReleased(left(30_000_400, 100, GLFW.GLFW_MOD_CONTROL));
        require(selection.geometry().equals(moved), "World-boundary rejection changed the completed area");
        controller.mouseClicked(left(480, 160, GLFW.GLFW_MOD_SHIFT), false);
        controller.beforeRender(500, 180);
        controller.mouseReleased(left(500, 180, GLFW.GLFW_MOD_SHIFT));
        require(selection.geometry().equals(moved.translated(20, 20)), "Shift drag cannot move a closed polygon while the polygon tool remains enabled");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(moved), "Undo whole-polygon move did not restore geometry");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT);
        require(selection.geometry().equals(moved.translated(20, 20)), "Cmd+Shift+Z did not redo the whole-polygon move");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(620, 110);
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        controller.mouseReleased(left(620, 110, 0));
        require(selection.geometry().equals(moved), "Esc did not restore the closed polygon");
        clickMap(controller, 620, 320, 0);
        require(draft.size() == 1, "Next click did not start another polygon");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(draft.isEmpty() && selection.geometry().equals(moved), "Undo first draft point changed the finished area");
        require(!((Button) get(controller, "undo")).active, "Exhausted draft undo still exposes unrelated area history");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(selection.geometry().equals(moved), "Undo crossed from the unfinished path into unrelated area history");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
        require(draft.size() == 1 && selection.geometry().equals(moved), "Redo did not restore the first draft point");
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        require(draft.isEmpty() && selection.geometry().equals(moved), "Cancelling the next path lost the completed area");
        press(controller, "areasButton"); press(controller, "areaSave");
        enter(controller, map, 8, "Polygon editing check");
        SavedAreaStore.Area area = store.state().areas().get(store.state().areas().size() - 1);
        require(area.geometry().equals(moved) && area.opacity() > 0 && area.visible(), "Saved polygon lost its geometry, visibility, or fill");
        press(controller, "areaLoad");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_SUPER), false);
        controller.mouseReleased(left(600, 95, GLFW.GLFW_MOD_SUPER));
        AreaGeometry editedSaved = selection.geometry();
        require(!editedSaved.equals(moved), "Loaded saved polygon cannot be edited with Cmd");
        press(controller, "areasButton"); press(controller, "areaUpdate");
        require(store.state().areas().stream().filter(a -> a.id().equals(area.id())).findFirst().orElseThrow()
                .geometry().equals(editedSaved), "Updating saved polygon converted it to a rectangle or lost edits");
        SavedAreaStore reload = new SavedAreaStore(directory); reload.useContext(context);
        require(reload.state().equals(store.state()), "Polygon edit was not persisted");
        require(window(controller).equals("NONE"), "Updating geometry did not return to the map");
        store.replace(saved); selection.restore(initial);
        ((SelectionHistory) get(controller, "history")).clear();
        ((Set<?>) get(controller, "selectedAreaIds")).clear();
        set(controller, "areaId", null); set(controller, "polygonMode", false); set(controller, "message", "");
        call(controller, "syncFields"); call(controller, "updateVisibility");
        System.out.println("PASS: polygon click-to-close, Ctrl/Cmd vertex drags, draft/closed undo, Esc, invalid moves, filled saved geometry, and persistence");
    }

    private static void doubleClickMap(ExactSelectionController controller, int x, int y, int modifiers) {
        clickMap(controller, x, y, modifiers);
        require(controller.mouseClicked(left(x, y, modifiers), true), "Double click was not captured");
        controller.mouseReleased(left(x, y, modifiers));
    }

    private static void checkPolygonInsertionAndUpdate(ExactSelectionController controller, GuiMap map,
                                                       SavedAreaStore store, Path directory,
                                                       MapBridge.Context context) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        PolygonGeometry polygon = new PolygonGeometry(List.of(new GridPoint(80, -120), new GridPoint(240, -120),
                new GridPoint(240, 80), new GridPoint(80, 80)));
        SavedAreaStore.Area area = SavedAreaStore.create("Editable polygon", polygon, 0xAABBCC, 35, "polygon-group");
        store.replace(new SavedAreaStore.State(List.of(area), List.of(new SavedAreaStore.Group("polygon-group", "Group", false)), true, true));
        set(controller, "areaId", area.id());
        press(controller, "areasButton"); press(controller, "areaLoad");
        require(selection.geometry().equals(polygon) && !(Boolean) get(controller, "polygonMode"),
                "Loading a polygon did not enter safe editing mode");
        clickMap(controller, 400, 60, 0); clickMap(controller, 402, 61, 0);
        clickMap(controller, 480, 160, 0); doubleClickMap(controller, 620, 320, 0);
        require(selection.geometry().equals(polygon) && selection.phase() == SelectionState.Phase.SELECTED,
                "Ordinary editing clicks replaced a saved polygon with a small rectangle");
        call(controller, "applyFields");
        require(selection.geometry().equals(polygon) && !((Button) get(controller, "apply")).active,
                "Applying rectangle coordinates destroyed a polygon");
        for (EditBox field : (EditBox[]) get(controller, "fields")) require(!field.active, "Polygon bounds are editable as rectangle coordinates");
        clickMap(controller, 480, 65, GLFW.GLFW_MOD_SHIFT);
        require(selection.geometry().equals(polygon) && ((List<?>) get(controller, "polygonDraft")).isEmpty(),
                "First click of a double click started an unrelated path");
        controller.mouseClicked(left(480, 65, GLFW.GLFW_MOD_SHIFT), true);
        controller.mouseReleased(left(480, 65, GLFW.GLFW_MOD_SHIFT));
        PolygonGeometry inserted = (PolygonGeometry) selection.geometry();
        require(inserted.vertices().size() == 5 && inserted.vertices().get(1).equals(new GridPoint(160, -120)),
                "Shift double click did not insert an axis-aligned vertex into the correct side");
        require(inserted.bounds().equals(polygon.bounds()) && inserted.areaTwice() == polygon.areaTwice()
                && !inserted.triangulate().isEmpty(), "Collinear insertion changed bounds, area, or fill");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(polygon), "Insertion undo did not restore the original polygon");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
        require(selection.geometry().equals(inserted), "Insertion redo did not restore the new vertex");
        press(controller, "areasButton");
        call(controller, "layoutDialog");
        Button update = (Button) get(controller, "areaUpdate");
        require(update.visible && update.active, "Polygon geometry update is unavailable");
        press(controller, "areaUpdate");
        require(window(controller).equals("NONE") && store.state().areas().get(0).equals(area.withGeometry(inserted)),
                "Update geometry lost polygon vertices or area metadata");
        SavedAreaStore reload = new SavedAreaStore(directory); reload.useContext(context);
        require(reload.state().equals(store.state()), "Updated vertices with unchanged bounds were not persisted");
        press(controller, "areasButton"); press(controller, "managementUndo");
        require(store.state().areas().get(0).equals(area), "Geometry update undo did not restore the saved polygon");
        press(controller, "managementRedo"); press(controller, "areaLoad");
        controller.mouseClicked(left(480, 60, GLFW.GLFW_MOD_SUPER), false);
        controller.mouseReleased(left(480, 40, GLFW.GLFW_MOD_SUPER));
        PolygonGeometry moved = (PolygonGeometry) selection.geometry();
        require(moved.vertices().get(1).equals(new GridPoint(160, -140)), "Inserted vertex cannot be moved with Cmd");
        press(controller, "areasButton"); press(controller, "areaUpdate");
        require(store.state().areas().get(0).equals(area.withGeometry(moved)), "Moved inserted vertex did not update saved geometry");
        press(controller, "areasButton"); press(controller, "areaLoad");
        doubleClickMap(controller, 400, 160, 0);
        PolygonGeometry closing = (PolygonGeometry) selection.geometry();
        require(closing.vertices().size() == 6 && closing.vertices().getLast().equals(new GridPoint(80, -20)),
                "Double click did not insert into the closing side");
        doubleClickMap(controller, 400, 160, 0);
        require(selection.geometry().equals(closing), "Double click on an existing vertex created a duplicate");
        press(controller, "clear"); press(controller, "polygonModeButton");
        clickMap(controller, 400, 60, 0);
        key(controller, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_MOD_SHIFT);
        controller.beforeRender(550, 85);
        require(get(controller, "polygonCursor").equals(new GridPoint(230, -120)), "Shift preview is not horizontal");
        clickMap(controller, 550, 85, GLFW.GLFW_MOD_SHIFT);
        controller.beforeRender(570, 245);
        require(get(controller, "polygonCursor").equals(new GridPoint(230, 65)), "Shift preview is not vertical");
        clickMap(controller, 570, 245, GLFW.GLFW_MOD_SHIFT);
        List<GridPoint> draft = (List<GridPoint>) get(controller, "polygonDraft");
        require(draft.equals(List.of(new GridPoint(80, -120), new GridPoint(230, -120), new GridPoint(230, 65))),
                "Shift draft placement disagrees with preview");
        controller.keyReleased(new KeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_MOD_SHIFT));
        controller.beforeRender(405, 230);
        require(get(controller, "polygonCursor").equals(new GridPoint(85, 50)), "Releasing Shift kept preview constrained");
        clickMap(controller, 405, 230, 0);
        clickMap(controller, 400, 60, 0);
        require(selection.geometry() instanceof PolygonGeometry finished && finished.vertices().size() == 4
                && !finished.triangulate().isEmpty(), "Axis-aligned draft could not become a filled polygon");
        store.replace(saved); selection.restore(initial);
        ((SelectionHistory) get(controller, "history")).clear();
        ((Set<?>) get(controller, "selectedAreaIds")).clear();
        set(controller, "areaId", null); set(controller, "polygonMode", false); set(controller, "shiftDown", false);
        call(controller, "syncFields"); call(controller, "updateVisibility");
        System.out.println("PASS: edge double clicks, insertion undo/redo, Shift point preview/placement, safe saved polygon editing and geometry update persistence");
    }

    private static void checkSavedLabelLayering(ExactSelectionController controller, SavedAreaStore store,
                                                Unsafe unsafe) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        List<AreaGeometry> geometries = List.of(
                new PolygonGeometry(List.of(new GridPoint(-100, -80), new GridPoint(100, -80), new GridPoint(0, 80))),
                new RectangleGeometry(new SelectionBounds(-100, -80, 100, 80)));
        for (AreaGeometry geometry : geometries) {
            selection.setGeometry(geometry);
            SavedAreaStore.Area area = SavedAreaStore.create("Named area", geometry, 0x59E3CE, 25, null);
            SavedAreaStore.Area overlapping = SavedAreaStore.create("Overlapping area", geometry, 0xFF5573, 50, null);
            store.replace(new SavedAreaStore.State(List.of(area, overlapping), List.of(), true, true));
            for (boolean active : List.of(true, false)) {
                if (active) selection.setGeometry(geometry); else selection.disable();
                SelectionState.Snapshot before = selection.snapshot();
                RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
                graphics.events = new ArrayList<>();
                graphics.transform = new Matrix3x2fStack(16);
                graphics.state = new GuiRenderState() {
                    @Override public void submitGuiElement(GuiElementRenderState element) {
                        graphics.events.add(new DrawEvent(null, graphics.stratum));
                        super.submitGuiElement(element);
                    }
                };
                controller.renderMapOverlays(graphics, 600, 330);
                List<GuiElementRenderState> geometryDraws = new ArrayList<>();
                graphics.state.forEachElement(geometryDraws::add, GuiRenderState.TraverseRange.ALL);
                require(geometryDraws.size() == (active ? 6 : 4),
                        "Saved or selected area bypasses the renderer preserving fractional coordinates");
                int lastGeometry = graphics.events.stream().filter(event -> event.text() == null)
                        .mapToInt(DrawEvent::stratum).max().orElseThrow();
                for (String name : List.of("Named area", "Overlapping area")) {
                    DrawEvent label = graphics.events.stream().filter(event -> name.equals(event.text()))
                            .findFirst().orElseThrow(() -> new AssertionError("Missing saved area label: " + name));
                    require(label.stratum() > lastGeometry,
                            "Saved area label is covered by geometry; active selection=" + active);
                }
                require(selection.snapshot().equals(before), "Rendering labels changed the active selection");
            }
        }
        selection.disable();
        for (SavedAreaStore.Area area : List.of(
                SavedAreaStore.create("Offscreen", new PolygonGeometry(List.of(new GridPoint(5000, 5000),
                        new GridPoint(5100, 5000), new GridPoint(5100, 5100), new GridPoint(5000, 5100))), 0x59E3CE, 25, null),
                SavedAreaStore.create("Concave", new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(100, 0),
                        new GridPoint(100, 20), new GridPoint(20, 20), new GridPoint(20, 100), new GridPoint(0, 100))),
                        0x59E3CE, 25, null))) {
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
            graphics.events = new ArrayList<>();
            graphics.transform = new Matrix3x2fStack(16);
            graphics.state = new GuiRenderState() {
                @Override public void submitGuiElement(GuiElementRenderState element) {
                    graphics.events.add(new DrawEvent(null, graphics.stratum));
                    super.submitGuiElement(element);
                }
            };
            controller.renderMapOverlays(graphics, 600, 330);
            boolean found = graphics.events.stream().anyMatch(event -> area.name().equals(event.text()));
            require(area.name().equals("Offscreen") ? !found : found,
                    area.name().equals("Offscreen") ? "Offscreen polygon name was pinned to the map edge"
                            : "Concave polygon lost its name because its bounding-box center is outside");
        }
        store.replace(saved); selection.restore(initial);
        call(controller, "syncFields"); call(controller, "updateVisibility");
        System.out.println("PASS: saved polygon and rectangle names above active selection and overlapping areas, selection preserved");
    }

    private static void checkGeometryGestures(ExactSelectionController controller, GuiMap map, Unsafe unsafe) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        boolean initialChunks = (Boolean) get(controller, "chunks");
        boolean initialAnchor = (Boolean) get(controller, "sizeFromCenter");
        boolean initialMoveUnits = (Boolean) get(controller, "moveInChunks");
        selection.enable();
        set(controller, "chunks", false);
        selection.set(new SelectionBounds(80, 80, 239, 239));
        controller.mouseClicked(left(556, 340, 0), false);
        controller.mouseReleased(left(556, 340, 0));
        require(selection.bounds().equals(new SelectionBounds(80, 80, 239, 239)),
                "Clicking a rectangle handle without moving changed its inclusive edge");

        set(controller, "chunks", true);
        controller.mouseClicked(left(556, 340, 0), false);
        controller.mouseReleased(left(556, 340, 0));
        require(selection.bounds().equals(new SelectionBounds(80, 80, 239, 239)),
                "Clicking a chunk-snapped handle changed its edge without movement");

        set(controller, "chunks", false);
        controller.mouseClicked(left(450, 300, GLFW.GLFW_MOD_SHIFT), false);
        controller.mouseReleased(left(480, 300, GLFW.GLFW_MOD_SHIFT));
        require(selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "Pointer movement between the last frame and release was lost");

        selection.set(new SelectionBounds(80, 80, 239, 239));
        controller.mouseClicked(left(560, 340, 0), false);
        controller.mouseReleased(left(570, 340, 0));
        require(selection.bounds().equals(new SelectionBounds(80, 80, 249, 239)),
                "The resize release coordinate was not applied");

        selection.enable();
        set(controller, "chunks", true);
        clickMap(controller, 500, 260, 0);
        RecordingGraphics previewGraphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
        previewGraphics.events = new ArrayList<>();
        previewGraphics.transform = new Matrix3x2fStack(16);
        previewGraphics.state = new GuiRenderState();
        controller.renderMapOverlays(previewGraphics, 480, 240);
        require(selection.bounds().equals(new SelectionBounds(191, 95, 160, 48)),
                "Reverse chunk preview did not cover whole chunks before confirmation");
        clickMap(controller, 480, 240, 0);
        require(selection.bounds().equals(new SelectionBounds(191, 95, 160, 48)),
                "Reverse chunk selection did not include both complete chunks");

        PolygonGeometry polygon = new PolygonGeometry(List.of(new GridPoint(80, 80), new GridPoint(100, 80),
                new GridPoint(100, 100), new GridPoint(80, 100)));
        selection.setGeometry(polygon);
        controller.mouseClicked(left(410, 270, GLFW.GLFW_MOD_SHIFT), false);
        controller.mouseReleased(left(431, 270, GLFW.GLFW_MOD_SHIFT));
        require(selection.geometry().equals(polygon.translated(21, 0)),
                "Rectangle chunk snapping leaked into polygon movement");

        set(controller, "sizeFromCenter", true);
        set(controller, "moveInChunks", true);
        controller.init();
        Button anchor = (Button) get(controller, "anchorButton");
        Button units = (Button) get(controller, "moveUnitsButton");
        Method translate = ExactSelectionController.class.getDeclaredMethod("tr", String.class, Object[].class);
        translate.setAccessible(true);
        Component expectedAnchor = (Component) translate.invoke(controller, new Object[]{"anchor_center", new Object[0]});
        Component expectedUnits = (Component) translate.invoke(controller, new Object[]{"units_chunks", new Object[0]});
        require(anchor.getMessage().getString().equals(expectedAnchor.getString()),
                "Resize anchor label did not follow its saved mode after rebuilding the screen");
        require(units.getMessage().getString().equals(expectedUnits.getString()),
                "Move units label did not follow its saved mode after rebuilding the screen");

        selection.restore(initial);
        set(controller, "chunks", initialChunks);
        set(controller, "sizeFromCenter", initialAnchor);
        set(controller, "moveInChunks", initialMoveUnits);
        ((SelectionHistory) get(controller, "history")).clear();
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        System.out.println("PASS: stationary and offset handle drags, release coordinates, reverse chunk selection, polygon movement, and mode labels");
    }

    private static void checkPrecisePointer(ExactSelectionController controller, Minecraft minecraft,
                                            Unsafe unsafe) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        Window window = (Window) unsafe.allocateInstance(Window.class);
        set(window, "width", 640); set(window, "height", 360);
        set(window, "guiScaledWidth", 640); set(window, "guiScaledHeight", 360);
        MouseHandler mouse = (MouseHandler) unsafe.allocateInstance(MouseHandler.class);
        set(mouse, "xpos", 400.6); set(mouse, "ypos", 300.2);
        set(minecraft, "window", window); set(minecraft, "mouseHandler", mouse);
        try {
            if (!selection.enabled()) selection.enable();
            set(controller, "polygonMode", true);
            controller.beforeRender(400, 300);
            GridPoint cursor = (GridPoint) get(controller, "polygonCursor");
            require(new GridPoint(81, 120).equals(cursor),
                    "Polygon preview used the whole render pixel instead of the click position: " + cursor);
        } finally {
            set(minecraft, "window", null); set(minecraft, "mouseHandler", null);
            set(controller, "polygonMode", false); set(controller, "polygonCursor", null);
            selection.restore(initial);
        }
        System.out.println("PASS: map previews follow the fractional cursor used by clicks");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object dialog(ExactSelectionController controller, String name) throws Exception {
        return Enum.valueOf((Class) field(controller.getClass(), "dialog").getType(), name);
    }

    private static void checkWaypointRows(ExactSelectionController controller) throws Exception {
        List<WaypointAccess.Point> points = new ArrayList<>();
        for (int i = 0; i < 30; i++) points.add(new WaypointAccess.Point(new Object(), i, 64, i, "Point " + i, false, true));
        set(controller, "pending", new WaypointAccess.Snapshot(new Object(), new Object(), "set", points));
        set(controller, "dialog", dialog(controller, "WAYPOINTS"));
        set(controller, "modalScroll", 0);
        call(controller, "updateVisibility");
        Set<?> chosen = (Set<?>) get(controller, "chosenPoints");
        chosen.clear();
        try {
            // 640x360: dialog at (40, 15), 330 high, 15 visible rows starting at y + 81.
            controller.mouseClicked(left(60, 15 + 81 + 14 * 14 + 5, 0), false);
            require(chosen.size() == 1 && chosen.contains(points.get(14).identity()), "Last visible waypoint row is not clickable");
            controller.mouseClicked(left(60, 15 + 81 + 15 * 14 + 1, 0), false);
            require(chosen.size() == 1, "Clicking below the waypoint list toggled a hidden row");
        } finally {
            chosen.clear();
            set(controller, "pending", null);
            set(controller, "dialog", dialog(controller, "NONE"));
            call(controller, "updateVisibility");
        }
        System.out.println("PASS: waypoint rows accept clicks only where they are drawn");
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, minecraft);
        FixedFont font = new FixedFont();
        set(minecraft, "font", font);
        GuiMap map = (GuiMap) unsafe.allocateInstance(GuiMap.class);
        set(map, "minecraft", minecraft);
        set(map, "font", font);
        for (String name : List.of("children", "narratables", "renderables")) set(map, name, new ArrayList<>());
        map.width = 640; map.height = 360;
        Path directory = Files.createTempDirectory("exact-selection-ui-");
        try {
            MapBridge.Context context = new MapBridge.Context(new Object(), "ui-check", null, "main");
            MapBridge bridge = new MapBridge() {
                @Override public Frame exactselection$frame() { return new Frame(context, 0, 0, 1, 1, map.width, map.height); }
                @Override public MapProcessor exactselection$processor() { return null; }
                @Override public void exactselection$pan(double x, double y) { }
                @Override public void exactselection$clearNativeInput() { }
            };
            SavedAreaStore store = new SavedAreaStore(directory);
            store.useContext(context);
            var a = SavedAreaStore.create("First", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
            var b = SavedAreaStore.create("Second", new SelectionBounds(20, 20, 30, 30), 0x654321, 50, null);
            store.replace(new SavedAreaStore.State(List.of(a, b), List.of(), true, true));
            SavedAreaStore minimapStore = new SavedAreaStore(directory);
            minimapStore.useContext(context);
            ExactSelectionController controller = new ExactSelectionController(map, bridge, minecraft, store, null);
            controller.init();
            SelectionState selection = (SelectionState) get(controller, "state");
            selection.enable(); selection.set(new SelectionBounds(40, 40, 50, 50));
            set(controller, "context", context); set(controller, "historyContext", context);
            call(controller, "updateVisibility");
            checkSelectionPanel(controller, map);
            checkExportNotice(controller, map, directory);
            checkPolygonEditing(controller, map, store, directory, context);
            checkSavedLabelLayering(controller, store, unsafe);
            checkPolygonInsertionAndUpdate(controller, map, store, directory, context);
            checkGeometryGestures(controller, map, unsafe);
            checkPrecisePointer(controller, minecraft, unsafe);
            checkWaypointRows(controller);
            press(controller, "groupsButton");
            require(window(controller).equals("GROUPS"), "Groups did not open separately");
            checkLayout(controller);
            press(controller, "groupCreate");
            require(window(controller).equals("GROUP_NAME"), "Group name dialog did not open");
            require(!((Button) get(controller, "modalApply")).active, "An empty group name can be submitted");
            enter(controller, map, 5, "Buildings");
            require(store.state().groups().size() == 1 && window(controller).equals("GROUPS"), "First group was not created");
            String firstGroup = store.state().groups().get(0).id();
            press(controller, "groupCreate");
            enter(controller, map, 5, "Shops");
            require(store.state().groups().size() == 2, "Creating while a group is selected renamed it instead");
            String secondGroup = store.state().groups().get(1).id();
            press(controller, "groupRename");
            enter(controller, map, 5, "Stores");
            require(store.state().groups().get(1).name().equals("Stores") && store.state().groups().get(1).id().equals(secondGroup), "Group rename changed identity");
            press(controller, "managementClose");
            press(controller, "areasButton");
            checkLayout(controller);
            enter(controller, map, 4, "Renamed first");
            require(store.state().areas().size() == 2 && store.state().areas().get(0).name().equals("Renamed first"), "Enter in area name created an area instead of renaming it");
            press(controller, "groupAssign");
            require(window(controller).equals("ASSIGN_GROUP"), "Group chooser did not open");
            checkLayout(controller);
            row(controller, 2, false);
            require(store.state().areas().get(0).groupId().equals(secondGroup), "Clicked group was not assigned");
            press(controller, "groupAssign"); row(controller, 0, false);
            require(store.state().areas().get(0).groupId() == null, "No group did not remove the assignment");
            press(controller, "groupAssign"); row(controller, 2, false);
            press(controller, "groupAssign"); press(controller, "managementClose");
            require(secondGroup.equals(store.state().areas().get(0).groupId()), "Cancel changed the area group");
            row(controller, 1, true);
            require(((Set<?>) get(controller, "selectedAreaIds")).size() == 2, "Checkbox did not add the second area");
            press(controller, "groupAssign");
            row(controller, 1, false);
            require(store.state().areas().stream().allMatch(area -> firstGroup.equals(area.groupId())), "Bulk assignment missed an area");
            press(controller, "manageGroups");
            row(controller, 0, false);
            press(controller, "groupVisibility");
            require(!store.state().groups().get(0).visible(), "Group hide did not persist");
            require(store.state().areas().stream().allMatch(SavedAreaStore.Area::visible), "Group hiding destroyed individual visibility");
            minimapStore.useContext(context);
            require(minimapStore.state().equals(store.state()), "Minimap store did not refresh after hiding a group");
            press(controller, "managementClose");
            press(controller, "areaVisibility");
            require(store.state().areas().stream().noneMatch(SavedAreaStore.Area::visible), "Bulk area hide did not persist");
            press(controller, "areaVisibility");
            require(store.state().areas().stream().allMatch(SavedAreaStore.Area::visible), "Bulk area show did not persist");
            press(controller, "groupAssign");
            press(controller, "manageGroups");
            press(controller, "groupCreate");
            text(controller, 5).setValue("Created from chooser");
            controller.init();
            require(text(controller, 5).getValue().equals("Created from chooser"), "Resize lost the group name draft");
            press(controller, "modalApply");
            String thirdGroup = store.state().groups().get(2).id();
            press(controller, "managementClose");
            require(window(controller).equals("ASSIGN_GROUP"), "Returning from group creation lost the assignment flow");
            row(controller, 3, false);
            require(store.state().areas().stream().allMatch(area -> thirdGroup.equals(area.groupId())), "New group assignment failed");
            set(controller, "polygonMode", true);
            List<?> draft = (List<?>) get(controller, "polygonDraft");
            List.class.getMethod("add", Object.class).invoke(draft, new GridPoint(70, 70));
            press(controller, "managementUndo");
            require(draft.size() == 1, "Management undo removed a polygon vertex instead of undoing the area operation");
            set(controller, "polygonMode", false); draft.clear();
            require(store.state().areas().stream().allMatch(area -> firstGroup.equals(area.groupId())), "Undo did not restore group assignment");
            press(controller, "managementRedo");
            press(controller, "manageGroups");
            press(controller, "groupDelete");
            checkLayout(controller);
            press(controller, "cancel");
            require(store.state().groups().size() == 3, "Cancel removed a group");
            press(controller, "groupDelete"); press(controller, "confirm");
            require(store.state().groups().size() == 2 && store.state().areas().stream().allMatch(area -> area.groupId() == null), "Deleting a group failed to preserve and ungroup its areas");
            press(controller, "managementUndo");
            require(store.state().groups().size() == 3 && store.state().areas().stream().allMatch(area -> thirdGroup.equals(area.groupId())), "Undo group deletion failed");
            press(controller, "managementClose");
            press(controller, "areaSave");
            enter(controller, map, 8, "Third area");
            require(store.state().areas().size() == 3 && ((Set<?>) get(controller, "selectedAreaIds")).size() == 1, "Saved area was not selected");
            SavedAreaStore reloaded = new SavedAreaStore(directory);
            reloaded.useContext(context);
            require(reloaded.state().equals(store.state()), "Group operations were lost after reloading saved data");
            for (int[] size : List.of(new int[]{320, 240}, new int[]{480, 270}, new int[]{960, 540})) {
                map.width = size[0]; map.height = size[1];
                controller.init();
                checkLayout(controller);
                press(controller, "groupAssign"); checkLayout(controller);
                press(controller, "managementClose");
                press(controller, "areaSave"); checkLayout(controller);
                press(controller, "modalCancel");
                press(controller, "manageGroups"); checkLayout(controller);
                press(controller, "groupCreate"); checkLayout(controller);
                press(controller, "modalCancel");
                press(controller, "managementClose");
            }
            System.out.println("PASS: management UI callbacks, separate windows, layouts, assignment, visibility, persistence, and undo");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
