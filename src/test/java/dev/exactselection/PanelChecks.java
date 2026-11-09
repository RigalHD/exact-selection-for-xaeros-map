package dev.exactselection;

import static dev.exactselection.UiHarness.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.gui.GuiMap;

/** The panel beside the map: its lines, hints and copy buttons. */
final class PanelChecks {
    private PanelChecks() {}

    static void checkHintFitting(ExactSelectionController controller) throws Exception {
        Method fit = declared("fitHint", String.class, int.class);
        fit.setAccessible(true);
        String hint = "Ctrl/Cmd: двигать вершины · Двойной ЛКМ по стороне: добавить · Ctrl/Cmd+двойной ЛКМ: удалить";
        require(
                fit.invoke(receiver(controller, fit), hint, 240).equals("Ctrl/Cmd: двигать вершины"),
                "Panel hint cut a word instead of dropping whole hints");
        require(
                fit.invoke(receiver(controller, fit), hint, 404)
                        .equals("Ctrl/Cmd: двигать вершины · Двойной ЛКМ по стороне: добавить"),
                "Expanded panel hint did not keep every whole hint that fits");
        String narrow = (String) fit.invoke(receiver(controller, fit), hint, 60);
        require(
                narrow.endsWith("…") && narrow.length() * 6 <= 60,
                "Overlong single hint is not shortened with an ellipsis");
        Button save = (Button) get(controller, "quickSave"), mode = (Button) get(controller, "panelModeButton");
        // The hint line is drawn at PANEL_Y + 22 with 9-pixel text; buttons must stay clear of it.
        require(
                mode.getY() + mode.getHeight() <= 26 + 22
                        && save.getY() == mode.getY()
                        && save.getY() + save.getHeight() <= 26 + 22
                        && save.getX() + save.getWidth() < mode.getX(),
                "Save is not beside the mode button above the hint line");
        System.out.println("PASS: panel hints keep whole phrases and stay clear of the header buttons");
    }

    static void checkSelectionPanel(ExactSelectionController controller, GuiMap map) throws Exception {
        require((Boolean) get(controller, "compact"), "Compact panel is not the default");
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot snapshot = selection.snapshot();
        require(!((Button) get(controller, "selectionExportButton")).visible, "Export leaked into compact panel");
        checkMainLayout(controller, map);
        press(controller, "panelModeButton");
        Button export = (Button) get(controller, "selectionExportButton");
        require(export.visible && export.active, "Finished unsaved selection cannot be exported");
        for (String name : List.of("dirty", "dragging", "loadFailed")) {
            set(controller, name, true);
            call(controller, "updateVisibility");
            require(!export.active, "Export enabled while " + name);
            set(controller, name, false);
        }
        selection.reset();
        call(controller, "updateVisibility");
        require(!export.active, "Empty selection can be exported");
        selection.click(0, 0);
        selection.hover(10, 10);
        call(controller, "updateVisibility");
        require(!export.active, "Unfinished rectangle can be exported");
        selection.setGeometry(
                new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(10, 0), new GridPoint(0, 10))));
        call(controller, "updateVisibility");
        require(export.active, "Finished polygon cannot be exported");
        @SuppressWarnings("unchecked")
        List<GridPoint> draft = (List<GridPoint>) get(polygon(controller), "draft");
        draft.add(new GridPoint(1, 1));
        call(controller, "updateVisibility");
        require(!export.active, "Unfinished polygon can export an old selection");
        draft.clear();
        selection.restore(snapshot);
        EditBox[] coordinates = (EditBox[]) get(controller, "fields");
        coordinates[0].setValue("12345");
        String value = coordinates[0].getValue();
        press(controller, "panelModeButton");
        press(controller, "panelModeButton");
        require(
                value.equals(coordinates[0].getValue()) && (Boolean) get(controller, "dirty"),
                "Changing panel mode lost coordinate draft");
        require(selection.snapshot().equals(snapshot), "Changing panel mode changed selection");
        set(controller, "dirty", false);
        call(controller, "syncFields");
        for (int[] size : new int[][] {{320, 240}, {480, 270}, {960, 540}}) {
            map.width = size[0];
            map.height = size[1];
            call(controller, "layoutMainPanel");
            call(controller, "updateVisibility");
            checkMainLayout(controller, map);
            boolean exportReached = export.visible;
            for (int i = 0; i < 20; i++) {
                require(controller.mouseScrolled(40, 70, -1), "Panel scroll leaked into native map zoom");
                checkMainLayout(controller, map);
                exportReached |= export.visible;
            }
            require(exportReached, "Export is inaccessible on a small screen");
            press(controller, "panelModeButton");
            checkMainLayout(controller, map);
            require(!controller.mouseScrolled(40, 220, -1), "Compact panel consumes scrolling below its bounds");
            press(controller, "panelModeButton");
        }
        press(controller, "panelModeButton");
        map.width = 640;
        map.height = 360;
        call(controller, "layoutMainPanel");
        call(controller, "updateVisibility");
        System.out.println(
                "PASS: compact default, reversible panel toggle, unsaved selection export guards, responsive panel scrolling");
    }

    static void checkFramedAreasAndMetrics(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        require(
                SelectionDialogs.formatNumber(10004500.5).equals("10004500.5"),
                "A large value with a fraction was shown in scientific notation");
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        var inside = SavedAreaStore.create("Inside", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
        var touching = SavedAreaStore.create("Touching", new SelectionBounds(21, 0, 30, 10), 0x123456, 25, null);
        var far = SavedAreaStore.create(
                "Far",
                new PolygonGeometry(List.of(new GridPoint(100, 100), new GridPoint(120, 100), new GridPoint(100, 120))),
                0x123456,
                25,
                null);
        var crossing = SavedAreaStore.create(
                "Crossing",
                new PolygonGeometry(List.of(new GridPoint(15, -10), new GridPoint(40, -10), new GridPoint(15, 15))),
                0x123456,
                25,
                null);
        try {
            store.replace(new SavedAreaStore.State(List.of(inside, touching, far, crossing), List.of(), true, true));
            selection.set(new SelectionBounds(-5, -5, 20, 20));
            press(controller, "panelModeButton");
            Button zones = (Button) get(controller, "zonesButton"), areas = (Button) get(controller, "areasButton");
            require(areas.visible && zones.visible && zones.active, "Expanded panel lost Areas or Areas in selection");
            require(
                    (Integer) get(controller, "metricsShift") == -12,
                    "Perimeter did not join the area line in the wide panel");
            press(controller, "zonesButton");
            require(
                    window(controller).equals("AREAS")
                            && get(controller, "selectedAreaIds")
                                    .equals(new java.util.LinkedHashSet<>(List.of(inside.id(), crossing.id()))),
                    "Areas in selection must check overlapping areas only, not touching or distant ones");
            press(controller, "managementClose");
            press(controller, "panelModeButton");
            require(
                    (Integer) get(controller, "metricsShift") == 0,
                    "Perimeter shared a line that is too narrow in the compact panel");
        } finally {
            store.replace(saved);
            selection.restore(initial);
            if (!(Boolean) get(controller, "compact")) press(controller, "panelModeButton");
            ((Set<?>) get(controller, "selectedAreaIds")).clear();
            call(controller, "syncFields");
            call(controller, "updateVisibility");
        }
        System.out.println(
                "PASS: Areas stays in the expanded panel, areas in selection by overlap, perimeter beside area when it fits");
    }

    static void checkSeparateCorners(
            ExactSelectionController controller, GuiMap map, Minecraft minecraft, SavedAreaStore store, Unsafe unsafe)
            throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot before = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        Object previousKeyboard = get(minecraft, "keyboardHandler");
        ClipboardKeyboard keyboard = (ClipboardKeyboard) unsafe.allocateInstance(ClipboardKeyboard.class);
        set(minecraft, "keyboardHandler", keyboard);
        try {
            SelectionBounds bounds = new SelectionBounds(-120, 340, 56, -78);
            selection.set(bounds);
            call(controller, "syncFields");
            call(controller, "updateVisibility");
            // The compact panel has no corner rows; the expanded one copies each corner beside its fields.
            set(controller, "compact", true);
            call(controller, "layoutMainPanel");
            call(controller, "updateVisibility");
            require(
                    !((Button) get(controller, "copyCoordinates")).visible
                            && !((Button) get(controller, "clear")).visible,
                    "The compact panel still shows the corners or Clear");
            checkMainLayout(controller, map);
            for (boolean compact : List.of(false)) {
                set(controller, "compact", compact);
                call(controller, "layoutMainPanel");
                call(controller, "updateVisibility");
                Button a = (Button) get(controller, "copyCoordinates"), b = (Button) get(controller, "copyCornerB");
                require(
                        a.visible && a.active && b.visible && b.active && a.getY() < b.getY(),
                        "Corner buttons are missing or are not beside separate coordinate rows");
                checkMainLayout(controller, map);
                press(controller, "copyCoordinates");
                require("-120 340".equals(keyboard.copied), "Corner A clipboard format is wrong");
                press(controller, "copyCornerB");
                require("56 -78".equals(keyboard.copied), "Corner B clipboard format is wrong");
            }
            set(controller, "compact", true);
            call(controller, "layoutMainPanel");
            call(controller, "updateVisibility");
            var area = SavedAreaStore.create("Corners", bounds, AreaPalette.DEFAULT, 25, null);
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            press(controller, "areasButton");
            pick(controller, store, area.id());
            call(controller, "layoutDialog");
            Button[] copies = areaCopies(controller);
            // Four corners by direction (north is -Z, west is -X): NW and NE on one line, SW and SE below.
            require(
                    copies[1].visible
                            && copies[7].visible
                            && copies[8].visible
                            && copies[6].visible
                            && copies[1].getY() == copies[7].getY()
                            && copies[8].getY() == copies[6].getY()
                            && copies[1].getY() < copies[8].getY()
                            && copies[1].getX() < copies[7].getX(),
                    "Saved rectangle lacks its four corner buttons");
            checkLayout(controller);
            String[] expected = {"-120 -78", "56 -78", "-120 340", "56 340"};
            int[] order = {1, 7, 8, 6};
            for (int i = 0; i < 4; i++) {
                copies[order[i]].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                require(expected[i].equals(keyboard.copied), "Saved corner " + i + " copied " + keyboard.copied);
            }
            var polygon = SavedAreaStore.create(
                    "Polygon",
                    new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(3, 0), new GridPoint(0, 4))),
                    AreaPalette.DEFAULT,
                    25,
                    null);
            store.replace(new SavedAreaStore.State(List.of(polygon), List.of(), true, true));
            pick(controller, store, polygon.id());
            call(controller, "layoutDialog");
            require(
                    copies[1].visible && !copies[6].visible && !copies[7].visible && !copies[8].visible,
                    "Polygon vertex copy exposed rectangle corners");
            copies[1].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            require("0 0; 3 0; 0 4".equals(keyboard.copied), "Polygon vertex coordinate pairs must use spaces");
            // Its area is 6 blocks² exactly, its perimeter 12; a sloped one has fractions, copied rounded down, up or
            // exactly.
            var sloped = SavedAreaStore.create(
                    "Sloped",
                    new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(3, 0), new GridPoint(0, 3))),
                    AreaPalette.DEFAULT,
                    25,
                    null);
            store.replace(new SavedAreaStore.State(List.of(sloped), List.of(), true, true));
            pick(controller, store, sloped.id());
            call(controller, "layoutDialog");
            AutosaveSettings settings = (AutosaveSettings) get(controller, "autosave");
            AutosaveSettings.Rounding rounding = settings.rounding();
            try {
                settings.setRounding(AutosaveSettings.Rounding.DOWN);
                double slopedArea = sloped.geometry().area(),
                        perimeter = sloped.geometry().perimeter();
                require(
                        slopedArea != Math.floor(slopedArea) && perimeter != Math.floor(perimeter),
                        "The sloped polygon has whole metrics");
                copies[2].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                require(
                        Long.toString((long) Math.floor(slopedArea)).equals(keyboard.copied),
                        "Area was not rounded down: " + keyboard.copied);
                copies[3].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                require(
                        Long.toString((long) Math.floor(perimeter)).equals(keyboard.copied),
                        "Perimeter was not rounded down: " + keyboard.copied);
                set(controller, "autosaveOpen", true);
                call(controller, "updateVisibility");
                call(controller, "layoutDialog");
                press(controller, "roundingButton");
                checkLayout(controller);
                require(
                        settings.rounding() == AutosaveSettings.Rounding.UP
                                && AutosaveSettings.load(store.root()).rounding() == AutosaveSettings.Rounding.UP,
                        "Rounding up was not switched on and saved");
                copies[2].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                require(
                        Long.toString((long) Math.ceil(slopedArea)).equals(keyboard.copied),
                        "Area was not rounded up: " + keyboard.copied);
                copies[3].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                require(
                        Long.toString((long) Math.ceil(perimeter)).equals(keyboard.copied),
                        "Perimeter was not rounded up: " + keyboard.copied);
                press(controller, "roundingButton");
                require(settings.rounding() == AutosaveSettings.Rounding.EXACT, "Rounding did not cycle to none");
                copies[2].onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                require(keyboard.copied.contains("."), "Exact copying still rounded the area: " + keyboard.copied);
            } finally {
                settings.setRounding(rounding);
                set(controller, "autosaveOpen", false);
            }
            System.out.println(
                    "PASS: separate A/B corner copying in the expanded panel, four compass corners for saved areas, none in the compact panel, space-separated coordinates, polygon vertices preserved");
        } finally {
            set(minecraft, "keyboardHandler", previousKeyboard);
            store.replace(saved);
            selection.restore(before);
            ((Set<?>) get(controller, "selectedAreaIds")).clear();
            set(controller, "areaId", null);
            set(controller, "dialog", dialog(controller, "NONE"));
            set(controller, "compact", true);
            set(controller, "message", "");
            call(controller, "layoutMainPanel");
            call(controller, "syncFields");
            call(controller, "updateVisibility");
        }
    }
}
