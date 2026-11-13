package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import xaero.map.controls.ControlsRegister;
import xaero.map.gui.GuiMap;

/**
 * The weak spots the first scenario left out: other dimensions and worlds, a broken file, the zone limit, speed, random
 * input.
 */
public class BugHuntGameTest extends MapUiGameTest {
    private TestSingleplayerContext world;
    private final Set<String> problems = new LinkedHashSet<>();

    private void problem(String text) {
        if (problems.add(text)) System.out.println("E2E: PROBLEM " + text);
    }

    private void openMap() {
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitForScreen(GuiMap.class);
        c.waitTicks(25);
    }

    private void closeMap() {
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitFor(mc -> mc.screen == null);
        c.waitTicks(3);
    }

    private void ensureTool() throws Exception {
        if (!ui().enabled()) press("toggle");
        check(ui().enabled(), "the tool is on");
        // The panel's view is remembered between sessions; the scenarios click the map where the expanded one lies.
        if (!ui().selectionPanel.compact) press("panelModeButton");
    }

    private void saveZone(String name, double x1, double y1, double x2, double y2) throws Exception {
        selectRect(x1, y1, x2, y2);
        rightClick((x1 + x2) / 2, (y1 + y2) / 2);
        press("quickMenuSave");
        typeInto(ui().field(ExactSelectionController.DialogField.NEW_AREA), name);
        key(GLFW.GLFW_KEY_ENTER);
        check(hasZone(name), "the zone was saved: " + name);
    }

    private boolean hasZone(String name) {
        return ui().areas().stream().anyMatch(a -> a.name().equals(name));
    }

    private void setup() {
        world.getClientWorld().waitForChunksRender();
        world.getServer().runCommand("gamemode creative @a");
        in.resizeWindow(1600, 900);
        c.runOnClient(mc -> {
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
        });
        c.waitTicks(10);
        openMap();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        c = context;
        in = c.getInput();
        c.runOnClient(mc ->
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
        TestWorldSave first;
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            world = w;
            first = w.getWorldSave();
            setup();
            section("limit and speed", this::limitAndSpeed);
            section("broken file", this::brokenFile);
            section("unexplored export", this::unexploredExport);
            section("dimensions", this::dimensions);
            section("leaving a world", this::leaveWorld);
        }
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            world = w;
            setup();
            section("second world", this::secondWorld);
        }
        try (TestSingleplayerContext w = first.open()) {
            world = w;
            world.getClientWorld().waitForChunksRender();
            c.waitTicks(10);
            openMap();
            section("back in the first world", this::backInFirstWorld);
            closeMap();
            c.waitTicks(10);
            shot("b40_first_world_minimap");
        }
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            world = w;
            setup();
            section("random input, seed 4", () -> monkey(4, 500));
            shot("b50_after_random");
        }
        in.resizeWindow(1600, 900);
        System.out.println(
                "E2E: RESULT passed=" + passed + " failed=" + failures.size() + " problems=" + problems.size());
        for (String failure : failures) System.out.println("E2E: FAILED: " + failure);
        for (String problem : problems) System.out.println("E2E: PROBLEMS: " + problem);
    }

    private double msPerFrame(String scene) {
        long start = System.nanoTime();
        c.waitTicks(60);
        double ms = (System.nanoTime() - start) / 1e6 / 60;
        System.out.println(String.format(java.util.Locale.ROOT, "E2E: speed %-28s %.1f ms per frame", scene, ms));
        return ms;
    }

    private void limitAndSpeed() throws Exception {
        ExactSelectionController ui = ui();
        ensureTool();
        c.runOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
        });
        double empty = msPerFrame("no zones, tool on");
        List<SavedAreaStore.Area> areas = c.computeOnClient(mc -> {
            MapBridge.Frame f = ui.bridge.exactselection$frame();
            List<SavedAreaStore.Area> list = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                double x = 60 + (i % 40) * 17.5, y = 30 + (i / 40) * 15;
                int wx = (int) Math.floor(f.worldX(x)), wz = (int) Math.floor(f.worldZ(y));
                int size = Math.max(2, (int) (f.worldX(x + 14) - f.worldX(x)));
                if (i % 10 == 0) {
                    // At a close zoom the ring is a few blocks wide and rounding repeats its vertices; each is kept
                    // once.
                    List<GridPoint> ring = new ArrayList<>();
                    for (int v = 0; v < 64; v++) {
                        GridPoint point = new GridPoint(
                                wx + Math.round(size * 2 * Math.cos(v * Math.PI / 32)),
                                wz + Math.round(size * 2 * Math.sin(v * Math.PI / 32)));
                        if (!ring.contains(point)) ring.add(point);
                    }
                    list.add(SavedAreaStore.create("Ring " + i, new PolygonGeometry(ring), 0x33AA66, 25, null));
                } else
                    list.add(SavedAreaStore.create(
                            "Zone " + i, new SelectionBounds(wx, wz, wx + size, wz + size), 0x3366AA, 25, null));
            }
            return list;
        });
        List<SavedAreaStore.Area> tooMany = new ArrayList<>(areas);
        tooMany.add(SavedAreaStore.create("One more", new SelectionBounds(0, 0, 1, 1), 0x112233, 25, null));
        boolean added = c.computeOnClient(
                mc -> ui.areaEdits.changeAreas(ui.areaEdits.areaState(tooMany, ui.groups()), "area_save"));
        check(
                !added && ui.areas().isEmpty() && !ui.message.isEmpty(),
                "1001 zones are refused with a message: " + ui.message);
        boolean saved = c.computeOnClient(
                mc -> ui.areaEdits.changeAreas(ui.areaEdits.areaState(areas, ui.groups()), "area_save"));
        check(saved && ui.areas().size() == 1000, "1000 zones are accepted");
        double zones = msPerFrame("1000 zones, tool on");
        shot("b01_1000_zones");
        at(400, 200);
        double hoverOn = msPerFrame("1000 zones, pointer over them");
        click(300, 120);
        at(500, 300);
        double preview = msPerFrame("1000 zones, second corner");
        key(GLFW.GLFW_KEY_ESCAPE);
        key(GLFW.GLFW_KEY_O);
        at(420, 210);
        double contour = msPerFrame("1000 zones, Polygon tool");
        key(GLFW.GLFW_KEY_O);
        key(GLFW.GLFW_KEY_J);
        double window = msPerFrame("1000 zones, Areas window");
        shot("b02_1000_areas_window");
        long t = System.nanoTime();
        press("areaSelectAll");
        press("areaClearSelection");
        System.out.println("E2E: speed select all + clear " + (System.nanoTime() - t) / 1000000
                + " ms including two 560 ms pauses");
        key(GLFW.GLFW_KEY_ESCAPE);
        press("toggle");
        at(400, 200);
        double off = msPerFrame("1000 zones, tool off, hover");
        press("toggle");
        c.runOnClient(mc -> {
            long u = System.nanoTime();
            for (int i = 0; i < 200; i++) ui.updateVisibility();
            System.out.println("E2E: speed updateVisibility " + (System.nanoTime() - u) / 200000 + " us each");
            MapBridge.Frame f = ui.bridge.exactselection$frame();
            u = System.nanoTime();
            for (int i = 0; i < 200; i++) ui.zoneSnap.point(f, 400, 200);
            System.out.println("E2E: speed zoneSnap.point " + (System.nanoTime() - u) / 200000 + " us each");
            u = System.nanoTime();
            for (int i = 0; i < 200; i++) ui.areasWindow.areaAt(f, 400, 200);
            System.out.println("E2E: speed areaAt " + (System.nanoTime() - u) / 200000 + " us each");
        });
        // Saving rewrites the whole file.
        long w = c.computeOnClient(mc -> {
            long u = System.nanoTime();
            ui.areaEdits.toggleAreaNames();
            ui.areaEdits.toggleAreaNames();
            return (System.nanoTime() - u) / 2000000;
        });
        System.out.println("E2E: speed one write of 1000 zones " + w + " ms");
        c.runOnClient(mc -> ui.areaStore.replace(ui.areaEdits.areaState(List.of(), ui.groups())));
        check(ui.areas().isEmpty(), "the test zones are gone");
    }

    private void unexploredExport() throws Exception {
        ExactSelectionController ui = ui();
        ensureTool();
        c.runOnClient(mc -> {
            ui.state.set(new SelectionBounds(100000, 100000, 100040, 100040));
            ui.syncFields();
        });
        c.waitTick();
        press("panelModeButton");
        press("selectionExportButton");
        check(ui.dialog == Dialog.EXPORT, "Export opens for a place nobody explored");
        press("run");
        c.waitTicks(3);
        check(
                // The scenario before this one leaves the game in Russian.
                !ui.mapExport.exportRunning && (ui.message.contains("explored") || ui.message.contains("исследована")),
                "an unexplored place gets a clear message: " + ui.message);
        shot("b13_unexplored");
        press("panelModeButton");
    }

    private void brokenFile() throws Exception {
        ExactSelectionController ui = ui();
        ensureTool();
        saveZone("Keep one", 400, 120, 480, 200);
        saveZone("Keep two", 500, 120, 580, 200);
        Field f = SavedAreaStore.class.getDeclaredField("file");
        f.setAccessible(true);
        Path file = (Path) f.get(ui.areaStore);
        int count = ui.areas().size();
        closeMap();
        Files.writeString(file, "{ broken");
        openMap();
        check(ui.loadFailed, "a broken area file is noticed when the map opens");
        check(ui.areas().isEmpty(), "no zones are shown from it");
        ensureTool();
        selectRect(400, 260, 480, 320);
        shot("b10_broken_panel");
        check(!w("quickSave").active, "nothing can be saved over a broken file");
        rightClick(440, 290);
        check(ui.rightClickMenu.quickMenu, "the menu still opens");
        press("quickMenuSave");
        check(ui.dialog == Dialog.NONE || !w("modalApply").active, "the menu cannot save either: window=" + ui.dialog);
        key(GLFW.GLFW_KEY_ESCAPE);
        if (ui.dialog != Dialog.NONE) key(GLFW.GLFW_KEY_ESCAPE);
        c.runOnClient(mc -> ui.reset());
        key(GLFW.GLFW_KEY_J);
        check(ui.dialog == Dialog.AREAS && w("areaRestore").visible, "Areas offers to restore");
        shot("b11_broken_areas");
        check(Files.readString(file).equals("{ broken"), "the broken file was left alone");
        press("areaRestore");
        check(
                !ui.loadFailed && ui.areas().size() == count - 1,
                "the previous version is back: " + ui.areas().size());
        check(Files.exists(file.resolveSibling(file.getFileName() + ".broken")), "the broken file is kept beside it");
        shot("b12_restored");
        key(GLFW.GLFW_KEY_ESCAPE);
        saveZone("After restore", 500, 120, 580, 200);
        check(ui.areas().size() == count, "saving works again");
        closeMap();
        c.waitTicks(3);
        // No backup at all: a file that was never readable.
        openMap();
    }

    /** The game closes every screen while the player changes dimension, so the map opens again afterwards. */
    private void teleport(String dimension, int y) {
        world.getServer().runCommand("execute in minecraft:" + dimension + " run tp @a 0 " + y + " 0");
        c.waitTicks(60);
        boolean open = c.computeOnClient(mc -> mc.screen instanceof GuiMap);
        System.out.println("E2E: after teleport to " + dimension + " the map is " + (open ? "still open" : "closed")
                + ", window=" + ui().dialog);
        if (!open) openMap();
        c.waitTicks(20);
    }

    private void dimensions() throws Exception {
        ExactSelectionController ui = ui();
        ensureTool();
        saveZone("Overworld zone", 400, 120, 480, 200);
        selectRect(400, 240, 520, 340);
        key(GLFW.GLFW_KEY_J);
        check(ui.dialog == Dialog.AREAS, "Areas is open when the player changes dimension");
        teleport("the_nether", 100);
        shot("b20_nether_after_tp");
        System.out.println("E2E: nether enabled=" + ui.enabled() + " window=" + ui.dialog + " zones="
                + ui.areas().size() + " frame=" + (frame() != null) + " message=" + ui.message);
        check(
                !ui.enabled() && ui.dialog == Dialog.NONE && ui.state.bounds() == null,
                "the change of dimension closes the window and turns the tool off");
        check(!ui.history.canUndo(), "the old dimension's history is gone");
        check(!hasZone("Overworld zone"), "the Nether has its own zones");
        ensureTool();
        saveZone("Nether zone", 400, 120, 480, 200);
        shot("b21_nether_zone");
        key(GLFW.GLFW_KEY_R);
        click(300, 300);
        click(420, 340);
        shot("b22_nether_ruler");
        String other = ui.rulerTool.rulerOtherDimensionText();
        check(other != null && !other.isEmpty(), "the ruler shows the Overworld distance: " + other);
        key(GLFW.GLFW_KEY_ESCAPE);
        key(GLFW.GLFW_KEY_ESCAPE);
        selectRect(500, 240, 620, 340);
        rightClick(560, 290);
        press("centerButton");
        press("centerCreate");
        c.waitTick();
        double scale = c.computeOnClient(mc -> ui.waypoints.dimensionScale());
        int inside = c.computeOnClient(mc ->
                ui.waypoints.select(ui.context, ui.state.geometry()).points().size());
        System.out.println("E2E: nether waypoint scale=" + scale + " inside=" + inside + " all=" + points());
        check(inside == 1, "the new waypoint lies inside the selection it came from");
        shot("b23_nether_waypoint");
        cmd(GLFW.GLFW_KEY_Z);
        check(
                c.computeOnClient(mc -> ui.waypoints
                                .select(ui.context, ui.state.geometry())
                                .points()
                                .size())
                        == 0,
                "undo removes it");
        teleport("the_end", 100);
        shot("b24_end");
        check(!ui.enabled() && ui.areas().isEmpty(), "the End starts clean");
        ensureTool();
        saveZone("End zone", 400, 120, 480, 200);
        teleport("overworld", -60);
        check(
                !ui.enabled() && hasZone("Overworld zone") && !hasZone("End zone"),
                "back in the Overworld its zones return");
        shot("b25_overworld_again");
        ensureTool();
    }

    private void leaveWorld() throws Exception {
        ExactSelectionController ui = ui();
        ensureTool();
        saveZone("First world zone", 400, 120, 480, 200);
        selectRect(500, 240, 620, 340);
        key(GLFW.GLFW_KEY_O);
        click(300, 300);
        click(380, 320);
        check(
                ui.polygonTool.mode && ui.polygonTool.draft.size() == 2,
                "a contour waits when the player leaves the world");
    }

    private void secondWorld() throws Exception {
        ExactSelectionController ui = ui();
        System.out.println("E2E: second world enabled=" + ui.enabled() + " zones="
                + ui.areas().size() + " draft=" + ui.polygonTool.draft.size() + " undo=" + ui.history.canUndo());
        check(!ui.enabled(), "another world starts with the tool off");
        check(
                ui.state.bounds() == null
                        && ui.polygonTool.draft.isEmpty()
                        && !ui.polygonTool.mode
                        && !ui.rulerTool.mode,
                "nothing is carried over");
        check(!ui.history.canUndo() && !ui.history.canRedo(), "no history is carried over");
        check(
                ui.areas().stream().noneMatch(a -> a.name().equals("First world zone")),
                "the other world's zones are not here");
        shot("b30_second_world");
        ensureTool();
        saveZone("Second world zone", 400, 120, 480, 200);
        closeMap();
        c.waitTicks(5);
        shot("b31_second_world_hud");
        openMap();
    }

    private void backInFirstWorld() throws Exception {
        ExactSelectionController ui = ui();
        check(hasZone("First world zone") && !hasZone("Second world zone"), "the first world has its own zones again");
        check(!ui.enabled(), "the tool is off");
        shot("b32_first_world_map");
    }

    // ---------- Random input ----------

    private String nameOf(Object widget) {
        try {
            List<Object> holders = new ArrayList<>(List.of(ui(), ui().exportWindow));
            for (Field part : ExactSelectionController.class.getDeclaredFields()) {
                if (!UiPart.class.isAssignableFrom(part.getType())) continue;
                part.setAccessible(true);
                holders.add(part.get(ui()));
            }
            for (Object holder : holders)
                for (Field f : holder.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (value == widget) return f.getName();
                    if (value instanceof Object[] array)
                        for (int i = 0; i < array.length; i++)
                            if (array[i] == widget) return f.getName() + "[" + i + "]";
                }
        } catch (ReflectiveOperationException e) {
            return "?";
        }
        return "?";
    }

    private void recover() {
        boolean map = c.computeOnClient(mc -> mc.screen instanceof GuiMap);
        if (map) return;
        c.setScreen(() -> null);
        c.waitTick();
        in.pressKey(ControlsRegister.keyOpenMap);
        c.waitFor(mc -> mc.screen instanceof GuiMap, 300);
        c.waitTick();
    }

    private void invariants(String op) {
        ExactSelectionController ui = ui();
        Object focused = ui.screen == null ? null : ui.screen.getFocused();
        if (focused instanceof AbstractWidget x && ui.widgets.contains(x) && !x.visible)
            problem("focus stays on hidden " + nameOf(x) + " in window " + ui.dialog + " after " + op.split(" ")[0]);
        if (ui.rightClickMenu.quickMenu && ui.dialog != Dialog.NONE) problem("menu open behind window " + ui.dialog);
        if (!ui.polygonTool.draft.isEmpty() && !ui.polygonTool.mode)
            problem("contour without the Polygon tool after " + op.split(" ")[0]);
        if (ui.gesture instanceof MapGesture.Held && ui.dialog != Dialog.NONE)
            problem("held gesture " + ui.gesture.getClass().getSimpleName() + " behind window " + ui.dialog);
        if (ui.rulerTool.mode && ui.polygonTool.mode) problem("ruler and Polygon tool at once");
        if (!ui.enabled()
                && (ui.polygonTool.mode || ui.rulerTool.mode || ui.dialog != Dialog.NONE || ui.state.bounds() != null))
            problem("tool off with state: window " + ui.dialog);
        if (ui.waypointsWindow.pending != null && ui.dialog != Dialog.WAYPOINTS && ui.dialog != Dialog.COLOR)
            problem("waypoint snapshot held in window " + ui.dialog);
        if (ui.screen != null)
            for (AbstractWidget x : ui.widgets) {
                if (x.visible
                        && (x.getX() < 0
                                || x.getY() < -20
                                || x.getX() + x.getWidth() > ui.screen.width + 1
                                || x.getY() + x.getHeight() > ui.screen.height + 1))
                    problem("visible " + nameOf(x) + " leaves the " + ui.screen.width + "x" + ui.screen.height
                            + " screen in window " + ui.dialog + " compact=" + ui.selectionPanel.compact);
            }
    }

    private void monkey(long seed, int steps) {
        Random r = new Random(seed);
        Set<String> messages = new TreeSet<>();
        Set<String> skip = Set.of("pick", "openFolder", "importOpenFolder", "transferFolderButton", "openExportButton");
        int[][] sizes = {{1600, 900}, {1024, 640}, {854, 480}, {1280, 720}};
        int[] keys = {
            GLFW.GLFW_KEY_ESCAPE,
            GLFW.GLFW_KEY_ESCAPE,
            GLFW.GLFW_KEY_ENTER,
            GLFW.GLFW_KEY_TAB,
            GLFW.GLFW_KEY_BACKSPACE,
            GLFW.GLFW_KEY_DELETE,
            GLFW.GLFW_KEY_O,
            GLFW.GLFW_KEY_R,
            GLFW.GLFW_KEY_J,
            GLFW.GLFW_KEY_N,
            GLFW.GLFW_KEY_LEFT,
            GLFW.GLFW_KEY_HOME
        };
        for (int i = 0; i < steps; i++) {
            String op = "?";
            try {
                recover();
                ExactSelectionController ui = ui();
                if (ui.selectionPanel.toggle == null) continue;
                if (!ui.enabled() && r.nextInt(3) > 0) {
                    op = "toggle";
                    clickQuick(ui.selectionPanel.toggle);
                } else {
                    int width = ui.screen.width, height = ui.screen.height, roll = r.nextInt(100);
                    double x = 40 + r.nextDouble() * (width - 80), y = 16 + r.nextDouble() * (height - 40);
                    if (roll < 36) {
                        List<AbstractWidget> list = new ArrayList<>();
                        for (AbstractWidget w : ui.widgets)
                            if (w.visible && w.active && !skip.contains(nameOf(w))) list.add(w);
                        if (!list.isEmpty()) {
                            AbstractWidget w = list.get(r.nextInt(list.size()));
                            op = "widget " + nameOf(w);
                            clickQuick(w);
                        }
                    } else if (roll < 58) {
                        op = "click";
                        at(x, y);
                        pressMods(
                                r.nextInt(5) == 0
                                        ? GLFW.GLFW_MOD_SUPER
                                        : r.nextInt(8) == 0
                                                ? GLFW.GLFW_MOD_SHIFT
                                                : r.nextInt(8) == 0 ? GLFW.GLFW_MOD_ALT : 0);
                    } else if (roll < 63) {
                        op = "double click";
                        at(x, y);
                        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    } else if (roll < 70) {
                        op = "right click";
                        at(x, y);
                        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                    } else if (roll < 75) {
                        op = "drag";
                        at(x, y);
                        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        c.waitTick();
                        at(x + r.nextInt(80) - 40, y + r.nextInt(80) - 40);
                        c.waitTick();
                        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    } else if (roll < 78) {
                        op = "same frame clicks";
                        in.setCursorPos(
                                x
                                        * c.computeOnClient(
                                                mc -> (double) mc.getWindow().getGuiScale()),
                                y
                                        * c.computeOnClient(
                                                mc -> (double) mc.getWindow().getGuiScale()));
                        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    } else if (roll < 82) {
                        op = "scroll";
                        at(x, y);
                        in.scroll(r.nextBoolean() ? 1 : -1);
                    } else if (roll < 90) {
                        int k = keys[r.nextInt(keys.length)];
                        op = "key " + k;
                        keyMods(k, r.nextInt(6) == 0 ? GLFW.GLFW_MOD_SHIFT : 0);
                    } else if (roll < 94) {
                        int choice = r.nextInt(4);
                        op = "undo/redo " + choice;
                        if (choice == 0) cmd(GLFW.GLFW_KEY_Y);
                        else if (choice == 1) cmdShift(GLFW.GLFW_KEY_Z);
                        else cmd(GLFW.GLFW_KEY_Z);
                    } else if (roll < 98) {
                        op = "type";
                        in.typeChars(new String[] {"a", "12", "-", "Зона", "#ff0000", " "}[r.nextInt(6)]);
                    } else if (roll < 99) {
                        op = "map key";
                        in.pressKey(ControlsRegister.keyOpenMap);
                        c.waitTicks(2);
                    } else {
                        int[] size = sizes[r.nextInt(sizes.length)];
                        op = "resize " + size[0];
                        in.resizeWindow(size[0], size[1]);
                    }
                }
                c.waitTick();
                recover();
                invariants(op);
                messages.add(ui().message);
            } catch (Throwable t) {
                StackTraceElement[] trace = t.getStackTrace();
                StringBuilder where = new StringBuilder();
                for (Throwable x = t; x != null; x = x.getCause())
                    for (StackTraceElement e : x.getStackTrace()) {
                        if (e.getClassName().startsWith("dev.exactselection")
                                && !e.getClassName().contains("GameTest")) {
                            where.append(e.getClassName().replace("dev.exactselection.", ""))
                                    .append('.')
                                    .append(e.getMethodName())
                                    .append(':')
                                    .append(e.getLineNumber());
                            break;
                        }
                    }
                problem("CRASH after " + op.split(" ")[0] + " in window " + (ui() == null ? "?" : ui().dialog) + " at "
                        + where + ": " + t);
                t.printStackTrace(System.out);
            }
            if (i % 500 == 0)
                System.out.println(
                        "E2E: random step " + i + " zones=" + ui().areas().size());
        }
        System.out.println("E2E: random messages: " + messages);
        in.resizeWindow(1600, 900);
        c.waitTicks(3);
    }

    private void clickQuick(AbstractWidget w) {
        at(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
        in.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}
