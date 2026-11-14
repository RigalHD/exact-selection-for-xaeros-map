package dev.exactselection;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import xaero.map.controls.ControlsRegister;
import xaero.map.gui.GuiMap;

public class LayersGameTest extends MapUiGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        c = context;
        in = c.getInput();
        c.runOnClient(mc ->
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
        try (TestSingleplayerContext w = c.worldBuilder().create()) {
            w.getClientWorld().waitForChunksRender();
            in.resizeWindow(1600, 900);
            c.runOnClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
            });
            c.waitTicks(10);
            in.pressKey(ControlsRegister.keyOpenMap);
            c.waitForScreen(GuiMap.class);
            c.waitTicks(25);
            section("layers and overlap", this::layers);
            section("shift on a vertex", this::shiftVertex);
        }
        System.out.println("E2E: RESULT passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("E2E: FAILED: " + f);
    }

    private List<String> order() {
        return c.computeOnClient(
                mc -> ui().areas().stream().map(SavedAreaStore.Area::name).toList());
    }

    private void layers() throws Exception {
        ExactSelectionController ui = ui();
        press("toggle");
        c.runOnClient(mc -> {
            MapBridge.Frame f = ui.bridge.exactselection$frame();
            int x = (int) f.worldX(360), z = (int) f.worldZ(120);
            var red =
                    SavedAreaStore.create("Красная", new SelectionBounds(x, z, x + 119, z + 119), 0xDD2222, 100, null);
            var blue = SavedAreaStore.create(
                    "Синяя", new SelectionBounds(x + 60, z + 60, x + 179, z + 179), 0x2222DD, 100, null);
            ui.areaEdits.changeAreas(ui.areaEdits.areaState(List.of(red, blue), List.of()), "area_save");
        });
        var red = ui.areas().get(0);
        c.waitTicks(5);
        shot("l0_blue_on_top");
        double rx = sx(red.geometry().bounds().minX() + 20),
                rz = sy(red.geometry().bounds().minZ() + 20);
        rightClick(rx, rz);
        check(
                ui.rightClickMenu.quickMenu && w("quickMenuRaise").visible && w("quickMenuLower").visible,
                "the menu offers the layers");
        check(w("quickMenuRaise").active && !w("quickMenuLower").active, "red can only go up");
        c.waitTicks(2);
        shot("l1_menu");
        press("quickMenuRaise");
        check(order().equals(List.of("Синяя", "Красная")), "red is now above blue: " + order());
        check(
                ui.rightClickMenu.quickMenu && !w("quickMenuRaise").active && w("quickMenuLower").active,
                "the menu stays open and up is over");
        key(GLFW.GLFW_KEY_ESCAPE);
        at(20, 20);
        c.waitTicks(3);
        shot("l2_red_on_top");
        cmd(GLFW.GLFW_KEY_Z);
        check(order().equals(List.of("Красная", "Синяя")), "undo restores the order: " + order());
        c.waitTicks(3);
        shot("l3_undone");
        cmdShift(GLFW.GLFW_KEY_Z);
        check(order().equals(List.of("Синяя", "Красная")), "redo moves it up again: " + order());

        press("areasButton");
        c.runOnClient(mc -> {
            ui.areasWindow.selectedAreaIds.addAll(
                    ui.areas().stream().map(SavedAreaStore.Area::id).toList());
            ui.areasWindow.areaId = ui.areas().get(0).id();
        });
        c.waitTicks(3);
        check(w("overlapCopy").visible, "two checked zones show the overlap");
        shot("l4_overlap");
        press("overlapCopy");
        String copied = c.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
        check("3600".equals(copied), "60×60 was copied: " + copied);
        press("managementClose");
        c.runOnClient(mc -> ui.areasWindow.selectedAreaIds.clear());
    }

    private void shiftVertex() throws Exception {
        ExactSelectionController ui = ui();
        c.runOnClient(mc -> {
            MapBridge.Frame f = ui.bridge.exactselection$frame();
            long x = (long) f.worldX(180), z = (long) f.worldZ(330);
            var shape = SavedAreaStore.create(
                    "Контур",
                    new PolygonGeometry(List.of(
                            new GridPoint(x, z),
                            new GridPoint(x + 150, z + 12),
                            new GridPoint(x + 120, z + 110),
                            new GridPoint(x + 10, z + 100))),
                    0x22AA55,
                    25,
                    null);
            List<SavedAreaStore.Area> all = new java.util.ArrayList<>(ui.areas());
            all.add(shape);
            ui.areaEdits.changeAreas(ui.areaEdits.areaState(all, List.of()), "area_save");
            ui.areasWindow.loadArea(shape);
        });
        c.waitTicks(3);
        List<GridPoint> start = c.computeOnClient(mc -> ui.state.geometry().vertices());
        GridPoint v = start.get(1);
        // Hold the button on the vertex, go off the lines through its neighbours, then press and release Shift.
        at(sx(v.x()), sy(v.z()));
        in.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        at(sx(v.x()) + 8, sy(v.z()) + 14);
        c.waitTicks(3);
        GridPoint free = c.computeOnClient(mc -> ui.state.geometry().vertices().get(1));
        check(
                free.x() != start.get(0).x()
                        && free.z() != start.get(0).z()
                        && free.z() != start.get(2).z()
                        && free.x() != start.get(2).x(),
                "without Shift the vertex is free: " + free);
        shot("s0_free");
        in.holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        c.waitTicks(3);
        GridPoint held = c.computeOnClient(mc -> ui.state.geometry().vertices().get(1));
        check(
                held.x() == start.get(0).x()
                        || held.z() == start.get(0).z()
                        || held.x() == start.get(2).x()
                        || held.z() == start.get(2).z(),
                "Shift lines it up: " + held + " of " + start);
        shot("s1_shift");
        in.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        c.waitTick();
        in.releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        c.waitTicks(3);
        check(
                c.computeOnClient(mc -> ui.state.geometry().vertices().get(1)).equals(held),
                "release keeps the aligned point");
        shot("s2_done");
    }
}
