package dev.exactselection;

import static dev.exactselection.UiHarness.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;
import xaero.map.gui.GuiMap;

/** The waypoint, color and export windows. */
final class WindowChecks {
    private WindowChecks() {}

    static void checkExportNotice(ExactSelectionController controller, GuiMap map, Path directory) throws Exception {
        Button open = (Button) get(controller, "openExportButton");
        require(!open.visible, "File button is visible before an export");
        Method completed = declared("exportCompleted", AreaExportService.Result.class);
        completed.setAccessible(true);
        Path first = directory.resolve("export with spaces.png");
        Files.write(first, new byte[] {1});
        completed.invoke(receiver(controller, completed), new AreaExportService.Result(first, 100, 200));
        controller.updateVisibility();
        require(open.visible && open.active, "Successful export did not show the file button");
        // The bottom toast hides after a while, but the file button stays until another message replaces the notice.
        Method toast = declared("renderToast", net.minecraft.client.gui.GuiGraphics.class);
        toast.setAccessible(true);
        set(controller, "toastText", get(controller, "message"));
        set(controller, "toastShownAt", net.minecraft.util.Util.getMillis() - 7000);
        toast.invoke(receiver(controller, toast), (Object) null);
        call(controller, "updateVisibility");
        require(open.visible, "The file button disappeared with the toast");
        Object notice = get(controller, "message");
        set(controller, "message", "x");
        set(controller, "toastText", "x");
        set(controller, "toastShownAt", net.minecraft.util.Util.getMillis() - 7000);
        toast.invoke(receiver(controller, toast), (Object) null);
        require(get(controller, "message").equals(""), "An expired ordinary message was kept");
        set(controller, "message", notice);
        call(controller, "updateVisibility");
        require(
                get(controller, "lastExport").equals(first.toAbsolutePath()),
                "Wrong file attached to the export button");
        require(
                !get(controller, "message").toString().contains(directory.toString()),
                "Full export path leaked into panel text");
        checkMainLayout(controller, map);
        press(controller, "panelModeButton");
        for (int[] size : new int[][] {{320, 240}, {480, 270}, {960, 540}}) {
            map.width = size[0];
            map.height = size[1];
            call(controller, "layoutMainPanel");
            call(controller, "updateVisibility");
            boolean reached = open.visible;
            for (int i = 0; i < 20; i++) {
                controller.mouseScrolled(40, 70, -1);
                checkMainLayout(controller, map);
                reached |= open.visible;
            }
            require(reached, "File button cannot be reached on a small screen");
        }
        press(controller, "panelModeButton");
        press(controller, "areasButton");
        completed.invoke(receiver(controller, completed), new AreaExportService.Result(first, 100, 200));
        controller.updateVisibility();
        for (int[] size : new int[][] {{320, 240}, {480, 270}, {960, 540}}) {
            map.width = size[0];
            map.height = size[1];
            call(controller, "layoutDialog");
            call(controller, "updateVisibility");
            require(open.visible, "Saved-area export did not show the file button");
            checkLayout(controller);
        }
        set(controller, "areaDeleteConfirmation", true);
        call(controller, "updateVisibility");
        require(!open.visible, "File button leaked into deletion confirmation");
        set(controller, "areaDeleteConfirmation", false);
        call(controller, "updateVisibility");
        press(controller, "managementClose");
        call(controller, "layoutMainPanel");
        call(controller, "updateVisibility");
        require(open.visible, "File button was lost on returning from saved areas");
        checkMainLayout(controller, map);
        Path second = directory.resolve("new export.png");
        Files.write(second, new byte[] {2});
        completed.invoke(receiver(controller, completed), new AreaExportService.Result(second, 300, 400));
        controller.updateVisibility();
        require(
                get(controller, "lastExport").equals(second.toAbsolutePath()),
                "Export button still points to previous PNG");
        Files.delete(second);
        press(controller, "openExportButton");
        require(
                !open.visible && get(controller, "message").toString().contains("export_file_missing"),
                "Missing file did not produce a useful message");
        set(controller, "message", "");
        map.width = 640;
        map.height = 360;
        call(controller, "layoutMainPanel");
        call(controller, "updateVisibility");
        System.out.println(
                "PASS: export file button, latest PNG path, both panels, responsive layouts, and missing-file handling");
    }

    static void checkWaypointManagement(
            GuiMap map, MapBridge bridge, Minecraft minecraft, SavedAreaStore store, MapBridge.Context context)
            throws Exception {
        FakeWaypoints access = new FakeWaypoints();
        ExactSelectionController controller = new ExactSelectionController(map, bridge, minecraft, store, access);
        autosaveProperties(controller);
        controller.init();
        SelectionState selection = (SelectionState) get(controller, "state");
        selection.enable();
        set(controller, "context", context);
        set(controller, "historyContext", context);
        set(controller, "compact", false);
        call(controller, "layoutMainPanel");
        call(controller, "updateVisibility");
        try {
            press(controller, "pointsButton");
            checkLayout(controller);
            require(
                    ((Set<?>) get(controller, "chosenPoints")).isEmpty(),
                    "Opening all waypoints selected the entire set");
            require(
                    !((Button) get(controller, "confirm")).active,
                    "Entire set can be deleted without explicit selection");
            int[] rect = dialogRect(controller);
            controller.mouseClicked(left(rect[0] + 20, rect[1] + 85, 0), false);
            enter(controller, map, 9, "Renamed");
            require(
                    access.points.get(0).name().equals("Renamed")
                            && access.points.get(1).name().equals("Second")
                            && window(controller).equals("WAYPOINTS"),
                    "Waypoint rename changed unselected points or closed the window");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(access.points.get(0).name().equals("First"), "Waypoint rename undo failed");
            press(controller, "pointRecolor");
            checkLayout(controller);
            rect = dialogRect(controller);
            controller.mouseClicked(
                    left((Integer) call(controller, "swatchX", rect[0], rect[2], 2) + 2, rect[1] + 36, 0), false);
            require(
                    access.points.get(0).color() == 2
                            && access.points.get(1).color() == 0
                            && window(controller).equals("WAYPOINTS"),
                    "Waypoint palette did not edit only checked rows");
            access.points.set(0, access.points.get(0).with("External", 2));
            enter(controller, map, 9, "Stale attempt");
            require(
                    access.points.get(0).name().equals("External")
                            && get(controller, "message").toString().contains("changed"),
                    "Stale waypoint edit was not rejected");
            press(controller, "confirm");
            require(
                    access.points.size() == 2 && window(controller).equals("WAYPOINTS"),
                    "Waypoint delete did not retain the management window");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(access.points.size() == 3, "Waypoint deletion cannot be undone from its window");
            require(
                    ((List<?>) call(controller, "filteredPoints")).size() == 3,
                    "The window's list did not follow the undone deletion");
            press(controller, "cancel");
            selection.set(new SelectionBounds(-1, -1, 15, 15));
            call(controller, "updateSelection");
            call(controller, "updateVisibility");
            press(controller, "pointsButton");
            require(
                    ((Set<?>) get(controller, "chosenPoints")).size() == 2,
                    "Opening selection did not select its two waypoints");
            press(controller, "pointScope");
            require(
                    ((Set<?>) get(controller, "chosenPoints")).size() == 2,
                    "Entire-set switch unexpectedly selected more points");
            press(controller, "pointScope");
            require(
                    ((Set<?>) get(controller, "chosenPoints")).size() == 2,
                    "Selection scope did not select the contained points");
            text(controller, 7).setValue("Second");
            enter(controller, map, 9, "Filtered");
            require(
                    access.points.stream()
                                            .filter(p -> p.name().equals("Filtered"))
                                            .count()
                                    == 1
                            && access.points.stream().anyMatch(p -> p.name().equals("External")),
                    "Waypoint rename ignored the name filter");
            text(controller, 7).setValue("");
            call(controller, "updateVisibility");
            for (int[] size : List.of(new int[] {320, 240}, new int[] {480, 270}, new int[] {960, 540})) {
                map.width = size[0];
                map.height = size[1];
                controller.init();
                checkLayout(controller);
                press(controller, "pointRecolor");
                checkLayout(controller);
                key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
                require(!window(controller).equals("COLOR"), "Esc did not close the color window");
            }
            press(controller, "cancel");
            System.out.println(
                    "PASS: safe entire-set waypoint management, rename/color/delete, undo, stale snapshot guards, scope and responsive layouts");
        } finally {
            @SuppressWarnings("unchecked")
            List<AbstractWidget> added = (List<AbstractWidget>) get(controller, "widgets");
            map.children().removeIf(added::contains);
            map.width = 640;
            map.height = 360;
        }
    }

    static void checkWaypointRows(ExactSelectionController controller) throws Exception {
        List<WaypointAccess.Point> points = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            points.add(new WaypointAccess.Point(new Object(), i, 64, i, "Point " + i, false, true));
        set(controller, "pending", new WaypointAccess.Snapshot(new Object(), new Object(), "set", points));
        set(controller, "dialog", dialog(controller, "WAYPOINTS"));
        set(controller, "modalScroll", 0);
        call(controller, "updateVisibility");
        Set<?> chosen = (Set<?>) get(controller, "chosenPoints");
        chosen.clear();
        try {
            // 640x360: dialog at (40, 15), 330 high, 12 visible rows starting at y + 81.
            controller.mouseClicked(left(60, 15 + 81 + 11 * 14 + 5, 0), false);
            require(
                    chosen.size() == 1 && chosen.contains(points.get(11).identity()),
                    "Last visible waypoint row is not clickable");
            controller.mouseClicked(left(60, 15 + 81 + 12 * 14 + 1, 0), false);
            require(chosen.size() == 1, "Clicking below the waypoint list toggled a hidden row");
            // 30 rows, 12 visible: the scrollbar sits at x + w - 17 .. x + w - 7 = 583..593 beside the rows.
            controller.mouseClicked(left(588, 15 + 81 + 12 * 14 - 1, 0), false);
            require(
                    (Integer) get(controller, "modalScroll") == 18 && chosen.size() == 1,
                    "Clicking the bottom of the scrollbar did not scroll to the end (or toggled a row)");
            controller.mouseReleased(left(588, 15 + 81, 0));
            require(
                    (Integer) get(controller, "modalScroll") == 0 && !(Boolean) get(controller, "scrollbarDragging"),
                    "Dragging the scrollbar thumb to the top did not scroll back");
        } finally {
            chosen.clear();
            set(controller, "pending", null);
            set(controller, "dialog", dialog(controller, "NONE"));
            call(controller, "updateVisibility");
        }
        System.out.println("PASS: waypoint rows accept clicks only where they are drawn");
    }

    static void checkColorPicker(ExactSelectionController controller) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        int previous = (Integer) get(controller, "newAreaColor");
        try {
            require(
                    AreaPalette.hsv(0, 1, 1) == 0xFF0000
                            && AreaPalette.hsv(1 / 3.0, 1, 1) == 0x00FF00
                            && AreaPalette.hsv(0.5, 0, 0.5) == 0x808080,
                    "HSV conversion is wrong");
            for (int color : AreaPalette.COLORS) {
                double[] hsv = AreaPalette.toHsv(color);
                require(
                        AreaPalette.hsv(hsv[0], hsv[1], hsv[2]) == color,
                        "HSV round trip changed " + AreaPalette.format(color));
            }
            selection.enable();
            selection.set(new SelectionBounds(0, 0, 9, 9));
            call(controller, "openAreaNameDialog");
            int[] r = dialogRect(controller);
            int preview = (Integer) call(controller, "swatchX", r[0], r[2], AreaPalette.COLUMNS - 1);
            controller.mouseClicked(left(preview + 2, r[1] + 175, 0), false);
            require(window(controller).equals("PICKER"), "The color preview did not open the picker");
            r = dialogRect(controller);
            int[] square = pickerRect("pickerSquare", r), hue = pickerRect("pickerHueBar", r);
            // Hue: drag from the top (red) down a third (green); then the square's top-right is the full color.
            controller.mouseClicked(left(hue[0] + 4, hue[1], 0), false);
            controller.mouseReleased(left(hue[0] + 4, hue[1] + (hue[3] - 1) / 3, 0));
            controller.mouseClicked(left(square[0] + square[2] - 1, square[1], 0), false);
            controller.mouseReleased(left(square[0] + square[2] + 20, square[1] - 20, 0));
            int picked = (Integer) call(controller, "pickerColor");
            require(
                    (picked >> 8 & 255) == 255 && (picked & 255) == 0 && (picked >> 16) < 16,
                    "Picker did not give a full green: " + AreaPalette.format(picked));
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(
                    window(controller).equals("AREA_NAME") && (Integer) get(controller, "newAreaColor") == previous,
                    "Esc in the picker changed the color or left the save window");
            controller.mouseClicked(left(preview + 2, r[1] + 175, 0), false);
            square = pickerRect("pickerSquare", dialogRect(controller));
            controller.mouseClicked(left(square[0], square[1] + square[3] - 1, 0), false);
            controller.mouseReleased(left(square[0], square[1] + square[3] - 1, 0));
            press(controller, "modalApply");
            require(
                    window(controller).equals("AREA_NAME")
                            && (Integer) get(controller, "newAreaColor") == 0
                            && ((EditBox[]) get(controller, "dialogFields"))
                                    [10].getValue()
                                    .equals("#000000"),
                    "Apply in the picker did not hand black back to the save window");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            // The zone color window has a preview too; the picker's Apply recolors the zone at once.
            SavedAreaStore store = (SavedAreaStore) get(controller, "areaStore");
            SavedAreaStore.State savedAreas = store.state();
            var area = SavedAreaStore.create("Picked", new SelectionBounds(0, 0, 9, 9), 0x123456, 25, null);
            store.replace(new SavedAreaStore.State(List.of(area), List.of()));
            try {
                selection.load(area.geometry(), area.id());
                call(controller, "openQuickColor");
                r = dialogRect(controller);
                controller.mouseClicked(left(r[0] + r[2] - 146 + 2, r[1] + 115, 0), false);
                require(window(controller).equals("PICKER"), "The zone color preview did not open the picker");
                square = pickerRect("pickerSquare", dialogRect(controller));
                controller.mouseClicked(left(square[0], square[1] + square[3] - 1, 0), false);
                controller.mouseReleased(left(square[0], square[1] + square[3] - 1, 0));
                press(controller, "modalApply");
                require(
                        window(controller).equals("NONE")
                                && store.state().areas().get(0).color() == 0,
                        "Apply in the picker did not recolor the zone: "
                                + AreaPalette.format(
                                        store.state().areas().get(0).color()));
            } finally {
                store.replace(savedAreas);
                ((SelectionHistory) get(controller, "history")).clear();
            }
        } finally {
            set(controller, "dialog", dialog(controller, "NONE"));
            set(controller, "newAreaColor", previous);
            selection.restore(initial);
            call(controller, "syncFields");
            call(controller, "updateVisibility");
        }
        System.out.println(
                "PASS: color picker from the save and zone color previews: HSV conversion, hue bar, square with clamping, Esc and Apply");
    }

    static void checkExportWindow(ExactSelectionController controller, GuiMap map, Path directory) throws Exception {
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
            Path defaultFolder = directory
                    .resolve("xaero-exact-selection")
                    .resolve("map-exports")
                    .toAbsolutePath();
            require(
                    folder.getValue().isEmpty() && window.folder().equals(defaultFolder),
                    "Default export folder must remain an empty field resolving to the default path: "
                            + folder.getValue());
            Button run = window.run;
            require(run.visible && run.active, "Export is unavailable for a small selection");
            require(
                    !window.ownFill.visible && window.unfilledArea() == null,
                    "A plain selection offered to drop its own area fill");
            ExportPreferences preferences = window.preferences;
            Button areas = window.areas, labels = window.labels;
            require(
                    preferences.areas && areas.visible && areas.active && labels.active,
                    "Export must include areas by default");
            boolean previousLabels = preferences.labels;
            press(controller, window.areas, "areas");
            require(
                    !preferences.areas && !labels.active && preferences.labels == previousLabels,
                    "Hiding exported areas must disable labels without losing their preference");
            preferences.save();
            require(!ExportPreferences.load(directory).areas, "Area export visibility was not remembered");
            press(controller, window.areas, "areas");
            require(
                    preferences.areas && labels.active && preferences.labels == previousLabels,
                    "Enabling exported areas did not restore the label control");
            preferences.save();
            int scale = preferences.scale;
            press(controller, window.scale, "scale");
            require(
                    preferences.scale != scale && window.windowScale == preferences.scale,
                    "Scale button did not change the scale");
            boolean night = preferences.night;
            press(controller, window.time, "time");
            require(preferences.night != night, "Day/night button did not toggle");
            folder.setValue("relative/path");
            call(controller, "updateVisibility");
            require(!run.active, "A relative folder can be exported to");
            press(controller, window.standardFolder, "standardFolder");
            require(
                    run.active && folder.getValue().isEmpty() && window.folder().equals(defaultFolder),
                    "Default folder button did not clear the field and restore a usable folder");
            for (int[] size :
                    List.of(new int[] {320, 240}, new int[] {480, 270}, new int[] {960, 540}, new int[] {640, 360})) {
                map.width = size[0];
                map.height = size[1];
                // Screen.rebuildWidgets clears the old widgets before init() adds new ones.
                for (String name : List.of("children", "narratables", "renderables"))
                    ((List<?>) get(map, name)).clear();
                controller.init();
                checkLayout(controller);
            }
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(window(controller).equals("NONE"), "Esc did not close the export window back to the map");
            // Exporting a saved area can leave out that area's own fill; the choice is remembered.
            SavedAreaStore store = (SavedAreaStore) get(controller, "areaStore");
            SavedAreaStore.State savedAreas = store.state();
            var own = SavedAreaStore.create("Own", new SelectionBounds(0, 0, 99, 49), 0x123456, 40, null);
            store.replace(new SavedAreaStore.State(List.of(own), List.of()));
            try {
                selection.load(own.geometry(), own.id());
                call(controller, "updateVisibility");
                press(controller, "selectionExportButton");
                require(
                        window.ownFill.visible && window.ownFill.active && window.unfilledArea() == null,
                        "Exporting a saved area did not offer its own fill, on by default");
                checkLayout(controller);
                press(controller, window.ownFill, "ownFill");
                require(own.id().equals(window.unfilledArea()), "Turning off the area's fill did not reach the export");
                preferences.save();
                require(!ExportPreferences.load(directory).ownFill, "The area's own fill choice was not remembered");
                press(controller, window.areas, "areas");
                require(
                        !window.ownFill.active && window.unfilledArea() == null,
                        "The fill switch works with areas hidden");
                press(controller, window.areas, "areas");
                press(controller, window.ownFill, "ownFill");
                preferences.save();
                key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            } finally {
                store.replace(savedAreas);
            }
            preferences.scale = 4;
            selection.set(new SelectionBounds(0, 0, 3999, 3999));
            call(controller, "updateVisibility");
            press(controller, "selectionExportButton");
            require(
                    window(controller).equals("EXPORT"),
                    "Export window did not reopen; message=" + get(controller, "message"));
            // init() above rebuilt the widgets, so read the current button.
            require(!window.run.active, "An image above the pixel limit can be exported");
            require(
                    window.windowScale == 1 && preferences.scale == 4,
                    "A large area lowered the remembered export scale instead of only this window's");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        } finally {
            set(controller, "dialog", dialog(controller, "NONE"));
            set(controller, "compact", true);
            selection.restore(initial);
            call(controller, "layoutMainPanel");
            call(controller, "syncFields");
            call(controller, "updateVisibility");
        }
        System.out.println(
                "PASS: export window with default folder, path validation, scale, day/night, own area fill, size limit and responsive layout");
    }
}
