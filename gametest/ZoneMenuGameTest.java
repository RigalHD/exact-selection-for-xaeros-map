package dev.exactselection;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import xaero.map.controls.ControlsRegister;
import xaero.map.gui.GuiMap;

public class ZoneMenuGameTest extends MapUiGameTest {
    private String clipboard() {
        return c.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        c = context;
        in = c.getInput();
        c.runOnClient(mc ->
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
        String saved = c.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
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
            section("zone menu", this::zoneMenu);
        } finally {
            c.runOnClient(mc -> mc.keyboardHandler.setClipboard(saved == null ? "" : saved));
        }
        System.out.println("E2E: RESULT passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("E2E: FAILED: " + f);
    }

    private void zoneMenu() throws Exception {
        ExactSelectionController ui = ui();
        // Copy rounding is remembered between sessions; the checks below expect the default.
        c.runOnClient(mc -> ui.autosave.setRounding(ModSettings.Rounding.DOWN));
        press("toggle");
        c.runOnClient(mc -> {
            MapBridge.Frame f = ui.bridge.exactselection$frame();
            int x1 = (int) f.worldX(420), z1 = (int) f.worldZ(120), x2 = (int) f.worldX(540), z2 = (int) f.worldZ(260);
            var group = new SavedAreaStore.Group("g", "Магазины", true);
            var zone = SavedAreaStore.create("Главное здание", new SelectionBounds(x1, z1, x2, z2), 0x8855AA, 25, "g");
            var route = SavedAreaStore.create(
                    "Дорога",
                    new PolylineGeometry(List.of(new GridPoint(x1 - 60, z1), new GridPoint(x1 - 30, z2))),
                    0x55AA55,
                    25,
                    null);
            ui.areaEdits.changeAreas(ui.areaEdits.areaState(List.of(zone, route), List.of(group)), "area_save");
        });
        SavedAreaStore.Area zone = ui.areas().get(0);
        at(480, 190);
        shot("z0_hover");
        rightClick(480, 190);
        check(
                ui.rightClickMenu.quickMenu
                        && w("quickInfoName").visible
                        && w("quickInfoGroup").visible
                        && w("quickInfoSize").visible,
                "the zone's details are in its menu");
        shot("z1_menu");
        press("quickInfoName");
        check(
                zone.name().equals(clipboard()) && ui.rightClickMenu.quickMenu,
                "a click copies the name and keeps the menu");
        press("quickInfoGroup");
        check("Магазины".equals(clipboard()), "a click copies the group");
        press("quickInfoSize");
        check(
                Long.toString((long) zone.geometry().area()).equals(clipboard()),
                "a click copies the area: " + clipboard());
        shot("z2_copied");
        press("quickMenuOpacity");
        check(ui.rightClickMenu.quickMenu, "the actions still work");
        key(GLFW.GLFW_KEY_ESCAPE);
        check(!ui.rightClickMenu.quickMenu, "Esc closes the menu");
        SavedAreaStore.Area route = ui.areas().get(1);
        GridPoint a = route.geometry().vertices().get(0),
                b = route.geometry().vertices().get(1);
        rightClick((sx(a.x()) + sx(b.x())) / 2, (sy(a.z()) + sy(b.z())) / 2);
        check(
                ui.rightClickMenu.quickMenu
                        && w("quickInfoSize").visible
                        && !w("quickInfoGroup").visible
                        && !w("quickMenuEdit").visible,
                "a route's menu has its details");
        shot("z3_route");
        press("quickInfoSize");
        check(
                clipboard().equals(String.valueOf((long) route.geometry().perimeter())),
                "a click copies the route's length: " + clipboard());
        key(GLFW.GLFW_KEY_ESCAPE);
        c.runOnClient(mc -> {
            mc.getLanguageManager().setSelected("ru_ru");
            mc.options.languageCode = "ru_ru";
            mc.reloadResourcePacks();
        });
        c.waitFor(mc -> mc.getOverlay() == null, 2400);
        c.waitTicks(5);
        rightClick(480, 190);
        shot("z4_ru");
        key(GLFW.GLFW_KEY_ESCAPE);
        rightClick(ui.screen.width - 60, ui.screen.height - 20);
        selectEdge();
    }

    /** Near the corner of the screen the menu must stay on screen. */
    private void selectEdge() throws Exception {
        ExactSelectionController ui = ui();
        c.runOnClient(mc -> {
            MapBridge.Frame f = ui.bridge.exactselection$frame();
            int x1 = (int) f.worldX(ui.screen.width - 150),
                    z1 = (int) f.worldZ(ui.screen.height - 90),
                    x2 = (int) f.worldX(ui.screen.width - 40),
                    z2 = (int) f.worldZ(ui.screen.height - 30);
            var corner = SavedAreaStore.create("В углу", new SelectionBounds(x1, z1, x2, z2), 0xAA5555, 25, null);
            List<SavedAreaStore.Area> all = new java.util.ArrayList<>(ui.areas());
            all.add(corner);
            ui.areaEdits.changeAreas(ui.areaEdits.areaState(all, ui.groups()), "area_save");
        });
        rightClick(ui.screen.width - 50, ui.screen.height - 40);
        var d = w("quickMenuDelete");
        check(
                ui.rightClickMenu.quickMenu
                        && d.getY() + d.getHeight() <= ui.screen.height
                        && d.getX() + d.getWidth() <= ui.screen.width,
                "the menu stays on screen in a corner");
        shot("z5_corner");
    }
}
