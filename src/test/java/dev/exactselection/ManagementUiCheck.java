package dev.exactselection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.Set;
import net.minecraft.SharedConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyboardHandler;
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

    private static final class ClipboardKeyboard extends KeyboardHandler {
        String copied;
        private ClipboardKeyboard() { super(null); }
        @Override public void setClipboard(String text) { copied = text; }
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

    private static Object call(Object object, String name, int value) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, int.class);
        method.setAccessible(true);
        return method.invoke(object, value);
    }

    private static Object call(Object object, String name, int a, int b, int c) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, int.class, int.class, int.class);
        method.setAccessible(true);
        return method.invoke(object, a, b, c);
    }

    private static Object call(Object object, String name, boolean value) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, boolean.class);
        method.setAccessible(true);
        return method.invoke(object, value);
    }

    private static void press(ExactSelectionController controller, String name) throws Exception {
        press(controller, (Button) get(controller, name), name);
    }

    private static void press(ExactSelectionController controller, Button button, String name) throws Exception {
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

    /** Checks only {@code id} in the open Areas window, as a click on its row name would. */
    private static void pick(ExactSelectionController controller, SavedAreaStore store, String id) throws Exception {
        List<SavedAreaStore.Area> areas = store.state().areas();
        for (int i = 0; i < areas.size(); i++) if (areas.get(i).id().equals(id)) { row(controller, i, false); return; }
        throw new AssertionError("No area " + id);
    }

    /** Legacy flows expect immediate writes: autosave every property except the territory shape. */
    private static void autosaveProperties(ExactSelectionController controller) throws Exception {
        AutosaveSettings autosave = (AutosaveSettings) get(controller, "autosave");
        if (!autosave.enabled()) autosave.setEnabled(true);
        if (autosave.includes(AutosaveSettings.Field.GEOMETRY)) autosave.toggle(AutosaveSettings.Field.GEOMETRY);
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

    private static void checkHintFitting(ExactSelectionController controller) throws Exception {
        Method fit = ExactSelectionController.class.getDeclaredMethod("fitHint", String.class, int.class);
        fit.setAccessible(true);
        String hint = "Ctrl/Cmd: двигать вершины · Двойной ЛКМ по стороне: добавить · Ctrl/Cmd+двойной ЛКМ: удалить";
        require(fit.invoke(controller, hint, 240).equals("Ctrl/Cmd: двигать вершины"), "Panel hint cut a word instead of dropping whole hints");
        require(fit.invoke(controller, hint, 404).equals("Ctrl/Cmd: двигать вершины · Двойной ЛКМ по стороне: добавить"),
                "Expanded panel hint did not keep every whole hint that fits");
        String narrow = (String) fit.invoke(controller, hint, 60);
        require(narrow.endsWith("…") && narrow.length() * 6 <= 60, "Overlong single hint is not shortened with an ellipsis");
        Button save = (Button) get(controller, "quickSave"), mode = (Button) get(controller, "panelModeButton");
        // The hint line is drawn at PANEL_Y + 22 with 9-pixel text; buttons must stay clear of it.
        require(mode.getY() + mode.getHeight() <= 26 + 22 && save.getY() == mode.getY()
                        && save.getY() + save.getHeight() <= 26 + 22 && save.getX() + save.getWidth() < mode.getX(),
                "Save is not beside the mode button above the hint line");
        System.out.println("PASS: panel hints keep whole phrases and stay clear of the header buttons");
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

    private static MouseButtonEvent right(int x, int y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0));
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
        require(selection.phase() == SelectionState.Phase.READY && get(controller, "gesture") == null,
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
        require(((MapGesture.VertexDrag) get(controller, "gesture")).invalid, "Duplicate closed vertex was accepted");
        controller.mouseReleased(left(400, 260, GLFW.GLFW_MOD_SUPER));
        require(selection.geometry().equals(moved), "Rejected vertex edit did not restore original geometry");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(450, 290);
        require(((MapGesture.VertexDrag) get(controller, "gesture")).invalid, "Self-intersecting vertex move was accepted");
        controller.mouseReleased(left(450, 290, GLFW.GLFW_MOD_CONTROL));
        require(selection.geometry().equals(moved), "Self-intersection rejection changed the completed area");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(30_000_400, 100);
        require(((MapGesture.VertexDrag) get(controller, "gesture")).invalid, "World-boundary vertex move was accepted");
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
        press(controller, "quickSave");
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
        press(controller, "areasButton"); pick(controller, store, area.id()); press(controller, "areaLoad");
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
        Button update = (Button) get(controller, "quickSave");
        require(update.visible && update.active, "Polygon geometry update is unavailable");
        press(controller, "quickSave");
        require(window(controller).equals("NONE") && store.state().areas().get(0).equals(area.withGeometry(inserted)),
                "Update geometry lost polygon vertices or area metadata");
        SavedAreaStore reload = new SavedAreaStore(directory); reload.useContext(context);
        require(reload.state().equals(store.state()), "Updated vertices with unchanged bounds were not persisted");
        press(controller, "areasButton"); press(controller, "managementUndo");
        require(store.state().areas().get(0).equals(area), "Geometry update undo did not restore the saved polygon");
        press(controller, "managementRedo"); pick(controller, store, area.id());
        Button edit = (Button) get(controller, "areaLoad");
        require(!edit.active && edit.getMessage().getString().equals(Component.translatable("exactselection.area_editing_now").getString()),
                "The territory being edited can be loaded again instead of showing that it is being edited");
        press(controller, "managementClose");
        controller.mouseClicked(left(480, 60, GLFW.GLFW_MOD_SUPER), false);
        controller.mouseReleased(left(480, 40, GLFW.GLFW_MOD_SUPER));
        PolygonGeometry moved = (PolygonGeometry) selection.geometry();
        require(moved.vertices().get(1).equals(new GridPoint(160, -140)), "Inserted vertex cannot be moved with Cmd");
        press(controller, "quickSave");
        require(store.state().areas().get(0).equals(area.withGeometry(moved)), "Moved inserted vertex did not update saved geometry");
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
            // Names off: the areas are still drawn, without a single label.
            store.replace(new SavedAreaStore.State(List.of(area, overlapping), List.of(), true, true, false));
            RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
            graphics.events = new ArrayList<>();
            graphics.transform = new Matrix3x2fStack(16);
            graphics.state = new GuiRenderState();
            controller.renderMapOverlays(graphics, 600, 330);
            List<GuiElementRenderState> draws = new ArrayList<>();
            graphics.state.forEachElement(draws::add, GuiRenderState.TraverseRange.ALL);
            require(draws.size() == 4 && graphics.events.stream().noneMatch(event -> event.text() != null),
                    "Hidden area names were still drawn, or the areas vanished with them");
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

        // Like a photo editor: a plain drag inside moves the selection; a click inside without moving keeps it.
        selection.set(new SelectionBounds(80, 80, 239, 239));
        controller.mouseClicked(left(450, 300, 0), false);
        controller.mouseReleased(left(480, 300, 0));
        require(selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)), "A plain drag inside did not move the selection");
        controller.mouseClicked(left(450, 300, 0), false);
        controller.mouseReleased(left(450, 300, 0));
        require(selection.phase() == SelectionState.Phase.SELECTED && selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A click inside the selection started a new one");
        selection.reset(); call(controller, "updateVisibility");
        for (String name : List.of("sizeButton", "moveButton", "centerButton"))
            require(!((Button) get(controller, name)).active, name + " works without a selection");
        selection.set(new SelectionBounds(80, 80, 239, 239)); call(controller, "updateVisibility");
        require(((Button) get(controller, "moveButton")).active, "Move is unavailable for a finished selection");

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

    private static SelectionBounds dragHandle(ExactSelectionController controller, SelectionBounds start,
                                              int fromX, int fromY, int toX, int toY) {
        SelectionState selection = (SelectionState) uncheckedGet(controller, "state");
        selection.set(start);
        controller.mouseClicked(left(fromX, fromY, 0), false);
        controller.mouseReleased(left(toX, toY, 0));
        return selection.bounds();
    }

    private static void checkGestureEdgeCases(ExactSelectionController controller, MapBridge.Context context) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        // Frame: scale 1 at the origin, so screen = world + (320, 180). Blocks X 0..15 span screen 320..336.
        SelectionBounds chunk = new SelectionBounds(0, 100, 15, 115);
        require(dragHandle(controller, chunk, 320, 288, 352, 288).equals(new SelectionBounds(16, 100, 31, 115)),
                "Dragging the west edge past the east edge changed the width: " + selection.bounds());
        require(dragHandle(controller, new SelectionBounds(16, 100, 31, 115), 352, 288, 320, 288).equals(chunk),
                "Dragging the east edge past the west edge changed the width: " + selection.bounds());
        require(dragHandle(controller, chunk, 328, 280, 328, 312).equals(new SelectionBounds(0, 116, 15, 131)),
                "Dragging the north edge past the south edge changed the length: " + selection.bounds());
        require(dragHandle(controller, chunk, 320, 288, 336, 288).equals(new SelectionBounds(16, 100, 16, 115)),
                "Dragging onto the opposite edge did not keep the one block under the cursor: " + selection.bounds());
        set(controller, "chunks", true);
        try {
            require(dragHandle(controller, chunk, 320, 288, 352, 288).equals(new SelectionBounds(16, 100, 31, 115)),
                    "Chunk mode lost the 16-block grid after crossing edges: " + selection.bounds());
        } finally { set(controller, "chunks", false); }

        // Losing the map context while a handle is held must end the gesture instead of crashing on release.
        selection.set(chunk);
        controller.mouseClicked(left(336, 288, 0), false);
        Object grabbed = get(controller, "gesture");
        require(grabbed instanceof MapGesture.Drag d && d.handle == MapGesture.Handle.EAST, "East handle was not grabbed");
        MapGesture.Drag drag = (MapGesture.Drag) grabbed;
        set(controller, "context", new MapBridge.Context(new Object(), "other", null, "main"));
        require(controller.mouseReleased(left(360, 288, 0)), "Release after a context change was not consumed");
        require(get(controller, "gesture") == null && !selection.enabled(),
                "Context change did not end the drag");
        MapGesture.Host host = (MapGesture.Host) get(controller, "gestureHost");
        SelectionState.Snapshot afterLoss = selection.snapshot();
        require(!drag.finish(host) && !drag.cancel(host) && selection.snapshot().equals(afterLoss),
                "A dropped gesture still committed or restored the selection");
        set(controller, "context", context);
        selection.restore(initial);

        // A double click within the hit tolerance inserts the vertex on the side and keeps the shape.
        PolygonGeometry square = new PolygonGeometry(List.of(new GridPoint(0, 100), new GridPoint(40, 100),
                new GridPoint(40, 140), new GridPoint(0, 140)));
        selection.setGeometry(square);
        doubleClickMap(controller, 340, 285, 0);
        PolygonGeometry inserted = (PolygonGeometry) selection.geometry();
        require(inserted.vertices().size() == 5 && inserted.vertices().get(1).equals(new GridPoint(20, 100))
                && inserted.areaTwice() == square.areaTwice(), "Inserted vertex bent the side: " + inserted.vertices());
        ((SelectionHistory) get(controller, "history")).clear();
        selection.restore(initial);
        call(controller, "syncFields"); call(controller, "updateVisibility");
        System.out.println("PASS: edge crossing keeps sizes, release after context loss, and on-side vertex insertion");
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

    private static void checkReleaseFeatures(ExactSelectionController controller, GuiMap map,
                                              SavedAreaStore store) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        List<GridPoint> draft = (List<GridPoint>) get(controller, "polygonDraft");
        try {
            selection.setGeometry(new RectangleGeometry(new SelectionBounds(80, -120, 240, 80)));
            call(controller, "syncFields"); call(controller, "updateVisibility");
            require(((Button) get(controller, "quickSave")).visible, "Ready selection has no quick save");
            require(get(get(get(controller, "undo"), "tooltip"), "tooltip") != null
                    && get(get(get(controller, "redo"), "tooltip"), "tooltip") != null, "History arrows need tooltips even with no history");
            press(controller, "quickSave");
            require(window(controller).equals("AREA_NAME") && get(controller, "newAreaGroupId") == null,
                    "Quick save did not start with no group");
            checkLayout(controller);
            var groups = List.of(new SavedAreaStore.Group("release-group", "Release group", true));
            store.replace(new SavedAreaStore.State(saved.areas(), groups, true, true));
            press(controller, "newAreaGroup");
            require(get(controller, "newAreaGroupId").equals("release-group"), "New-area group button did not choose the group");
            press(controller, "newAreaOpacityButton");
            require((Integer) get(controller, "newAreaOpacity") == 50, "New-area fill did not advance from 25% to 50%");
            int[] nameRect = (int[]) call(controller, "dialogRect");
            int sample = (Integer) call(controller, "colorSampleWidth", nameRect[2]);
            EditBox nameField = ((EditBox[]) get(controller, "dialogFields"))[8];
            EditBox hexField = ((EditBox[]) get(controller, "dialogFields"))[10];
            int first = (Integer) call(controller, "swatchX", nameRect[0], nameRect[2], 0);
            int last = (Integer) call(controller, "swatchX", nameRect[0], nameRect[2], AreaPalette.COLUMNS - 1) + sample;
            int fieldEnd = nameField.getX() + nameField.getWidth();
            require(first >= nameField.getX() && last <= fieldEnd
                    && Math.abs((first - nameField.getX()) - (fieldEnd - last)) <= 1 && fieldEnd - last < AreaPalette.COLUMNS,
                    "New-area palette is not lined up with the name field");
            require(hexField.visible && hexField.getY() >= nameRect[1] + 98 + 3 * 22 - 4
                    && hexField.getX() + hexField.getWidth() < last - sample, "Custom color field is missing or covers the preview");
            // Second row, second swatch: the palette sits below the group and fill buttons.
            controller.mouseClicked(left((Integer) call(controller, "swatchX", nameRect[0], nameRect[2], 1) + 2,
                    nameRect[1] + 122, 0), false);
            require((Integer) get(controller, "newAreaColor") == AreaPalette.COLORS[9] && window(controller).equals("AREA_NAME")
                    && hexField.getValue().equals(AreaPalette.format(AreaPalette.COLORS[9])),
                    "New-area palette did not pick the clicked color");
            hexField.setValue("#12345");
            require((Integer) get(controller, "newAreaColor") == AreaPalette.COLORS[9], "Incomplete custom color was taken");
            hexField.setValue("#123456");
            require((Integer) get(controller, "newAreaColor") == 0x123456, "Typed custom color was not taken");
            hexField.setValue(AreaPalette.format(AreaPalette.COLORS[9]));
            SelectionState.Snapshot beforeSave = selection.snapshot();
            int countBefore = store.state().areas().size();
            enter(controller, map, 8, "Quick saved");
            SavedAreaStore.Area added = store.state().areas().getLast();
            require(window(controller).equals("NONE") && added.groupId().equals("release-group")
                    && selection.source() == null, "Quick save lost the group or enabled editing implicitly");
            require(added.color() == AreaPalette.COLORS[9] && added.opacity() == 50, "Saved area ignored the chosen color or fill");
            require(selection.phase() == SelectionState.Phase.READY && selection.bounds() == null,
                    "Selection was not cleared after saving the area");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(selection.snapshot().equals(beforeSave) && store.state().areas().size() == countBefore + 1,
                    "First undo after saving should only bring the selection back");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(store.state().areas().size() == countBefore, "Second undo did not remove the saved area");
            key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL); key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
            require(store.state().areas().getLast().equals(added) && selection.bounds() == null, "Redo did not restore the save and clear");
            press(controller, "areasButton"); press(controller, "areaSelectAll");
            press(controller, "areaColor"); checkLayout(controller);
            enter(controller, map, 10, "invalid");
            require(window(controller).equals("COLOR") && get(controller, "message").toString().contains("color_invalid"),
                    "Invalid RGB color was accepted");
            int[] rect = (int[]) call(controller, "dialogRect");
            controller.mouseClicked(left(rect[0] + 14, rect[1] + 36, 0), false);
            require(window(controller).equals("AREAS") && store.state().areas().stream().allMatch(a -> a.color() == AreaPalette.COLORS[0]),
                    "Palette did not recolor every selected area");
            // Select all makes the last area primary; it was saved at 50%, so every checked area moves to 75%.
            press(controller, "areaOpacity");
            require(store.state().areas().stream().map(SavedAreaStore.Area::opacity).distinct().count() == 1
                    && store.state().areas().get(0).opacity() == 75, "Opacity did not change every selected area");
            press(controller, "areaColor"); enter(controller, map, 10, "#abc");
            require(store.state().areas().stream().allMatch(a -> a.color() == 0xAABBCC), "Custom RGB did not apply to all selected areas");
            row(controller, store.state().areas().size() - 1, false);
            press(controller, "areaLoad");
            require(selection.source().equals(added.id()) && !((Button) get(controller, "quickSave")).active,
                    "Edit did not link the saved area or unchanged geometry can be applied");
            require(((Button) get(controller, "quickSave")).getMessage().getString()
                    .equals(Component.translatable("exactselection.quick_apply").getString()), "Editing button is not labelled Apply");
            clickMap(controller, 400, 60, GLFW.GLFW_MOD_CONTROL);
            require(selection.geometry() instanceof RectangleGeometry, "Stationary Ctrl click converted a rectangle");
            clickMap(controller, 480, 60, 0);
            require(selection.geometry() instanceof RectangleGeometry && selection.phase() == SelectionState.Phase.SELECTED,
                    "First edge click destroyed the editing selection");
            controller.mouseClicked(left(480, 60, 0), true); controller.mouseReleased(left(480, 60, 0));
            require(selection.geometry() instanceof PolygonGeometry && selection.geometry().vertices().size() == 5
                    && selection.source().equals(added.id()), "Rectangle side insertion did not retain its editing source");
            press(controller, "quickSave");
            require(store.state().areas().getLast().geometry().equals(selection.geometry()), "Apply did not save the linked area");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(store.state().areas().getLast().geometry() instanceof RectangleGeometry, "Apply undo lost the original rectangle");
            doubleClickMap(controller, 480, 60, GLFW.GLFW_MOD_CONTROL);
            require(selection.geometry().vertices().size() == 4, "Ctrl double click did not delete a polygon vertex");
            doubleClickMap(controller, 400, 60, GLFW.GLFW_MOD_SUPER);
            require(selection.geometry().vertices().size() == 3, "Cmd double click did not delete a polygon vertex");
            AreaGeometry triangle = selection.geometry();
            doubleClickMap(controller, 561, 60, GLFW.GLFW_MOD_CONTROL);
            require(selection.geometry().equals(triangle), "Deleting a triangle vertex was allowed");
            press(controller, "clear"); press(controller, "polygonModeButton");
            clickMap(controller, 400, 60, 0); clickMap(controller, 560, 60, 0); clickMap(controller, 560, 260, 0);
            doubleClickMap(controller, 560, 60, GLFW.GLFW_MOD_CONTROL);
            require(draft.size() == 2, "Ctrl double click did not delete a draft vertex");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(draft.size() == 3, "Draft vertex deletion cannot be undone");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            selection.set(new SelectionBounds(80, -120, 240, 80)); call(controller, "updateVisibility");
            controller.mouseClicked(left(400, 60, GLFW.GLFW_MOD_CONTROL), false);
            controller.mouseReleased(left(410, 50, GLFW.GLFW_MOD_CONTROL));
            require(selection.geometry() instanceof PolygonGeometry
                    && selection.geometry().vertices().get(0).equals(new GridPoint(90, -130)), "Ctrl corner drag did not convert the rectangle");
            for (int[] size : List.of(new int[]{320, 240}, new int[]{480, 270}, new int[]{960, 540})) {
                map.width = size[0]; map.height = size[1]; controller.init();
                press(controller, "areasButton"); press(controller, "areaSelectAll");
                press(controller, "areaColor"); checkLayout(controller); key(controller, GLFW.GLFW_KEY_ESCAPE, 0); require(!window(controller).equals("COLOR"), "Esc did not close the color window");
                press(controller, "areaSave"); checkLayout(controller); press(controller, "modalCancel");
                press(controller, "managementClose");
            }
            require(new RectangleGeometry(new SelectionBounds(0, 0, 2, 5)).perimeter() == 18, "Incorrect rectangle perimeter");
            System.out.println("PASS: quick save and groups, linked Apply/undo, bulk palette/hex/opacity, vertex deletion and rectangle conversion");
        } finally {
            store.replace(saved); selection.restore(initial); draft.clear();
            set(controller, "polygonMode", false); set(controller, "gesture", null);
            set(controller, "areaId", null); ((Set<?>) get(controller, "selectedAreaIds")).clear();
            set(controller, "dialog", dialog(controller, "NONE")); set(controller, "message", "");
            ((SelectionHistory) get(controller, "history")).clear();
            ((java.util.Deque<?>) get(controller, "polygonDraftUndo")).clear();
            ((java.util.Deque<?>) get(controller, "polygonDraftRedo")).clear();
            map.width = 640; map.height = 360; controller.init();
        }
    }

    private static final class FakeWaypoints implements WaypointAccess {
        final Object world = new Object(), set = new Object();
        final List<Point> points = new ArrayList<>();
        FakeWaypoints() {
            points.add(new Point(new Object(), 0, 64, 0, "First", false, true));
            points.add(new Point(new Object(), 10, 64, 10, "Second", false, true));
            points.add(new Point(new Object(), 100, 64, 100, "Outside", false, true));
        }
        @Override public Snapshot all(MapBridge.Context context) { return new Snapshot(world, set, "test", points); }
        @Override public Snapshot select(MapBridge.Context context, SelectionBounds bounds) {
            return select(context, new RectangleGeometry(bounds));
        }
        @Override public Snapshot select(MapBridge.Context context, AreaGeometry geometry) {
            return new Snapshot(world, set, "test", points.stream().filter(p -> geometry.containsBlockCenter(p.x(), p.z())).toList());
        }
        @Override public Snapshot captureSet(Snapshot snapshot) {
            return snapshot == null ? null : new Snapshot(world, set, "test", snapshot.points(), points);
        }
        @Override public int colorRgb(int index) { return index * 0x10101; }
        @Override public void edit(Snapshot snapshot, List<Point> from, List<Point> to) {
            for (Point p : from) if (!points.contains(p)) throw new IllegalStateException("stale point");
            for (int i = 0; i < from.size(); i++) points.set(points.indexOf(from.get(i)), to.get(i));
        }
        @Override public void delete(Snapshot snapshot) { points.removeAll(snapshot.points()); }
        @Override public void restore(Snapshot snapshot) { points.addAll(snapshot.points()); }
        @Override public Point create(MapBridge.Context c, int x, int y, int z, String n) { throw new UnsupportedOperationException(); }
        @Override public Point recreate(MapBridge.Context c, Point p) { throw new UnsupportedOperationException(); }
        @Override public void removeCreated(MapBridge.Context c, Point p) { throw new UnsupportedOperationException(); }
    }

    private static void checkWaypointManagement(GuiMap map, MapBridge bridge, Minecraft minecraft,
                                                SavedAreaStore store, MapBridge.Context context) throws Exception {
        FakeWaypoints access = new FakeWaypoints();
        ExactSelectionController controller = new ExactSelectionController(map, bridge, minecraft, store, access);
        autosaveProperties(controller);
        controller.init();
        SelectionState selection = (SelectionState) get(controller, "state");
        selection.enable(); set(controller, "context", context); set(controller, "historyContext", context);
        set(controller, "compact", false); call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
        try {
            press(controller, "pointsButton"); checkLayout(controller);
            require(((Set<?>) get(controller, "chosenPoints")).isEmpty(), "Opening all waypoints selected the entire set");
            require(!((Button) get(controller, "confirm")).active, "Entire set can be deleted without explicit selection");
            int[] rect = (int[]) call(controller, "dialogRect");
            controller.mouseClicked(left(rect[0] + 20, rect[1] + 85, 0), false);
            enter(controller, map, 9, "Renamed");
            require(access.points.get(0).name().equals("Renamed") && access.points.get(1).name().equals("Second")
                    && window(controller).equals("WAYPOINTS"), "Waypoint rename changed unselected points or closed the window");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(access.points.get(0).name().equals("First"), "Waypoint rename undo failed");
            call(controller, "refreshPending", false);
            press(controller, "pointRecolor"); checkLayout(controller);
            rect = (int[]) call(controller, "dialogRect");
            controller.mouseClicked(left((Integer) call(controller, "swatchX", rect[0], rect[2], 2) + 2, rect[1] + 36, 0), false);
            require(access.points.get(0).color() == 2 && access.points.get(1).color() == 0
                    && window(controller).equals("WAYPOINTS"), "Waypoint palette did not edit only checked rows");
            access.points.set(0, access.points.get(0).with("External", 2));
            enter(controller, map, 9, "Stale attempt");
            require(access.points.get(0).name().equals("External") && get(controller, "message").toString().contains("changed"),
                    "Stale waypoint edit was not rejected");
            press(controller, "confirm");
            require(access.points.size() == 2 && window(controller).equals("WAYPOINTS"), "Waypoint delete did not retain the management window");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(access.points.size() == 3, "Waypoint deletion cannot be undone from its window");
            press(controller, "cancel");
            selection.set(new SelectionBounds(-1, -1, 15, 15)); call(controller, "updateSelection"); call(controller, "updateVisibility");
            press(controller, "pointsButton");
            require(((Set<?>) get(controller, "chosenPoints")).size() == 2, "Opening selection did not select its two waypoints");
            press(controller, "pointScope");
            require(((Set<?>) get(controller, "chosenPoints")).size() == 2, "Entire-set switch unexpectedly selected more points");
            press(controller, "pointScope");
            require(((Set<?>) get(controller, "chosenPoints")).size() == 2, "Selection scope did not select the contained points");
            text(controller, 7).setValue("Second");
            enter(controller, map, 9, "Filtered");
            require(access.points.stream().filter(p -> p.name().equals("Filtered")).count() == 1
                    && access.points.stream().anyMatch(p -> p.name().equals("External")),
                    "Waypoint rename ignored the name filter");
            text(controller, 7).setValue(""); call(controller, "updateVisibility");
            for (int[] size : List.of(new int[]{320, 240}, new int[]{480, 270}, new int[]{960, 540})) {
                map.width = size[0]; map.height = size[1]; controller.init(); checkLayout(controller);
                press(controller, "pointRecolor"); checkLayout(controller); key(controller, GLFW.GLFW_KEY_ESCAPE, 0); require(!window(controller).equals("COLOR"), "Esc did not close the color window");
            }
            press(controller, "cancel");
            System.out.println("PASS: safe entire-set waypoint management, rename/color/delete, undo, stale snapshot guards, scope and responsive layouts");
        } finally {
            List<AbstractWidget> added = (List<AbstractWidget>) get(controller, "widgets");
            map.children().removeIf(added::contains);
            map.width = 640; map.height = 360;
        }
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
            // 640x360: dialog at (40, 15), 330 high, 12 visible rows starting at y + 81.
            controller.mouseClicked(left(60, 15 + 81 + 11 * 14 + 5, 0), false);
            require(chosen.size() == 1 && chosen.contains(points.get(11).identity()), "Last visible waypoint row is not clickable");
            controller.mouseClicked(left(60, 15 + 81 + 12 * 14 + 1, 0), false);
            require(chosen.size() == 1, "Clicking below the waypoint list toggled a hidden row");
            // 30 rows, 12 visible: the scrollbar sits at x + w - 17 .. x + w - 7 = 583..593 beside the rows.
            controller.mouseClicked(left(588, 15 + 81 + 12 * 14 - 1, 0), false);
            require((Integer) get(controller, "modalScroll") == 18 && chosen.size() == 1,
                    "Clicking the bottom of the scrollbar did not scroll to the end (or toggled a row)");
            controller.mouseReleased(left(588, 15 + 81, 0));
            require((Integer) get(controller, "modalScroll") == 0 && !(Boolean) get(controller, "scrollbarDragging"),
                    "Dragging the scrollbar thumb to the top did not scroll back");
        } finally {
            chosen.clear();
            set(controller, "pending", null);
            set(controller, "dialog", dialog(controller, "NONE"));
            call(controller, "updateVisibility");
        }
        System.out.println("PASS: waypoint rows accept clicks only where they are drawn");
    }

    private static void checkFramedAreasAndMetrics(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        var inside = SavedAreaStore.create("Inside", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
        var touching = SavedAreaStore.create("Touching", new SelectionBounds(21, 0, 30, 10), 0x123456, 25, null);
        var far = SavedAreaStore.create("Far", new PolygonGeometry(List.of(new GridPoint(100, 100), new GridPoint(120, 100),
                new GridPoint(100, 120))), 0x123456, 25, null);
        var crossing = SavedAreaStore.create("Crossing", new PolygonGeometry(List.of(new GridPoint(15, -10), new GridPoint(40, -10),
                new GridPoint(15, 15))), 0x123456, 25, null);
        try {
            store.replace(new SavedAreaStore.State(List.of(inside, touching, far, crossing), List.of(), true, true));
            selection.set(new SelectionBounds(-5, -5, 20, 20));
            press(controller, "panelModeButton");
            Button zones = (Button) get(controller, "zonesButton"), areas = (Button) get(controller, "areasButton");
            require(areas.visible && zones.visible && zones.active, "Expanded panel lost Areas or Areas in selection");
            require((Integer) get(controller, "metricsShift") == -12, "Perimeter did not join the area line in the wide panel");
            press(controller, "zonesButton");
            require(window(controller).equals("AREAS")
                    && get(controller, "selectedAreaIds").equals(new java.util.LinkedHashSet<>(List.of(inside.id(), crossing.id()))),
                    "Areas in selection must check overlapping areas only, not touching or distant ones");
            press(controller, "managementClose");
            press(controller, "panelModeButton");
            require((Integer) get(controller, "metricsShift") == 0, "Perimeter shared a line that is too narrow in the compact panel");
        } finally {
            store.replace(saved); selection.restore(initial);
            if (!(Boolean) get(controller, "compact")) press(controller, "panelModeButton");
            ((Set<?>) get(controller, "selectedAreaIds")).clear();
            call(controller, "syncFields"); call(controller, "updateVisibility");
        }
        System.out.println("PASS: Areas stays in the expanded panel, areas in selection by overlap, perimeter beside area when it fits");
    }

    @SuppressWarnings("unchecked")
    /** Right click inside the selection: quick actions for the territory under edit, or save and clear for a new one. */
    private static void checkQuickMenu(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        try {
            selection.enable();
            selection.set(new SelectionBounds(80, 80, 239, 239)); call(controller, "updateVisibility");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(490, 300));
            require(!(Boolean) get(controller, "quickMenu"), "Panning with the right button opened the quick menu");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            require((Boolean) get(controller, "quickMenu") && ((Button) get(controller, "quickMenuSave")).visible
                    && !((Button) get(controller, "quickMenuDelete")).visible, "A new selection did not offer Save and Clear");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(!(Boolean) get(controller, "quickMenu") && selection.bounds() != null, "Esc did not just close the quick menu");

            var area = SavedAreaStore.create("Edited", new SelectionBounds(80, 80, 239, 239), 0x123456, 25, null);
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            selection.load(area.geometry(), area.id()); call(controller, "updateVisibility");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            press(controller, "quickMenuOpacity");
            require(store.state().areas().get(0).opacity() == 50 && (Boolean) get(controller, "quickMenu"),
                    "Quick fill did not apply at once or closed the menu");
            press(controller, "quickMenuColor");
            require(window(controller).equals("COLOR"), "Quick color did not open the palette");
            controller.mouseClicked(left((Integer) call(controller, "swatchX", ((int[]) call(controller, "dialogRect"))[0],
                    ((int[]) call(controller, "dialogRect"))[2], 2) + 2, ((int[]) call(controller, "dialogRect"))[1] + 36, 0), false);
            require(window(controller).equals("NONE") && store.state().areas().get(0).color() == AreaPalette.COLORS[2],
                    "A quick color pick was not applied to the territory");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            press(controller, "quickMenuDelete");
            require(store.state().areas().isEmpty() && selection.bounds() == null, "Quick delete kept the zone or the selection");
            call(controller, "updateVisibility");
            press(controller, "undo"); press(controller, "undo");
            require(store.state().areas().size() == 1 && selection.source() != null, "Undo did not bring the deleted zone back");
        } finally {
            set(controller, "quickMenu", false);
            store.replace(saved); selection.restore(initial);
            ((SelectionHistory) get(controller, "history")).clear();
            call(controller, "syncFields"); call(controller, "updateVisibility");
        }
        System.out.println("PASS: right-click quick menu: pan does not open it, save/clear for new, fill/color/delete for an edited zone, Esc, undo");
    }

    private static void checkAreaDraftAndAutosave(ExactSelectionController controller, GuiMap map, SavedAreaStore store,
                                                   Unsafe unsafe) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        AutosaveSettings autosave = (AutosaveSettings) get(controller, "autosave");
        Map<AutosaveSettings.Field, Button> fieldButtons = (Map<AutosaveSettings.Field, Button>) get(controller, "autosaveFields");
        var group = new SavedAreaStore.Group("draft-group", "Draft group", true);
        var a = SavedAreaStore.create("Alpha", new SelectionBounds(0, 0, 9, 9), 0x123456, 25, null);
        var b = SavedAreaStore.create("Beta", new SelectionBounds(40, 40, 49, 49), 0x654321, 25, null);
        try {
            store.replace(new SavedAreaStore.State(List.of(a, b), List.of(group), true, true));
            autosave.setEnabled(false);
            press(controller, "areasButton");
            require(((Set<?>) get(controller, "selectedAreaIds")).isEmpty(), "Areas window opened with an area checked");
            press(controller, "areaNames");
            require(!store.state().showNames() && store.state().showOnMap(), "Names switch did not hide only the names");
            call(controller, "updateVisibility");
            press(controller, "managementUndo");
            require(store.state().showNames(), "Undo did not bring the area names back");
            pick(controller, store, a.id());
            text(controller, 4).setValue("Draft name");
            press(controller, "areaOpacity");
            press(controller, "groupAssign"); row(controller, 1, false);
            press(controller, "areaColor");
            int[] rect = (int[]) call(controller, "dialogRect");
            controller.mouseClicked(left(rect[0] + 14, rect[1] + 36, 0), false);
            require(window(controller).equals("AREAS") && store.state().areas().get(0).equals(a),
                    "Changes were written before Save while autosave is off");
            Button save = (Button) get(controller, "areaSaveChanges");
            require(save.visible && save.active, "Save is unavailable for pending changes");
            Button load = (Button) get(controller, "areaLoad"), shown = (Button) get(controller, "areaVisibility");
            Button gear = (Button) get(controller, "autosaveButton");
            Button[] copies = (Button[]) get(controller, "areaCopies");
            call(controller, "layoutDialog");
            require(save.getX() == load.getX() && save.getY() >= load.getY() + load.getHeight()
                    && copies[1].getY() > save.getY() + save.getHeight()
                    && shown.getY() > copies[3].getY() + copies[3].getHeight() + 6,
                    "Save is not under Edit/Export, or Selected is not below the territory's controls");
            require(gear.getWidth() <= 20 && gear.getMessage().getString().isEmpty(), "Autosave settings button is not a gear");
            int depth = ((Deque<?>) get(get(controller, "history"), "undo")).size();
            press(controller, "areaSaveChanges");
            SavedAreaStore.Area written = store.state().areas().get(0);
            require(written.name().equals("Draft name") && written.opacity() == 50 && "draft-group".equals(written.groupId())
                    && written.color() == AreaPalette.COLORS[0] && store.state().areas().get(1).equals(b),
                    "Save did not write every pending property of the checked area only");
            require(((Deque<?>) get(get(controller, "history"), "undo")).size() == depth + 1, "Save took more than one undo step");
            press(controller, "managementUndo");
            require(store.state().areas().get(0).equals(a), "One undo did not restore all saved properties");
            press(controller, "managementRedo");
            text(controller, 4).setValue("Lost name");
            row(controller, 1, false);
            require(store.state().areas().get(0).name().equals("Draft name")
                    && get(controller, "message").toString().contains("area_changes_discarded"),
                    "Switching areas wrote or silently dropped unsaved changes");

            press(controller, "autosaveButton");
            require((Boolean) get(controller, "autosaveOpen") && ((Button) get(controller, "autosaveToggle")).visible
                    && !((Button) get(controller, "groupAssign")).visible, "Autosave settings did not replace the properties");
            checkLayout(controller);
            press(controller, "autosaveToggle");
            require(autosave.enabled() && fieldButtons.values().stream().allMatch(button -> button.active),
                    "Autosave did not switch on with its field toggles");
            fieldButtons.get(AutosaveSettings.Field.NAME).onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            if (!autosave.includes(AutosaveSettings.Field.GEOMETRY)) fieldButtons.get(AutosaveSettings.Field.GEOMETRY).onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            press(controller, "autosaveClose");
            require(!save.visible, "Save stays visible under autosave with nothing pending");
            call(controller, "layoutDialog");
            require(copies[1].getY() == load.getY() + load.getHeight() + 19, "Hidden Save leaves a gap above the area details");
            press(controller, "areaOpacity");
            require(store.state().areas().get(1).opacity() == 50, "Autosaved fill was not written immediately");
            text(controller, 4).setValue("Manual name");
            call(controller, "updateVisibility");
            require(save.visible && store.state().areas().get(1).name().equals("Beta"),
                    "A change outside autosave must keep Save visible and wait for it");
            press(controller, "areaSaveChanges");
            require(store.state().areas().get(1).name().equals("Manual name"), "Save did not write the non-autosaved name");
            call(controller, "layoutDialog");
            for (Button copy : (Button[]) get(controller, "areaCopies")) require(copy.visible && copy.active, "Area copy buttons are missing");
            Button[] small = (Button[]) get(controller, "areaCopies");
            require(small[2].getWidth() <= 12 && small[2].getMessage().getString().isEmpty()
                    && small[5].getY() == small[4].getY() && small[5].getX() < small[4].getX(),
                    "Area copy buttons are not small icons after their values");

            pick(controller, store, a.id());
            press(controller, "areaLoad");
            Button quick = (Button) get(controller, "quickSave");
            require(quick.getMessage().getString().equals(Component.translatable("exactselection.quick_autosaved").getString()),
                    "Panel does not show that the territory shape autosaves");
            EditBox[] coordinates = (EditBox[]) get(controller, "fields");
            coordinates[2].setValue("19"); coordinates[3].setValue("19");
            call(controller, "applyFields");
            SavedAreaStore.Area reshaped = store.state().areas().get(0);
            require(reshaped.bounds().equals(new SelectionBounds(0, 0, 19, 19)), "Edited territory shape was not autosaved");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(store.state().areas().get(0).bounds().equals(new SelectionBounds(0, 0, 9, 9))
                    && selection.bounds().equals(new SelectionBounds(0, 0, 9, 9)),
                    "One undo did not restore both the selection and the autosaved territory");
            // While Alpha is edited its stored shape is drawn faded; Beta keeps its full outline.
            RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
            graphics.events = new ArrayList<>();
            graphics.transform = new Matrix3x2fStack(16);
            graphics.state = new GuiRenderState();
            controller.renderMapOverlays(graphics, 600, 330);
            List<Integer> colors = new ArrayList<>();
            graphics.state.forEachElement(element -> {
                try {
                    Method color = element.getClass().getDeclaredMethod("color");
                    color.setAccessible(true);
                    colors.add((Integer) color.invoke(element));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }, GuiRenderState.TraverseRange.ALL);
            int alpha = store.state().areas().get(0).color();
            require(colors.stream().filter(c -> (c & 0xFFFFFF) == alpha).allMatch(c -> c >>> 24 <= 0x60)
                    && colors.stream().anyMatch(c -> (c & 0xFFFFFF) == alpha),
                    "The stored shape of the edited territory is not faded behind its live bounds");
            int beta = store.state().areas().get(1).color();
            require(colors.stream().anyMatch(c -> c == (0xFF000000 | beta)), "Areas not being edited lost their full outline");

            // Writing another change keeps an unsaved name; a deletion leaves nothing checked.
            press(controller, "areasButton");
            pick(controller, store, b.id());
            text(controller, 4).setValue("Unsaved name");
            press(controller, "areaMapVisibility");
            AreaDraft draft = (AreaDraft) get(controller, "areaDraft");
            require(text(controller, 4).getValue().equals("Unsaved name") && "Unsaved name".equals(draft.name),
                    "Switching the map layer silently dropped the unsaved name");
            press(controller, "areaDelete"); press(controller, "confirm");
            require(store.state().areas().size() == 1 && ((Set<?>) get(controller, "selectedAreaIds")).isEmpty()
                    && get(controller, "areaId") == null && draft.isEmpty() && text(controller, 4).getValue().isEmpty(),
                    "Deleting an area checked another one or kept its draft");
        } finally {
            if (!autosave.enabled()) autosave.setEnabled(true);
            if (!autosave.includes(AutosaveSettings.Field.NAME)) autosave.toggle(AutosaveSettings.Field.NAME);
            if (autosave.includes(AutosaveSettings.Field.GEOMETRY)) autosave.toggle(AutosaveSettings.Field.GEOMETRY);
            set(controller, "autosaveOpen", false);
            store.replace(saved); selection.restore(initial);
            set(controller, "dialog", dialog(controller, "NONE"));
            ((Set<?>) get(controller, "selectedAreaIds")).clear(); set(controller, "areaId", null);
            ((SelectionHistory) get(controller, "history")).clear();
            call(controller, "syncFields"); call(controller, "updateVisibility");
        }
        System.out.println("PASS: area properties wait for Save as one undo step, autosave settings, Save hiding, area copy, territory shape autosave");
    }

    private static void clickRow(ExactSelectionController controller, int index, int modifiers) throws Exception {
        int[] rect = (int[]) call(controller, "dialogRect");
        controller.mouseClicked(new MouseButtonEvent(rect[0] + 54, rect[1] + 54 + index * 20,
                new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers)), false);
    }

    private static Set<String> checkedNames(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        Set<?> ids = (Set<?>) get(controller, "selectedAreaIds");
        Set<String> names = new java.util.TreeSet<>();
        for (SavedAreaStore.Area area : store.state().areas()) if (ids.contains(area.id())) names.add(area.name());
        return names;
    }

    private static void checkAreaListAndTransfer(ExactSelectionController controller, SavedAreaStore store, Path directory) throws Exception {
        SavedAreaStore.State saved = store.state();
        var roads = new SavedAreaStore.Group("roads", "Roads", true);
        var c = SavedAreaStore.create("Charlie", new SelectionBounds(0, 0, 4, 4), 0x111111, 25, null);
        var a = SavedAreaStore.create("alpha", new SelectionBounds(10, 0, 14, 4), 0x222222, 25, "roads");
        var d = SavedAreaStore.create("Delta", new SelectionBounds(20, 0, 24, 4), 0x333333, 25, "roads");
        var b = SavedAreaStore.create("Bravo", new SelectionBounds(30, 0, 34, 4), 0x444444, 25, null);
        try {
            store.replace(new SavedAreaStore.State(List.of(c, a, d, b), List.of(roads), true, true));
            press(controller, "areasButton");
            clickRow(controller, 0, 0);
            clickRow(controller, 2, GLFW.GLFW_MOD_SHIFT);
            require(checkedNames(controller, store).equals(Set.of("Charlie", "alpha", "Delta")), "Shift+click did not check the range");
            clickRow(controller, 1, GLFW.GLFW_MOD_CONTROL);
            require(checkedNames(controller, store).equals(Set.of("Charlie", "Delta")), "Ctrl+click did not toggle one area");
            clickRow(controller, 3, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT);
            // The Ctrl+click on alpha moved the anchor there, so the added range is alpha..Bravo.
            require(checkedNames(controller, store).equals(Set.of("Charlie", "alpha", "Delta", "Bravo")),
                    "Ctrl+Shift+click did not add the range from the last clicked area");
            clickRow(controller, 1, 0);
            require(checkedNames(controller, store).equals(Set.of("alpha")), "Plain click did not check a single area");

            press(controller, "areaSortButton");
            List<?> order = (List<?>) call(controller, "displayedAreas");
            require(order.stream().map(area -> ((SavedAreaStore.Area) area).name()).toList()
                    .equals(List.of("alpha", "Bravo", "Charlie", "Delta")), "Name order is wrong: " + order);
            clickRow(controller, 1, 0);
            require(checkedNames(controller, store).equals(Set.of("Bravo")), "Rows do not follow the sorted order");
            press(controller, "areaSortButton");
            require(((List<?>) call(controller, "displayedAreas")).stream().map(area -> ((SavedAreaStore.Area) area).name()).toList()
                    .equals(List.of("alpha", "Delta", "Bravo", "Charlie")), "Group order must list grouped areas by group, then the rest");
            press(controller, "areaSortButton"); press(controller, "areaSortButton");
            require(get(controller, "areaSort").toString().equals("DATE"), "Sort did not cycle back to date order");
            require(((Button[]) get(controller, "areaCopies")).length == 7, "Area copy buttons lack a name or corner action");

            press(controller, "areaClearSelection");
            call(controller, "layoutDialog");
            Button folderIcon = (Button) get(controller, "transferFolderButton"), importButton = (Button) get(controller, "areaImport");
            require(folderIcon.visible && folderIcon.getMessage().getString().isEmpty() && folderIcon.getY() == importButton.getY()
                    && folderIcon.getX() >= importButton.getX() + importButton.getWidth(),
                    "Areas window has no folder button beside Import");
            press(controller, "areaExportFile");
            Path folder = AreaTransfer.folder(directory);
            List<Path> files = AreaTransfer.list(folder);
            require(files.size() == 1 && AreaTransfer.read(files.get(0)).areas().size() == 4, "Exporting all areas wrote no complete file");
            require(((Button) get(controller, "openExportButton")).visible, "Export did not offer to show the file");
            press(controller, "areaImport");
            require(window(controller).equals("IMPORT"), "Import window did not open");
            checkLayout(controller);
            int[] rect = (int[]) call(controller, "dialogRect");
            controller.mouseClicked(left(rect[0] + 40, rect[1] + 54, 0), false);
            require(window(controller).equals("AREAS") && store.state().areas().size() == 4,
                    "Re-importing the same areas created duplicates");
            store.replace(new SavedAreaStore.State(List.of(c), List.of(), true, true));
            press(controller, "areaImport");
            controller.mouseClicked(left(rect[0] + 40, rect[1] + 54, 0), false);
            require(store.state().areas().size() == 4 && store.state().groups().size() == 1
                    && store.state().groups().get(0).name().equals("Roads")
                    && store.state().areas().stream().filter(area -> area.name().equals("Delta"))
                    .allMatch(area -> area.groupId().equals(store.state().groups().get(0).id())),
                    "Import did not restore areas with their group matched by name");
            press(controller, "managementUndo");
            require(store.state().areas().size() == 1, "Import was not one undo step");
            press(controller, "managementRedo");

            press(controller, "manageGroups");
            row(controller, 0, false);
            press(controller, "groupExport");
            checkLayout(controller);
            Path groupFile = AreaTransfer.list(folder).get(0);
            require(AreaTransfer.read(groupFile).areas().stream().map(SavedAreaStore.Area::name).sorted().toList()
                    .equals(List.of("Delta", "alpha")), "Group export must contain exactly that group's areas");
            press(controller, "managementClose");
        } finally {
            store.replace(saved);
            set(controller, "dialog", dialog(controller, "NONE"));
            ((Set<?>) get(controller, "selectedAreaIds")).clear(); set(controller, "areaId", null);
            set(controller, "areaSort", get(controller, "areaSort").getClass().getEnumConstants()[0]);
            ((SelectionHistory) get(controller, "history")).clear();
            set(controller, "message", "");
            call(controller, "updateVisibility");
        }
        System.out.println("PASS: Shift/Ctrl area selection, sorting, name copy, export/import with duplicate skipping and group matching, group export");
    }

    private static void checkExportWindow(ExactSelectionController controller, GuiMap map, Path directory) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        try {
            selection.set(new SelectionBounds(0, 0, 99, 49));
            call(controller, "updateVisibility");
            press(controller, "panelModeButton");
            press(controller, "selectionExportButton");
            require(window(controller).equals("EXPORT"), "Selection export did not open the export window");
            ExportWindow window = (ExportWindow) get(controller, "exportWindow");
            EditBox folder = window.folder;
            Path defaultFolder = directory.resolve("xaero-exact-selection").resolve("map-exports").toAbsolutePath();
            require(folder.getValue().isEmpty() && window.folder().equals(defaultFolder),
                    "Default export folder must remain an empty field resolving to the default path: " + folder.getValue());
            Button run = window.run;
            require(run.visible && run.active, "Export is unavailable for a small selection");
            ExportPreferences preferences = window.preferences;
            Button areas = window.areas, labels = window.labels;
            require(preferences.areas && areas.visible && areas.active && labels.active,
                    "Export must include areas by default");
            boolean previousLabels = preferences.labels;
            press(controller, window.areas, "areas");
            require(!preferences.areas && !labels.active && preferences.labels == previousLabels,
                    "Hiding exported areas must disable labels without losing their preference");
            preferences.save();
            require(!ExportPreferences.load(directory).areas, "Area export visibility was not remembered");
            press(controller, window.areas, "areas");
            require(preferences.areas && labels.active && preferences.labels == previousLabels,
                    "Enabling exported areas did not restore the label control");
            preferences.save();
            int scale = preferences.scale;
            press(controller, window.scale, "scale");
            require(preferences.scale != scale && window.windowScale == preferences.scale, "Scale button did not change the scale");
            boolean night = preferences.night;
            press(controller, window.time, "time");
            require(preferences.night != night, "Day/night button did not toggle");
            folder.setValue("relative/path"); call(controller, "updateVisibility");
            require(!run.active, "A relative folder can be exported to");
            press(controller, window.standardFolder, "standardFolder");
            require(run.active && folder.getValue().isEmpty() && window.folder().equals(defaultFolder),
                    "Default folder button did not clear the field and restore a usable folder");
            for (int[] size : List.of(new int[]{320, 240}, new int[]{480, 270}, new int[]{960, 540}, new int[]{640, 360})) {
                map.width = size[0]; map.height = size[1];
                // Screen.rebuildWidgets clears the old widgets before init() adds new ones.
                for (String name : List.of("children", "narratables", "renderables")) ((List<?>) get(map, name)).clear();
                controller.init(); checkLayout(controller);
            }
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(window(controller).equals("NONE"), "Esc did not close the export window back to the map");
            preferences.scale = 4;
            selection.set(new SelectionBounds(0, 0, 3999, 3999));
            call(controller, "updateVisibility");
            press(controller, "selectionExportButton");
            require(window(controller).equals("EXPORT"), "Export window did not reopen; message=" + get(controller, "message"));
            // init() above rebuilt the widgets, so read the current button.
            require(!window.run.active, "An image above the pixel limit can be exported");
            require(window.windowScale == 1 && preferences.scale == 4,
                    "A large area lowered the remembered export scale instead of only this window's");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        } finally {
            set(controller, "dialog", dialog(controller, "NONE"));
            set(controller, "compact", true);
            selection.restore(initial);
            call(controller, "layoutMainPanel"); call(controller, "syncFields"); call(controller, "updateVisibility");
        }
        System.out.println("PASS: export window with default folder, path validation, scale, day/night, size limit and responsive layout");
    }

    private static void checkSeparateCorners(ExactSelectionController controller, GuiMap map, Minecraft minecraft,
                                             SavedAreaStore store, Unsafe unsafe) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot before = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        Object previousKeyboard = get(minecraft, "keyboardHandler");
        ClipboardKeyboard keyboard = (ClipboardKeyboard) unsafe.allocateInstance(ClipboardKeyboard.class);
        set(minecraft, "keyboardHandler", keyboard);
        try {
            SelectionBounds bounds = new SelectionBounds(-120, 340, 56, -78);
            selection.set(bounds); call(controller, "syncFields"); call(controller, "updateVisibility");
            for (boolean compact : List.of(true, false)) {
                set(controller, "compact", compact); call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
                Button a = (Button) get(controller, "copyCoordinates"), b = (Button) get(controller, "copyCornerB");
                require(a.visible && a.active && b.visible && b.active && a.getY() < b.getY(),
                        "Corner buttons are missing or are not beside separate coordinate rows");
                checkMainLayout(controller, map);
                press(controller, "copyCoordinates"); require("-120 340".equals(keyboard.copied), "Corner A clipboard format is wrong");
                press(controller, "copyCornerB"); require("56 -78".equals(keyboard.copied), "Corner B clipboard format is wrong");
            }
            set(controller, "compact", true); call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
            var area = SavedAreaStore.create("Corners", bounds, AreaPalette.DEFAULT, 25, null);
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            press(controller, "areasButton"); pick(controller, store, area.id()); call(controller, "layoutDialog");
            Button[] copies = (Button[]) get(controller, "areaCopies");
            require(copies[1].visible && copies[6].visible && copies[1].getY() == copies[6].getY()
                    && copies[1].getX() < copies[6].getX(), "Saved rectangle lacks separate corner buttons");
            checkLayout(controller);
            copies[1].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            require("-120 340".equals(keyboard.copied), "Saved corner A clipboard format is wrong");
            copies[6].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            require("56 -78".equals(keyboard.copied), "Saved corner B clipboard format is wrong");
            var polygon = SavedAreaStore.create("Polygon", new PolygonGeometry(List.of(new GridPoint(0, 0),
                    new GridPoint(3, 0), new GridPoint(0, 4))), AreaPalette.DEFAULT, 25, null);
            store.replace(new SavedAreaStore.State(List.of(polygon), List.of(), true, true));
            pick(controller, store, polygon.id()); call(controller, "layoutDialog");
            require(copies[1].visible && !copies[6].visible, "Polygon vertex copy exposed an unrelated second corner");
            copies[1].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            require("0 0; 3 0; 0 4".equals(keyboard.copied), "Polygon vertex coordinate pairs must use spaces");
            System.out.println("PASS: separate A/B corner copying in both panels and saved areas, space-separated coordinates, polygon vertices preserved");
        } finally {
            set(minecraft, "keyboardHandler", previousKeyboard); store.replace(saved); selection.restore(before);
            ((Set<?>) get(controller, "selectedAreaIds")).clear(); set(controller, "areaId", null);
            set(controller, "dialog", dialog(controller, "NONE")); set(controller, "compact", true); set(controller, "message", "");
            call(controller, "layoutMainPanel"); call(controller, "syncFields"); call(controller, "updateVisibility");
        }
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
            autosaveProperties(controller);
            controller.init();
            SelectionState selection = (SelectionState) get(controller, "state");
            selection.enable(); selection.set(new SelectionBounds(40, 40, 50, 50));
            set(controller, "context", context); set(controller, "historyContext", context);
            call(controller, "updateVisibility");
            checkSeparateCorners(controller, map, minecraft, store, unsafe);
            checkSelectionPanel(controller, map);
            checkHintFitting(controller);
            checkFramedAreasAndMetrics(controller, store);
            checkAreaDraftAndAutosave(controller, map, store, unsafe);
            checkAreaListAndTransfer(controller, store, directory);
            checkExportWindow(controller, map, directory);
            checkExportNotice(controller, map, directory);
            checkPolygonEditing(controller, map, store, directory, context);
            checkSavedLabelLayering(controller, store, unsafe);
            checkPolygonInsertionAndUpdate(controller, map, store, directory, context);
            checkGeometryGestures(controller, map, unsafe);
            checkGestureEdgeCases(controller, context);
            checkPrecisePointer(controller, minecraft, unsafe);
            checkWaypointRows(controller);
            checkReleaseFeatures(controller, map, store);
            checkQuickMenu(controller, store);
            checkWaypointManagement(map, bridge, minecraft, store, context);
            controller.init();
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
            require(((Set<?>) get(controller, "selectedAreaIds")).isEmpty(), "Areas window opened with an area already checked");
            checkLayout(controller);
            row(controller, 0, false);
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
            require(selection.bounds() == null, "Saving from the Areas window kept the selection");
            selection.set(new SelectionBounds(40, 40, 50, 50)); call(controller, "updateVisibility");
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
