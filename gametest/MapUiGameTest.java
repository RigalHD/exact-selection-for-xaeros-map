package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SelectionState.Phase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import xaero.map.controls.ControlsRegister;
import xaero.map.gui.GuiMap;

/** Drives the real game by real input events, checks the mod's state after each step and saves screenshots. */
public class MapUiGameTest implements FabricClientGameTest {
    ClientGameTestContext c;
    TestInput in;
    int passed;
    final List<String> failures = new ArrayList<>();

    interface Step {
        void run() throws Exception;
    }

    ExactSelectionController ui() {
        try {
            Field current = ExactSelectionController.class.getDeclaredField("current");
            current.setAccessible(true);
            return (ExactSelectionController) current.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    Object field(String name) throws Exception {
        List<Object> holders = new ArrayList<>(List.of(ui(), ui().exportWindow));
        for (Field part : ExactSelectionController.class.getDeclaredFields()) {
            if (!UiPart.class.isAssignableFrom(part.getType())) continue;
            part.setAccessible(true);
            holders.add(part.get(ui()));
        }
        for (Object holder : holders) {
            try {
                Field f = holder.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(holder);
            } catch (NoSuchFieldException absent) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    AbstractWidget w(String name) throws Exception {
        return (AbstractWidget) field(name);
    }

    void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("E2E: ok   " + what);
            return;
        }
        String detail = "";
        try {
            detail = " [window=" + ui().dialog + " message='" + ui().message + "' phase="
                    + ui().state.phase() + "]";
        } catch (RuntimeException ignored) {
        }
        failures.add(what + detail);
        System.out.println("E2E: FAIL " + what + detail);
    }

    void pause() {
        // Longer than the mod's 500 ms double-click window, and Minecraft's 250 ms one.
        try {
            Thread.sleep(560);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void at(double x, double y) {
        // The game lowers the GUI scale when the window is too small for it.
        double scale = c.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
        in.setCursorPos(x * scale, y * scale);
        c.waitTick();
    }

    void click(double x, double y) {
        at(x, y);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        pause();
    }

    void doubleClick(double x, double y) {
        at(x, y);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        pause();
    }

    void rightClick(double x, double y) {
        at(x, y);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        pause();
    }

    void drag(double x1, double y1, double x2, double y2) {
        at(x1, y1);
        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        at((x1 + x2) / 2, (y1 + y2) / 2);
        c.waitTick();
        at(x2, y2);
        c.waitTick();
        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        pause();
    }

    void press(String name) throws Exception {
        AbstractWidget x = w(name);
        if (!x.visible || !x.active) {
            check(false, "Cannot press " + name + " visible=" + x.visible + " active=" + x.active);
            return;
        }
        click(x.getX() + x.getWidth() / 2.0, x.getY() + x.getHeight() / 2.0);
    }

    void clickWidget(AbstractWidget x) {
        click(x.getX() + x.getWidth() / 2.0, x.getY() + x.getHeight() / 2.0);
    }

    void key(int code) {
        in.pressKey(code);
    }

    /** A key with modifiers as GLFW reports them; TestInput always reports none. */
    void keyMods(int code, int mods) {
        c.runOnClient(mc -> {
            try {
                Method press =
                        KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, KeyEvent.class);
                press.setAccessible(true);
                long window = mc.getWindow().handle();
                press.invoke(mc.keyboardHandler, window, GLFW.GLFW_PRESS, new KeyEvent(code, 0, mods));
                press.invoke(mc.keyboardHandler, window, GLFW.GLFW_RELEASE, new KeyEvent(code, 0, mods));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        c.waitTick();
    }

    void cmd(int code) {
        keyMods(code, GLFW.GLFW_MOD_SUPER);
    }

    void cmdShift(int code) {
        keyMods(code, GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT);
    }

    void pressMods(int mods) {
        c.runOnClient(mc -> {
            try {
                Method button =
                        MouseHandler.class.getDeclaredMethod("onButton", long.class, MouseButtonInfo.class, int.class);
                button.setAccessible(true);
                long window = mc.getWindow().handle();
                MouseButtonInfo info = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, mods);
                button.invoke(mc.mouseHandler, window, info, GLFW.GLFW_PRESS);
                button.invoke(mc.mouseHandler, window, info, GLFW.GLFW_RELEASE);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        c.waitTick();
    }

    void clickMods(double x, double y, int mods) {
        at(x, y);
        pressMods(mods);
        pause();
    }

    void rowMods(int index, int mods) {
        clickMods(rect()[0] + 54, rect()[1] + 58 + index * 20, mods);
    }

    void typeInto(EditBox field, String text) {
        cmd(GLFW.GLFW_KEY_A);
        in.typeChars(text);
        c.waitTick();
    }

    void shot(String name) {
        c.waitTick();
        c.takeScreenshot(name);
    }

    void selectRect(double x1, double y1, double x2, double y2) {
        click(x1, y1);
        at(x2, y2);
        click(x2, y2);
    }

    MapBridge.Frame frame() {
        return c.computeOnClient(mc -> ui().bridge.exactselection$frame());
    }

    List<WaypointAccess.Point> points() {
        return c.computeOnClient(mc -> ui().waypoints.all(ui().context).points());
    }

    double sx(long worldX) {
        return frame().screenX(worldX);
    }

    double sy(long worldZ) {
        return frame().screenY(worldZ);
    }

    int[] rect() {
        return ui().windows.dialogRect();
    }

    void row(int index) {
        click(rect()[0] + 54, rect()[1] + 58 + index * 20);
    }

    void clickSwatch(int index, int count) {
        int[] r = rect();
        for (int y = r[1]; y < r[1] + r[3]; y += 2)
            for (int x = r[0]; x < r[0] + r[2]; x += 2) {
                if (ui().colorWindows.swatchAt(r[0], r[1], r[2], count, x, y) == index) {
                    click(x, y);
                    return;
                }
            }
        check(false, "No swatch " + index);
    }

    void section(String name, Step step) {
        System.out.println("E2E: == " + name);
        try {
            step.run();
        } catch (Throwable t) {
            check(false, name + " threw " + t);
            t.printStackTrace(System.out);
        }
        cleanup();
    }

    /** Back to the bare map with the tool on and nothing selected. */
    void cleanup() {
        for (int i = 0; i < 6 && ui().dialog != Dialog.NONE; i++) key(GLFW.GLFW_KEY_ESCAPE);
        c.runOnClient(mc -> {
            ExactSelectionController ui = ui();
            if (!ui.enabled()) return;
            ui.rightClickMenu.quickMenu = false;
            ui.rulerTool.setRulerMode(false);
            ui.polygonTool.discardDraft();
            ui.polygonTool.mode = false;
            ui.selectionPanel.compact = true;
            ui.selectionPanel.layoutMainPanel();
            ui.reset();
        });
        c.waitTick();
        pause();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        c = context;
        in = c.getInput();
        c.runOnClient(mc ->
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
        try (TestSingleplayerContext world = c.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            in.resizeWindow(1600, 900);
            c.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
            });
            c.waitTicks(10);
            in.pressKey(ControlsRegister.keyOpenMap);
            c.waitForScreen(GuiMap.class);
            c.waitTicks(30);
            section("tool on", this::toolOn);
            section("selection", this::selection);
            section("panel", this::panel);
            section("right-click menu and new area", this::menuAndNewArea);
            section("areas window", this::areasWindow);
            section("groups", this::groups);
            section("transfer", this::transfer);
            section("long list", this::longList);
            section("contour", this::contour);
            section("contour cancel", this::contourCancel);
            section("ruler", this::ruler);
            section("zone menu and snapping", this::zones);
            section("waypoints", this::waypoints);
            section("export", this::export);
            section("close and reopen", this::reopen);
            section("resize", this::resize);
            section("russian", this::russian);
            in.resizeWindow(1600, 900);
        }
        System.out.println("E2E: RESULT passed=" + passed + " failed=" + failures.size());
        for (String failure : failures) System.out.println("E2E: FAILED: " + failure);
    }

    void toolOn() throws Exception {
        ExactSelectionController ui = ui();
        check(!ui.enabled(), "the tool starts off");
        press("toggle");
        check(ui.enabled(), "the toggle turns the tool on");
        check(
                w("areasButton").visible && w("undo").visible && w("polygonModeButton").visible,
                "the panel's buttons show");
        check(!ui.selectionPanel.fields[0].visible, "the compact panel hides the coordinate fields");
        shot("01_tool_on");
        key(GLFW.GLFW_KEY_N);
        check(ui.chunks, "N switches to chunks");
        shot("01b_chunks");
        selectRect(420, 120, 520, 220);
        SelectionBounds b = ui.state.bounds();
        check(
                b != null
                        && ui.state.phase() == Phase.SELECTED
                        && Math.floorMod(b.minX(), 16) == 0
                        && Math.floorMod(b.maxX() + 1, 16) == 0,
                "a selection in chunks snaps to chunks");
        shot("01c_chunk_selection");
        c.runOnClient(mc -> ui.reset());
        key(GLFW.GLFW_KEY_N);
        check(!ui.chunks, "N switches back to blocks");
    }

    void selection() throws Exception {
        ExactSelectionController ui = ui();
        click(400, 120);
        check(ui.state.phase() == Phase.PREVIEW, "the first click starts a selection");
        SelectionBounds first = ui.state.bounds();
        at(560, 300);
        c.waitTicks(2);
        SelectionBounds hover = ui.state.bounds();
        check(hover.x2() != first.x2() && hover.z2() != first.z2(), "the second corner follows the pointer");
        check(
                ui.selectionPanel.fields[2].getValue().equals(Integer.toString(hover.x2())),
                "the fields follow the preview");
        shot("02_preview");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(ui.state.phase() == Phase.READY && ui.state.bounds() == null, "Esc drops the preview");
        selectRect(400, 120, 560, 300);
        check(ui.state.phase() == Phase.SELECTED, "the second click finishes it");
        shot("03_selected");
        SelectionBounds done = ui.state.bounds();
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.state.bounds() == null, "Cmd+Z undoes the selection");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(done.equals(ui.state.bounds()), "Cmd+Shift+Z brings it back");
        cmd(GLFW.GLFW_KEY_Z);
        cmd(GLFW.GLFW_KEY_Y);
        check(done.equals(ui.state.bounds()), "Cmd+Y brings it back");

        double right = sx(done.maxX() + 1L), bottom = sy(done.maxZ() + 1L);
        drag(right, bottom, right + 40, bottom + 24);
        SelectionBounds resized = ui.state.bounds();
        check(
                resized.maxX() > done.maxX() && resized.maxZ() > done.maxZ() && resized.minX() == done.minX(),
                "the corner handle resizes");
        check(ui.gesture == null, "the resize gesture ended");
        double cx = (sx(resized.minX()) + sx(resized.maxX() + 1L)) / 2,
                cy = (sy(resized.minZ()) + sy(resized.maxZ() + 1L)) / 2;
        drag(cx, cy, cx + 30, cy + 16);
        SelectionBounds moved = ui.state.bounds();
        check(
                moved.minX() > resized.minX() && moved.width() == resized.width() && moved.length() == resized.length(),
                "a drag inside moves it");
        at(cx + 30, cy + 16);
        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        at(cx + 31, cy + 16);
        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        pause();
        check(moved.equals(ui.state.bounds()), "a one-pixel jitter stays a click");
        cmd(GLFW.GLFW_KEY_Z);
        check(resized.equals(ui.state.bounds()), "undo takes back the move");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(moved.equals(ui.state.bounds()), "redo repeats the move");
        shot("03b_moved");
    }

    void panel() throws Exception {
        ExactSelectionController ui = ui();
        selectRect(400, 120, 560, 300);
        press("panelModeButton");
        check(
                !ui.selectionPanel.compact && ui.selectionPanel.fields[0].visible && w("clear").visible,
                "the panel expands");
        shot("04_expanded");
        EditBox x1 = ui.selectionPanel.fields[0];
        clickWidget(x1);
        check(ui.screen.getFocused() == x1, "a click focuses the field");
        typeInto(x1, "100");
        check(ui.dirty, "typing marks the fields as changed");
        check(w("apply").active && !w("quickSave").active, "typing updates Apply and Save");
        shot("05_typed");
        click(650, 380);
        check(ui.dirty && ui.message.length() > 0, "a map click while coordinates wait explains why");
        clickWidget(x1);
        key(GLFW.GLFW_KEY_ENTER);
        check(!ui.dirty && ui.state.bounds().x1() == 100, "Enter applies the typed coordinate");
        check(ui.selectionPanel.fields[0].getValue().equals("100"), "the field keeps the applied value");
        clickWidget(x1);
        typeInto(x1, "zz");
        key(GLFW.GLFW_KEY_ENTER);
        check(ui.dirty && ui.message.length() > 0, "bad coordinates give a message");
        shot("05b_bad_coordinates");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(
                !ui.dirty
                        && ui.state.bounds() != null
                        && ui.selectionPanel.fields[0].getValue().equals("100"),
                "Esc drops typed coordinates, not the selection");
        ui.focus(null);
        key(GLFW.GLFW_KEY_TAB);
        Object one = ui.screen.getFocused();
        check(one instanceof AbstractWidget a && a.visible && a.active, "Tab focuses a visible widget");
        key(GLFW.GLFW_KEY_TAB);
        Object two = ui.screen.getFocused();
        check(two instanceof AbstractWidget a && a.visible && a.active && two != one, "Tab moves on");
        keyMods(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_SHIFT);
        check(ui.screen.getFocused() == one, "Shift+Tab moves back");
        ui.focus(null);
        press("clear");
        check(ui.state.bounds() == null, "Clear drops the selection");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.state.bounds() != null, "undo brings a cleared selection back");
        press("panelModeButton");
        check(ui.selectionPanel.compact, "the panel collapses");
    }

    void menuAndNewArea() throws Exception {
        ExactSelectionController ui = ui();
        selectRect(400, 120, 560, 300);
        SelectionBounds b = ui.state.bounds();
        rightClick(480, 210);
        check(ui.rightClickMenu.quickMenu && w("quickMenuSave").visible, "a right click opens the menu");
        check(
                w("sizeButton").visible && w("moveButton").visible && w("centerButton").visible,
                "the menu has Size, Move, Center");
        shot("06_menu");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(!ui.rightClickMenu.quickMenu && b.equals(ui.state.bounds()), "Esc closes only the menu");
        check(!w("quickMenuSave").visible, "the menu's buttons went away with it");
        rightClick(480, 210);
        click(700, 400);
        check(!ui.rightClickMenu.quickMenu && b.equals(ui.state.bounds()), "a click elsewhere closes only the menu");
        rightClick(480, 210);
        in.scroll(1);
        c.waitTicks(40);
        check(!ui.rightClickMenu.quickMenu, "zooming closes the menu");
        // The zoom moved the map: find the selection again.
        rightClick((sx(b.minX()) + sx(b.maxX() + 1L)) / 2, (sy(b.minZ()) + sy(b.maxZ() + 1L)) / 2);
        press("sizeButton");
        check(ui.dialog == Dialog.SIZE, "Size opens its window");
        shot("06b_size");
        press("modalCancel");
        check(ui.dialog == Dialog.NONE, "Cancel closes it");
        rightClick((sx(b.minX()) + sx(b.maxX() + 1L)) / 2, (sy(b.minZ()) + sy(b.maxZ() + 1L)) / 2);
        press("moveButton");
        check(ui.dialog == Dialog.MOVE, "Move opens its window");
        clickWidget(ui.dialogFields[ExactSelectionController.OFFSET_X_FIELD]);
        typeInto(ui.dialogFields[ExactSelectionController.OFFSET_X_FIELD], "7");
        key(GLFW.GLFW_KEY_ENTER);
        check(ui.dialog == Dialog.NONE && ui.state.bounds().minX() == b.minX() + 7, "Enter moves the selection");
        rightClick((sx(b.minX() + 7L) + sx(b.maxX() + 8L)) / 2, (sy(b.minZ()) + sy(b.maxZ() + 1L)) / 2);
        press("quickMenuSave");
        check(ui.dialog == Dialog.AREA_NAME, "Save opens the name window");
        EditBox name = ui.dialogFields[ExactSelectionController.NEW_AREA_FIELD];
        check(name.visible && ui.screen.getFocused() == name, "the name field is shown and focused");
        shot("07_new_area");
        press("newAreaOpacityButton");
        clickSwatch(5, AreaPalette.COLORS.length);
        check(ui.newAreaWindow.newAreaColor == AreaPalette.COLORS[5], "a swatch picks the color");
        check(ui.dialog == Dialog.AREA_NAME, "a swatch keeps the window open");
        clickWidget(name);
        cmd(GLFW.GLFW_KEY_A);
        key(GLFW.GLFW_KEY_BACKSPACE);
        check(!w("modalApply").active, "an empty name cannot be saved");
        shot("07b_empty_name");
        in.typeChars("Alpha");
        c.waitTick();
        check(w("modalApply").active, "typing a name enables Create");
        key(GLFW.GLFW_KEY_ENTER);
        check(
                ui.dialog == Dialog.NONE
                        && ui.areas().size() == 1
                        && ui.areas().get(0).name().equals("Alpha"),
                "Enter saves the area");
        check(ui.areas().get(0).color() == AreaPalette.COLORS[5], "the area has the chosen color");
        check(ui.state.bounds() == null, "saving clears the selection");
        shot("08_saved");
    }

    void areasWindow() throws Exception {
        ExactSelectionController ui = ui();
        key(GLFW.GLFW_KEY_J);
        check(ui.dialog == Dialog.AREAS, "J opens Areas");
        check(w("managementClose").visible && w("areaSave").visible, "the window's widgets show");
        check(!w("toggle").visible && !w("areasButton").visible, "the panel's buttons are hidden behind it");
        check(!w("areaLoad").active, "nothing is checked at first");
        shot("09_areas");
        row(0);
        check(
                ui.areasWindow.selectedAreaIds.size() == 1 && w("areaLoad").active && w("areaDelete").active,
                "a row click checks the area");
        shot("10_area_row");
        EditBox name = ui.dialogFields[ExactSelectionController.AREA_NAME_FIELD];
        clickWidget(name);
        check(ui.screen.getFocused() == name, "a click focuses the name");
        typeInto(name, "Renamed");
        key(GLFW.GLFW_KEY_ENTER);
        check(ui.areas().get(0).name().equals("Renamed"), "Enter saves the new name");
        press("areaColor");
        check(ui.dialog == Dialog.COLOR, "Color opens its window");
        shot("11_color");
        clickSwatch(9, AreaPalette.COLORS.length);
        check(ui.dialog == Dialog.AREAS, "a swatch returns to Areas");
        press("areaOpacity");
        check(w("areaSaveChanges").visible && w("areaSaveChanges").active, "changes wait for Save");
        shot("12_pending");
        press("areaSaveChanges");
        check(ui.areas().get(0).color() == AreaPalette.COLORS[9], "Save writes the color");
        press("areaColor");
        press("modalCancel");
        check(ui.dialog == Dialog.AREAS, "Cancel returns from Color");
        press("autosaveButton");
        check(ui.areasWindow.autosaveOpen && w("autosaveToggle").visible, "the gear opens autosave");
        shot("13_autosave");
        press("autosaveToggle");
        press("roundingButton");
        press("autosaveToggle");
        press("autosaveClose");
        check(!ui.areasWindow.autosaveOpen && w("areaLoad").visible, "Close returns to the area");
        press("areaMapVisibility");
        check(!ui.areaStore.state().showOnMap(), "the Map switch turns the overlay off");
        press("areaMapVisibility");
        check(ui.areaStore.state().showOnMap(), "and on");
        press("areaNames");
        check(!ui.areaStore.state().showNames(), "the Names switch works");
        press("areaNames");
        press("areaMinimapVisibility");
        boolean minimap = ui.areaStore.state().showOnMinimap();
        press("areaMinimapVisibility");
        check(ui.areaStore.state().showOnMinimap() != minimap, "the Minimap switch works");
        press("areaVisibility");
        check(!ui.areas().get(0).visible(), "Hide hides the checked area");
        press("managementUndo");
        check(ui.areas().get(0).visible(), "the window's undo brings it back");
        press("managementRedo");
        check(!ui.areas().get(0).visible(), "the window's redo repeats it");
        press("areaVisibility");
        check(ui.areas().get(0).visible(), "Show shows it again");
        press("areaDelete");
        check(
                ui.areasWindow.areaDeleteConfirmation && w("confirm").visible && ui.screen.getFocused() == w("confirm"),
                "Delete asks, with the focus on its button");
        check(!w("areaDelete").visible, "the other buttons hide while it asks");
        shot("14_delete");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(
                !ui.areasWindow.areaDeleteConfirmation
                        && ui.dialog == Dialog.AREAS
                        && ui.areas().size() == 1,
                "Esc only cancels the question");
        press("areaDelete");
        press("confirm");
        check(ui.areas().isEmpty(), "Confirm deletes");
        shot("15_empty");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.areas().size() == 1, "Cmd+Z restores the deleted area");
        EditBox search = ui.areasWindow.areaSearch;
        clickWidget(search);
        in.typeChars("zzz");
        c.waitTick();
        check(ui.areasWindow.displayedAreas().isEmpty(), "the search filters the list");
        shot("16_search");
        cmd(GLFW.GLFW_KEY_A);
        key(GLFW.GLFW_KEY_BACKSPACE);
        check(ui.areasWindow.displayedAreas().size() == 1, "clearing the search brings it back");
        press("areaSortButton");
        press("areaSortButton");
        press("areaSortButton");
        press("areaSortButton");
        ui.focus(null);
        // Plain double click shows the area on the map.
        click(rect()[0] + 54, rect()[1] + 58);
        ui.areasWindow.selectedAreaIds.clear();
        c.waitTick();
        at(rect()[0] + 54, rect()[1] + 58);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTicks(3);
        check(ui.dialog == Dialog.NONE, "a double click on a row closes the window to show the area");
        shot("17_shown");
        c.waitTicks(20);
        key(GLFW.GLFW_KEY_J);
        row(0);
        press("areaLoad");
        check(
                ui.dialog == Dialog.NONE && ui.state.source() != null && ui.state.bounds() != null,
                "Edit loads the area");
        shot("18_editing");
        press("panelModeButton");
        shot("18b_editing_expanded");
        press("panelModeButton");
    }

    void groups() throws Exception {
        ExactSelectionController ui = ui();
        key(GLFW.GLFW_KEY_J);
        row(0);
        press("manageGroups");
        check(ui.dialog == Dialog.GROUPS, "Groups opens");
        shot("20_groups");
        press("groupCreate");
        check(ui.dialog == Dialog.GROUP_NAME, "New group asks for a name");
        EditBox name = ui.dialogFields[ExactSelectionController.GROUP_NAME_FIELD];
        check(name.visible, "the group name field shows");
        check(!w("modalApply").active, "an empty group name cannot be created");
        shot("21_group_name");
        clickWidget(name);
        in.typeChars("Town");
        c.waitTick();
        check(w("modalApply").active, "a typed name enables Create");
        key(GLFW.GLFW_KEY_ENTER);
        check(
                ui.dialog == Dialog.GROUPS
                        && ui.groups().size() == 1
                        && ui.groups().get(0).name().equals("Town"),
                "Enter creates the group");
        press("groupRename");
        check(ui.dialog == Dialog.GROUP_NAME, "Rename asks for a name");
        clickWidget(name);
        typeInto(name, "City");
        key(GLFW.GLFW_KEY_ENTER);
        check(ui.groups().get(0).name().equals("City"), "Rename renames");
        press("groupVisibility");
        check(!ui.groups().get(0).visible(), "Hide hides the group");
        shot("22_group_hidden");
        press("groupVisibility");
        check(ui.groups().get(0).visible(), "Show shows it");
        press("managementClose");
        check(ui.dialog == Dialog.AREAS, "Back returns to Areas");
        press("groupAssign");
        check(ui.dialog == Dialog.ASSIGN_GROUP, "Group opens the chooser");
        shot("23_assign");
        click(rect()[0] + 60, rect()[1] + 48 + 20 + 10);
        check(ui.dialog == Dialog.AREAS, "a chooser row returns to Areas");
        press("areaSaveChanges");
        check(ui.groups().get(0).id().equals(ui.areas().get(0).groupId()), "the area joined the group");
        shot("24_grouped");
        press("manageGroups");
        press("groupDelete");
        check(ui.groupsWindow.groupDeleteConfirmation && w("confirm").visible, "Delete asks");
        shot("25_group_delete");
        press("cancel");
        check(!ui.groupsWindow.groupDeleteConfirmation && ui.groups().size() == 1, "Cancel keeps the group");
        press("groupDelete");
        press("confirm");
        check(ui.groups().isEmpty() && ui.areas().get(0).groupId() == null, "Confirm deletes the group, not the area");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.groups().size() == 1, "undo restores the group");
    }

    void transfer() throws Exception {
        ExactSelectionController ui = ui();
        key(GLFW.GLFW_KEY_J);
        row(0);
        press("areaExportFile");
        check(!ui.message.isEmpty(), "exporting areas reports it");
        shot("26_area_file");
        press("areaImport");
        check(ui.dialog == Dialog.IMPORT, "Import opens");
        shot("27_import");
        int areasBefore = ui.areas().size();
        click(rect()[0] + 60, rect()[1] + 58);
        c.waitTick();
        shot("28_imported");
        check(ui.areas().size() >= areasBefore, "importing keeps the areas: " + ui.message);
        if (ui.dialog == Dialog.IMPORT) press("managementClose");
        check(ui.dialog == Dialog.AREAS, "back to Areas");
    }

    void longList() throws Exception {
        ExactSelectionController ui = ui();
        c.runOnClient(mc -> {
            List<SavedAreaStore.Area> areas = new ArrayList<>(ui.areas());
            for (int i = 0; i < 40; i++)
                areas.add(SavedAreaStore.create(
                        "Zone " + i,
                        new SelectionBounds(1000 + i * 20, 1000, 1010 + i * 20, 1010),
                        0x336699,
                        25,
                        null));
            ui.areaEdits.changeAreas(ui.areaEdits.areaState(areas, ui.groups()), "area_save");
        });
        key(GLFW.GLFW_KEY_J);
        at(rect()[0] + 60, rect()[1] + 100);
        in.scroll(-1);
        c.waitTick();
        check(ui.areasWindow.areaScroll == 1, "the wheel scrolls the list");
        in.scroll(1);
        c.waitTick();
        in.scroll(1);
        c.waitTick();
        check(ui.areasWindow.areaScroll == 0, "the list stops at the top");
        for (int i = 0; i < 60; i++) {
            in.scroll(-1);
        }
        c.waitTick();
        int end = ui.areasWindow.areaScroll;
        check(
                end == ui.areasWindow.displayedAreas().size() - ui.areasWindow.visibleAreaDialogRows(rect()[3]),
                "the list stops at the end: " + end);
        shot("29_long_list");
        for (int i = 0; i < 60; i++) {
            in.scroll(1);
        }
        c.waitTick();
        press("areaSelectAll");
        check(ui.areasWindow.selectedAreaIds.size() == ui.areas().size(), "All checks every area");
        press("areaClearSelection");
        check(ui.areasWindow.selectedAreaIds.isEmpty(), "None clears them");
        row(0);
        rowMods(4, GLFW.GLFW_MOD_SHIFT);
        check(ui.areasWindow.selectedAreaIds.size() == 5, "Shift+click checks a range");
        rowMods(2, GLFW.GLFW_MOD_SUPER);
        check(ui.areasWindow.selectedAreaIds.size() == 4, "Cmd+click unchecks one");
        shot("30_range");
        press("areaDelete");
        press("confirm");
        check(
                ui.areas().size() == 37,
                "Delete removes the checked ones: " + ui.areas().size());
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.areas().size() == 41, "undo restores them all: " + ui.areas().size());
        press("managementClose");
        c.runOnClient(mc -> {
            List<SavedAreaStore.Area> keep = ui.areas().stream()
                    .filter(a -> !a.name().startsWith("Zone "))
                    .toList();
            ui.areaEdits.changeAreas(ui.areaEdits.areaState(keep, ui.groups()), "area_delete");
        });
        check(ui.areas().size() == 1, "test zones removed");
    }

    void contour() throws Exception {
        ExactSelectionController ui = ui();
        key(GLFW.GLFW_KEY_O);
        check(ui.polygonTool.mode, "O turns the Polygon tool on");
        shot("31_polygon_on");
        click(400, 100);
        click(560, 110);
        click(600, 260);
        click(420, 280);
        check(ui.polygonTool.draft.size() == 4, "four clicks make four vertices");
        at(500, 340);
        c.waitTick();
        shot("32_contour");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.polygonTool.draft.size() == 3, "Cmd+Z takes back a vertex");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(ui.polygonTool.draft.size() == 4, "Cmd+Shift+Z returns it");
        key(GLFW.GLFW_KEY_BACKSPACE);
        check(ui.polygonTool.draft.size() == 3, "Backspace removes the last vertex");
        click(420, 280);
        check(ui.polygonTool.draft.size() == 4, "a click adds it again");
        double midX = (sx(ui.polygonTool.draft.get(0).x())
                        + sx(ui.polygonTool.draft.get(1).x()))
                / 2;
        double midZ = (sy(ui.polygonTool.draft.get(0).z())
                        + sy(ui.polygonTool.draft.get(1).z()))
                / 2;
        click(midX, midZ);
        check(ui.polygonTool.draft.size() == 4, "a click on a side only shows the controls");
        doubleClick(midX, midZ);
        check(ui.polygonTool.draft.size() == 5, "a double click on a side adds a vertex");
        GridPoint third = ui.polygonTool.draft.get(3);
        drag(sx(third.x()), sy(third.z()), sx(third.x()) + 20, sy(third.z()) + 20);
        check(
                !ui.polygonTool.draft.get(3).equals(third) && ui.polygonTool.draft.size() == 5,
                "dragging moves a vertex");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.polygonTool.draft.get(3).equals(third), "undo returns the vertex");
        shot("33_contour_edited");
        GridPoint start = ui.polygonTool.draft.get(0);
        click(sx(start.x()), sy(start.z()));
        check(
                ui.state.geometry() instanceof PolygonGeometry
                        && ui.state.phase() == Phase.SELECTED
                        && ui.polygonTool.draft.isEmpty(),
                "a click on the first vertex closes the contour");
        shot("34_polygon");
        int vertices = ui.state.geometry().vertices().size();
        GridPoint a = ui.state.geometry().vertices().get(0),
                b = ui.state.geometry().vertices().get(1);
        doubleClick((sx(a.x()) + sx(b.x())) / 2, (sy(a.z()) + sy(b.z())) / 2);
        check(
                ui.state.geometry().vertices().size() == vertices + 1,
                "a double click on a side of the polygon adds a vertex");
        GridPoint added = ui.state.geometry().vertices().get(1);
        at(sx(added.x()), sy(added.z()));
        pressMods(GLFW.GLFW_MOD_SUPER);
        pressMods(GLFW.GLFW_MOD_SUPER);
        pause();
        check(ui.state.geometry().vertices().size() == vertices, "Cmd + double click deletes a vertex");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(!ui.polygonTool.mode || ui.state.geometry() != null, "Esc steps back");
        if (ui.polygonTool.mode) key(GLFW.GLFW_KEY_O);
        check(!ui.polygonTool.mode, "the Polygon tool is off");
        if (ui.state.geometry() == null) {
            cmd(GLFW.GLFW_KEY_Z);
        }
        check(
                ui.state.geometry() instanceof PolygonGeometry,
                "the polygon is still there or back: " + ui.state.geometry());
        AreaGeometry shape = ui.state.geometry();
        rightClick(500, 190);
        check(ui.rightClickMenu.quickMenu, "a right click on the polygon opens the menu");
        shot("35_polygon_menu");
        press("quickMenuSave");
        EditBox name = ui.dialogFields[ExactSelectionController.NEW_AREA_FIELD];
        typeInto(name, "Poly");
        key(GLFW.GLFW_KEY_ENTER);
        check(
                ui.areas().stream()
                        .anyMatch(area ->
                                area.name().equals("Poly") && area.geometry().equals(shape)),
                "the polygon is saved as an area");
        shot("36_polygon_saved");
    }

    void contourCancel() throws Exception {
        ExactSelectionController ui = ui();
        key(GLFW.GLFW_KEY_O);
        click(600, 300);
        click(700, 300);
        click(700, 400);
        key(GLFW.GLFW_KEY_ESCAPE);
        check(!ui.polygonTool.mode && ui.polygonTool.draft.isEmpty(), "Esc leaves the Polygon tool");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.polygonTool.mode && ui.polygonTool.draft.size() == 3, "undo brings the dropped contour back");
        shot("37_contour_back");
        key(GLFW.GLFW_KEY_ENTER);
        check(ui.state.geometry() instanceof PolygonGeometry, "Enter closes the contour");
    }

    void ruler() throws Exception {
        ExactSelectionController ui = ui();
        key(GLFW.GLFW_KEY_R);
        check(ui.rulerTool.mode && ui.selectionPanel.compact, "R turns the ruler on");
        shot("38_ruler_on");
        click(600, 60);
        check(ui.rulerTool.pointA != null && ui.rulerTool.pointB == null, "the first click sets A");
        at(700, 120);
        c.waitTick();
        shot("39_ruler_following");
        click(700, 120);
        check(ui.rulerTool.pointB != null, "the second click sets B");
        check(w("quickSave").visible && w("quickSave").active, "a measured line can be saved");
        clickMods(740, 200, GLFW.GLFW_MOD_SUPER);
        shot("40_route");
        GridPoint end = ui.rulerTool.pointB;
        key(GLFW.GLFW_KEY_BACKSPACE);
        check(!end.equals(ui.rulerTool.pointB), "Backspace takes back a route point");
        clickMods(740, 200, GLFW.GLFW_MOD_SUPER);
        press("quickSave");
        check(ui.dialog == Dialog.AREA_NAME && ui.newAreaWindow.routeToSave != null, "Save names the route");
        check(!w("newAreaOpacityButton").visible, "a route has no fill");
        shot("41_route_name");
        key(GLFW.GLFW_KEY_ENTER);
        check(ui.areas().stream().anyMatch(a -> !a.geometry().closed()), "the route is saved");
        check(ui.rulerTool.mode && ui.rulerTool.pointA == null, "the ruler is clear for the next one");
        shot("42_route_saved");
        click(600, 300);
        key(GLFW.GLFW_KEY_ESCAPE);
        check(ui.rulerTool.mode && ui.rulerTool.pointA == null, "Esc clears the measurement first");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(!ui.rulerTool.mode, "Esc then leaves the ruler");
    }

    void zones() throws Exception {
        ExactSelectionController ui = ui();
        SavedAreaStore.Area poly = ui.areas().stream()
                .filter(a -> a.name().equals("Poly"))
                .findFirst()
                .orElseThrow();
        GridPoint vertex = poly.geometry().vertices().get(0);
        at(sx(vertex.x()) + 40, sy(vertex.z()) - 2);
        at(
                (sx(poly.bounds().minX()) + sx(poly.bounds().maxX())) / 2,
                (sy(poly.bounds().minZ()) + sy(poly.bounds().maxZ())) / 2);
        c.waitTick();
        shot("43_zone_hover_tool_on");
        double px = (sx(poly.bounds().minX()) + sx(poly.bounds().maxX())) / 2,
                py = (sy(poly.bounds().minZ()) + sy(poly.bounds().maxZ())) / 2;
        rightClick(px, py);
        check(ui.rightClickMenu.quickMenu && w("quickMenuEdit").visible, "a right click on a zone opens its menu");
        shot("44_zone_menu");
        press("quickMenuHide");
        check(!ui.areasWindow.area(poly.id()).visible(), "Hide hides the zone");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.areasWindow.area(poly.id()).visible(), "undo shows it again");
        key(GLFW.GLFW_KEY_O);
        click(sx(vertex.x()) + 1, sy(vertex.z()) + 1);
        check(
                ui.polygonTool.draft.size() == 1 && ui.polygonTool.draft.get(0).equals(vertex),
                "a vertex snaps to a zone's vertex");
        shot("45_snapped");
        cmd(GLFW.GLFW_KEY_Z);
        clickMods(sx(vertex.x()) + 2, sy(vertex.z()) + 2, GLFW.GLFW_MOD_ALT);
        check(
                ui.polygonTool.draft.size() == 1 && !ui.polygonTool.draft.get(0).equals(vertex),
                "Alt turns snapping off");
        key(GLFW.GLFW_KEY_ESCAPE);
        click(px, py);
        check(ui.state.phase() == Phase.PREVIEW, "a press on a zone starts a selection");
        key(GLFW.GLFW_KEY_ESCAPE);
        rightClick(px, py);
        press("quickMenuEdit");
        check(
                ui.state.source() != null && poly.geometry().equals(ui.state.geometry()),
                "Edit from the menu loads the zone");
        shot("46_zone_edit");
    }

    void waypoints() throws Exception {
        ExactSelectionController ui = ui();
        check(ui.waypoints != null, "the Minimap bridge loaded");
        check(points() != null, "the real Minimap answers");
        int before = points().size();
        selectRect(600, 280, 720, 400);
        double cx = 660, cy = 340;
        rightClick(cx, cy);
        press("centerButton");
        check(ui.dialog == Dialog.CENTER, "Center opens");
        shot("47_center");
        EditBox name = ui.dialogFields[ExactSelectionController.CENTER_NAME_FIELD];
        clickWidget(name);
        typeInto(name, "E2E point");
        press("centerCreate");
        System.out.println("E2E: after create window=" + ui.dialog + " message=" + ui.message);
        check(points().size() == before + 1, "Create makes a real waypoint");
        shot("48_created");
        if (ui.dialog != Dialog.NONE) key(GLFW.GLFW_KEY_ESCAPE);
        cmd(GLFW.GLFW_KEY_Z);
        check(points().size() == before, "undo removes it (removeCreated)");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(points().size() == before + 1, "redo creates it again (recreate)");
        c.runOnClient(mc -> ui.updateSelection());
        press("panelModeButton");
        c.waitTick();
        shot("49_points_button");
        press("pointsButton");
        check(ui.dialog == Dialog.WAYPOINTS, "the waypoint window opens");
        shot("50_points");
        int[] r = rect();
        System.out.println(
                "E2E: rows " + ui.waypointsWindow.filteredPoints().size() + " pending " + ui.waypointsWindow.pending);
        check(
                ui.waypointsWindow.chosenPoints.size() == 1 && w("confirm").active,
                "a waypoint in the selection opens checked");
        click(r[0] + 60, r[1] + 88);
        check(ui.waypointsWindow.chosenPoints.isEmpty() && !w("confirm").active, "a row click unchecks it");
        shot("50b_unchecked");
        click(r[0] + 60, r[1] + 88);
        check(ui.waypointsWindow.chosenPoints.size() == 1 && w("confirm").active, "another click checks it again");
        EditBox rename = ui.dialogFields[ExactSelectionController.RENAME_FIELD];
        clickWidget(rename);
        typeInto(rename, "Renamed point");
        check(w("pointRename").active, "typing enables Rename");
        key(GLFW.GLFW_KEY_ENTER);
        check(points().stream().anyMatch(p -> p.name().equals("Renamed point")), "Enter renames the real waypoint");
        shot("51_renamed");
        press("pointRecolor");
        check(ui.dialog == Dialog.COLOR, "Color opens for waypoints");
        shot("52_point_color");
        clickSwatch(3, WaypointAccess.COLOR_COUNT);
        check(ui.dialog == Dialog.WAYPOINTS, "a color returns to the waypoints");
        check(points().stream().anyMatch(p -> p.color() == 3), "the real waypoint has the color");
        press("pointVisibility");
        press("pointVisibility");
        press("pointVisibility");
        press("pointColor");
        for (int i = 0; i < 16; i++) press("pointColor");
        press("confirm");
        System.out.println("E2E: after delete window=" + ui.dialog + " message=" + ui.message);
        check(points().size() == before, "Delete removes the real waypoint");
        shot("53_deleted");
        cmd(GLFW.GLFW_KEY_Z);
        check(points().size() == before + 1, "undo restores it (restore)");
        check(ui.waypointsWindow.filteredPoints().size() == 1, "the window's list shows the restored waypoint");
        check(
                ui.waypointsWindow.chosenPoints.isEmpty() && !w("confirm").active,
                "the restored waypoint comes back unchecked");
        shot("54_restored");
        if (ui.dialog != Dialog.NONE) press(ui.dialog == Dialog.WAYPOINTS ? "cancel" : "modalCancel");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(points().size() == before, "redo deletes it again");
        press("pointsButton");
        check(ui.dialog == Dialog.WAYPOINTS, "the window opens again");
        cmd(GLFW.GLFW_KEY_Z);
        check(
                points().size() == before + 1
                        && ui.waypointsWindow.filteredPoints().size() == 1,
                "undo inside the window shows it");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(
                points().size() == before && ui.waypointsWindow.filteredPoints().isEmpty(),
                "redo inside the window hides it");
        press("cancel");
        press("panelModeButton");
    }

    void export() throws Exception {
        ExactSelectionController ui = ui();
        selectRect(300, 120, 500, 300);
        press("panelModeButton");
        press("selectionExportButton");
        check(ui.dialog == Dialog.EXPORT, "Export opens");
        shot("55_export");
        press("time");
        press("time");
        press("scale");
        press("labels");
        press("labels");
        press("run");
        check(ui.mapExport.exportRunning || ui.mapExport.lastExport != null, "the export started");
        c.waitFor(mc -> !ui.mapExport.exportRunning, 1200);
        check(
                ui.mapExport.lastExport != null && java.nio.file.Files.exists(ui.mapExport.lastExport),
                "the PNG exists: " + ui.mapExport.lastExport + " message=" + ui.message);
        c.waitTick();
        shot("56_exported");
        check(w("openExportButton").visible || ui.dialog != Dialog.NONE, "the file button shows");
        if (ui.dialog != Dialog.NONE) key(GLFW.GLFW_KEY_ESCAPE);
        press("panelModeButton");
    }

    void reopen() throws Exception {
        ExactSelectionController ui = ui();
        selectRect(400, 120, 560, 300);
        SelectionBounds b = ui.state.bounds();
        key(GLFW.GLFW_KEY_J);
        if (!ui.areaStore.state().showOnMinimap()) press("areaMinimapVisibility");
        check(ui.areaStore.state().showOnMinimap(), "the Minimap switch is on");
        press("managementClose");
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitFor(mc -> mc.screen == null);
        c.waitTicks(5);
        shot("57_world_minimap");
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitForScreen(GuiMap.class);
        c.waitTicks(10);
        check(ui() == ui && ui.enabled() && b.equals(ui.state.bounds()), "the selection survives closing the map");
        check(w("areasButton").visible, "the panel is back");
        shot("58_reopened");
        cmd(GLFW.GLFW_KEY_Z);
        check(!ui.areaStore.state().showOnMinimap(), "the history survives: undo takes back the Minimap switch");
        cmd(GLFW.GLFW_KEY_Z);
        check(ui.state.bounds() == null, "and then the selection");
        key(GLFW.GLFW_KEY_O);
        click(420, 120);
        click(520, 140);
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitFor(mc -> mc.screen == null);
        c.waitTicks(5);
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitForScreen(GuiMap.class);
        c.waitTicks(10);
        check(ui.polygonTool.mode && ui.polygonTool.draft.size() == 2, "a contour survives closing the map");
        shot("59_contour_reopened");
        // Tool off: hover and the toggle.
        key(GLFW.GLFW_KEY_ESCAPE);
        c.runOnClient(mc -> ui.reset());
        press("toggle");
        check(!ui.enabled(), "the toggle turns the tool off");
        SavedAreaStore.Area poly = ui.areas().stream()
                .filter(a -> a.name().equals("Poly"))
                .findFirst()
                .orElse(null);
        if (poly != null)
            at(
                    (sx(poly.bounds().minX()) + sx(poly.bounds().maxX())) / 2,
                    (sy(poly.bounds().minZ()) + sy(poly.bounds().maxZ())) / 2);
        c.waitTick();
        shot("60_tool_off_hover");
        press("toggle");
        check(ui.enabled(), "and on again");
    }

    void resize() throws Exception {
        ExactSelectionController ui = ui();
        selectRect(400, 120, 560, 300);
        key(GLFW.GLFW_KEY_J);
        row(0);
        EditBox name = ui.dialogFields[ExactSelectionController.AREA_NAME_FIELD];
        clickWidget(name);
        in.typeChars("Typed");
        c.waitTick();
        String typed = ui.dialogFields[ExactSelectionController.AREA_NAME_FIELD].getValue();
        in.resizeWindow(1024, 640);
        c.waitTicks(3);
        check(
                ui.dialogFields[ExactSelectionController.AREA_NAME_FIELD]
                        .getValue()
                        .equals(typed),
                "a resize keeps typed text");
        AbstractWidget close = w("managementClose");
        check(
                close.visible && close.getX() + close.getWidth() <= ui.screen.width && close.getY() >= 0,
                "the window fits after a resize");
        shot("61_small_areas");
        cmd(GLFW.GLFW_KEY_A);
        key(GLFW.GLFW_KEY_BACKSPACE);
        key(GLFW.GLFW_KEY_ESCAPE);
        c.waitTick();
        press("panelModeButton");
        shot("62_small_expanded");
        in.resizeWindow(854, 480);
        c.waitTicks(3);
        shot("63_tiny_expanded");
        check(ui.selectionPanel.maxPanelScroll() > 0, "the panel does not fit this window, so it scrolls");
        if (ui.selectionPanel.maxPanelScroll() > 0) {
            at(100, 80);
            in.scroll(-1);
            c.waitTick();
            check(ui.selectionPanel.panelScroll > 0, "the wheel scrolls a panel that does not fit");
            shot("64_tiny_scrolled");
            in.scroll(1);
            c.waitTick();
        }
        press("panelModeButton");
        in.resizeWindow(1600, 900);
        c.waitTicks(3);
    }

    void russian() throws Exception {
        ExactSelectionController ui = ui();
        c.runOnClient(mc -> {
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.languageCode = "ru_ru";
            mc.reloadResourcePacks();
        });
        c.waitFor(mc -> mc.getOverlay() == null, 2400);
        c.waitTicks(5);
        shot("70_ru_compact");
        selectRect(400, 120, 560, 300);
        press("panelModeButton");
        shot("71_ru_expanded");
        rightClick(480, 210);
        shot("72_ru_menu");
        press("quickMenuSave");
        shot("73_ru_new_area");
        key(GLFW.GLFW_KEY_ESCAPE);
        key(GLFW.GLFW_KEY_J);
        row(0);
        shot("74_ru_areas");
        press("autosaveButton");
        shot("75_ru_autosave");
        press("autosaveClose");
        press("manageGroups");
        shot("76_ru_groups");
        press("managementClose");
        press("managementClose");
        key(GLFW.GLFW_KEY_R);
        click(600, 60);
        click(700, 120);
        shot("77_ru_ruler");
        key(GLFW.GLFW_KEY_ESCAPE);
        key(GLFW.GLFW_KEY_ESCAPE);
        check(ui.enabled(), "still on after the language change");
        in.resizeWindow(854, 480);
        c.waitTicks(3);
        press("panelModeButton");
        shot("78_ru_small_panel");
        press("panelModeButton");
        key(GLFW.GLFW_KEY_J);
        shot("79_ru_small_areas");
        key(GLFW.GLFW_KEY_ESCAPE);
    }
}
