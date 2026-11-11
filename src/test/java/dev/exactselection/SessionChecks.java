package dev.exactselection;

import static dev.exactselection.UiHarness.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.gui.GuiMap;

/** What survives between windows, map openings and releases. */
final class SessionChecks {
    private SessionChecks() {}

    static void checkReleaseFeatures(ExactSelectionController controller, GuiMap map, SavedAreaStore store)
            throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        @SuppressWarnings("unchecked")
        List<GridPoint> draft = (List<GridPoint>) get(polygon(controller), "draft");
        try {
            selection.setGeometry(new RectangleGeometry(new SelectionBounds(80, -120, 240, 80)));
            call(controller, "syncFields");
            call(controller, "updateVisibility");
            require(((Button) get(controller, "quickSave")).visible, "Ready selection has no quick save");
            require(
                    get(get(get(controller, "undo"), "tooltip"), "tooltip") != null
                            && get(get(get(controller, "redo"), "tooltip"), "tooltip") != null,
                    "History arrows need tooltips even with no history");
            press(controller, "quickSave");
            require(
                    window(controller).equals("AREA_NAME") && get(controller, "newAreaGroupId") == null,
                    "Quick save did not start with no group");
            checkLayout(controller);
            var groups = List.of(new SavedAreaStore.Group("release-group", "Release group", true));
            store.replace(new SavedAreaStore.State(saved.areas(), groups, true, true));
            press(controller, "newAreaGroup");
            require(
                    get(controller, "newAreaGroupId").equals("release-group"),
                    "New-area group button did not choose the group");
            press(controller, "newAreaOpacityButton");
            require((Integer) get(controller, "newAreaOpacity") == 50, "New-area fill did not advance from 25% to 50%");
            int[] nameRect = dialogRect(controller);
            int sample = (Integer) call(controller, "colorSampleWidth", nameRect[2]);
            EditBox nameField = ((EditBox[]) get(controller, "dialogFields"))[8];
            EditBox hexField = ((EditBox[]) get(controller, "dialogFields"))[10];
            int first = (Integer) call(controller, "swatchX", nameRect[0], nameRect[2], 0);
            int last =
                    (Integer) call(controller, "swatchX", nameRect[0], nameRect[2], AreaPalette.COLUMNS - 1) + sample;
            int fieldEnd = nameField.getX() + nameField.getWidth();
            require(
                    first >= nameField.getX()
                            && last <= fieldEnd
                            && Math.abs((first - nameField.getX()) - (fieldEnd - last)) <= 1
                            && fieldEnd - last < AreaPalette.COLUMNS,
                    "New-area palette is not lined up with the name field");
            require(
                    hexField.visible
                            && hexField.getY() >= nameRect[1] + 98 + 3 * 22 - 4
                            && hexField.getX() + hexField.getWidth() < last - sample,
                    "Custom color field is missing or covers the preview");
            // Second row, second swatch: the palette sits below the group and fill buttons.
            controller.mouseClicked(
                    left((Integer) call(controller, "swatchX", nameRect[0], nameRect[2], 1) + 2, nameRect[1] + 122, 0),
                    false);
            require(
                    (Integer) get(controller, "newAreaColor") == AreaPalette.COLORS[9]
                            && window(controller).equals("AREA_NAME")
                            && hexField.getValue().equals(AreaPalette.format(AreaPalette.COLORS[9])),
                    "New-area palette did not pick the clicked color");
            hexField.setValue("#12345");
            require(
                    (Integer) get(controller, "newAreaColor") == AreaPalette.COLORS[9],
                    "Incomplete custom color was taken");
            hexField.setValue("#123456");
            require((Integer) get(controller, "newAreaColor") == 0x123456, "Typed custom color was not taken");
            hexField.setValue(AreaPalette.format(AreaPalette.COLORS[9]));
            SelectionState.Snapshot beforeSave = selection.snapshot();
            int countBefore = store.state().areas().size();
            enter(controller, map, 8, "Quick saved");
            SavedAreaStore.Area added = store.state().areas().getLast();
            require(
                    window(controller).equals("NONE")
                            && added.groupId().equals("release-group")
                            && selection.source() == null,
                    "Quick save lost the group or enabled editing implicitly");
            require(
                    added.color() == AreaPalette.COLORS[9] && added.opacity() == 50,
                    "Saved area ignored the chosen color or fill");
            require(
                    selection.phase() == SelectionState.Phase.READY && selection.bounds() == null,
                    "Selection was not cleared after saving the area");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(
                    selection.snapshot().equals(beforeSave)
                            && store.state().areas().size() == countBefore + 1,
                    "First undo after saving should only bring the selection back");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(store.state().areas().size() == countBefore, "Second undo did not remove the saved area");
            key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
            key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
            require(
                    store.state().areas().getLast().equals(added) && selection.bounds() == null,
                    "Redo did not restore the save and clear");
            press(controller, "areasButton");
            press(controller, "areaSelectAll");
            press(controller, "areaColor");
            checkLayout(controller);
            enter(controller, map, 10, "invalid");
            require(
                    window(controller).equals("COLOR")
                            && get(controller, "message").toString().contains("color_invalid"),
                    "Invalid RGB color was accepted");
            int[] rect = dialogRect(controller);
            controller.mouseClicked(left(rect[0] + 14, rect[1] + 36, 0), false);
            require(
                    window(controller).equals("AREAS")
                            && store.state().areas().stream().allMatch(a -> a.color() == AreaPalette.COLORS[0]),
                    "Palette did not recolor every selected area");
            // Select all makes the last area primary; it was saved at 50%, so every checked area moves to 75%.
            press(controller, "areaOpacity");
            require(
                    store.state().areas().stream()
                                            .map(SavedAreaStore.Area::opacity)
                                            .distinct()
                                            .count()
                                    == 1
                            && store.state().areas().get(0).opacity() == 75,
                    "Opacity did not change every selected area");
            press(controller, "areaColor");
            enter(controller, map, 10, "#abc");
            require(
                    store.state().areas().stream().allMatch(a -> a.color() == 0xAABBCC),
                    "Custom RGB did not apply to all selected areas");
            row(controller, store.state().areas().size() - 1, false);
            press(controller, "editShapeButton");
            require(
                    selection.source().equals(added.id()) && !((Button) get(controller, "quickSave")).active,
                    "Edit did not link the saved area or unchanged geometry can be applied");
            require(
                    ((Button) get(controller, "quickSave"))
                            .getMessage()
                            .getString()
                            .equals(Component.translatable("exactselection.quick_apply")
                                    .getString()),
                    "Editing button is not labelled Apply");
            clickMap(controller, 400, 60, GLFW.GLFW_MOD_CONTROL);
            require(selection.geometry() instanceof RectangleGeometry, "Stationary Ctrl click converted a rectangle");
            clickMap(controller, 480, 60, 0);
            require(
                    selection.geometry() instanceof RectangleGeometry
                            && selection.phase() == SelectionState.Phase.SELECTED,
                    "First edge click destroyed the editing selection");
            controller.mouseClicked(left(480, 60, 0), true);
            controller.mouseReleased(left(480, 60, 0));
            require(
                    selection.geometry() instanceof PolygonGeometry
                            && selection.geometry().vertices().size() == 5
                            && selection.source().equals(added.id()),
                    "Rectangle side insertion did not retain its editing source");
            press(controller, "quickSave");
            require(
                    store.state().areas().getLast().geometry().equals(selection.geometry()),
                    "Apply did not save the linked area");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(
                    store.state().areas().getLast().geometry() instanceof RectangleGeometry,
                    "Apply undo lost the original rectangle");
            doubleClickMap(controller, 480, 60, GLFW.GLFW_MOD_CONTROL);
            require(selection.geometry().vertices().size() == 4, "Ctrl double click did not delete a polygon vertex");
            doubleClickMap(controller, 400, 60, GLFW.GLFW_MOD_SUPER);
            require(selection.geometry().vertices().size() == 3, "Cmd double click did not delete a polygon vertex");
            AreaGeometry triangle = selection.geometry();
            doubleClickMap(controller, 561, 60, GLFW.GLFW_MOD_CONTROL);
            require(selection.geometry().equals(triangle), "Deleting a triangle vertex was allowed");
            call(controller, "reset");
            press(controller, "polygonModeButton");
            clickMap(controller, 400, 60, 0);
            clickMap(controller, 560, 60, 0);
            clickMap(controller, 560, 260, 0);
            doubleClickMap(controller, 560, 60, GLFW.GLFW_MOD_CONTROL);
            require(draft.size() == 2, "Ctrl double click did not delete a draft vertex");
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(draft.size() == 3, "Draft vertex deletion cannot be undone");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            selection.set(new SelectionBounds(80, -120, 240, 80));
            call(controller, "updateVisibility");
            controller.mouseClicked(left(400, 60, GLFW.GLFW_MOD_CONTROL), false);
            controller.mouseReleased(left(410, 50, GLFW.GLFW_MOD_CONTROL));
            require(
                    selection.geometry() instanceof PolygonGeometry
                            && selection.geometry().vertices().get(0).equals(new GridPoint(90, -130)),
                    "Ctrl corner drag did not convert the rectangle");
            for (int[] size : List.of(new int[] {320, 240}, new int[] {480, 270}, new int[] {960, 540})) {
                map.width = size[0];
                map.height = size[1];
                controller.init();
                press(controller, "areasButton");
                press(controller, "areaSelectAll");
                press(controller, "areaColor");
                checkLayout(controller);
                key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
                require(!window(controller).equals("COLOR"), "Esc did not close the color window");
                press(controller, "saveSelectionButton");
                checkLayout(controller);
                press(controller, "modalCancel");
                press(controller, "managementClose");
            }
            require(
                    new RectangleGeometry(new SelectionBounds(0, 0, 2, 5)).perimeter() == 18,
                    "Incorrect rectangle perimeter");
            System.out.println(
                    "PASS: quick save and groups, linked Apply/undo, bulk palette/hex/opacity, vertex deletion and rectangle conversion");
        } finally {
            store.replace(saved);
            selection.restore(initial);
            draft.clear();
            set(polygon(controller), "mode", false);
            set(controller, "gesture", null);
            set(controller, "areaId", null);
            ((Set<?>) get(controller, "selectedAreaIds")).clear();
            set(controller, "dialog", dialog(controller, "NONE"));
            set(controller, "message", "");
            ((SelectionHistory) get(controller, "history")).clear();
            ((java.util.Deque<?>) get(polygon(controller), "draftUndo")).clear();
            ((java.util.Deque<?>) get(polygon(controller), "draftRedo")).clear();
            map.width = 640;
            map.height = 360;
            controller.init();
        }
    }

    /** At a low frame rate several events fall into one frame: each must find the widgets as the last one left them. */
    static void checkEventsInOneFrame(
            GuiMap map, MapBridge bridge, Minecraft minecraft, Path directory, MapBridge.Context context)
            throws Exception {
        ExactSelectionController tool = toolOn(map, bridge, minecraft, directory, context, true, "one-frame");
        press(tool, "areasButton");
        Button edit = (Button) get(tool, "editShapeButton"), close = (Button) get(tool, "managementClose");
        require(!edit.active && close.visible, "The Areas window did not open with nothing checked");
        int[] window = dialogRect(tool);
        tool.mouseClicked(left(window[0] + 54, window[1] + 54, 0), false);
        require(edit.active, "A click left the buttons out of date for the next event of its frame");
        key(tool, GLFW.GLFW_KEY_ESCAPE, 0);
        require(!close.visible, "A key left the buttons out of date for the next event of its frame");
        press(tool, "areasButton");
        tool.focus(edit);
        set(tool, "dialog", dialog(tool, "NONE"));
        tool.updateVisibility();
        require(map.getFocused() == null, "The focus stayed on a widget that went away");
        System.out.println("PASS: events within one frame see the widgets the previous event left");
    }

    static void checkModesAcrossWindows(
            GuiMap map, MapBridge bridge, Minecraft minecraft, Unsafe unsafe, Path directory, MapBridge.Context context)
            throws Exception {
        Object children = get(map, "children");
        int left = GLFW.GLFW_MOUSE_BUTTON_LEFT, right = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        try {
            // Saved areas show with the tool on even when the Map switch is off, and only then.
            ExactSelectionController hidden = toolOn(map, bridge, minecraft, directory, context, false, "map-off");
            for (boolean on : List.of(true, false)) {
                if (!on) call(hidden, "toggle");
                RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
                graphics.events = new ArrayList<>();
                graphics.transform = new Matrix3x2fStack(16);
                graphics.state = new GuiRenderState();
                hidden.renderMapOverlays(graphics, 600, 330);
                require(
                        graphics.events.stream().anyMatch(event -> "Saved".equals(event.text())) == on,
                        "Saved areas with the Map switch off: tool on=" + on);
            }

            // Editing an area during a two-click selection ends that selection, so nothing stays blocked.
            ExactSelectionController editing = toolOn(map, bridge, minecraft, directory, context, true, "preview");
            mapClick(editing, 100, 300, left);
            require(
                    ((SelectionState) get(editing, "state")).phase() == SelectionState.Phase.PREVIEW,
                    "First click did not start a selection");
            call(editing, "updateVisibility");
            press(editing, "areasButton");
            row(editing, 0, false);
            press(editing, "editShapeButton");
            call(editing, "updateVisibility");
            require(get(editing, "gesture") == null, "Editing an area left the two-click selection open");
            require(
                    (boolean) call(editing, "canExportSelection")
                            && ((Button) get(editing, "selectionExportButton")) != null,
                    "Export stays blocked after editing");
            mapClick(editing, 340, 200, right);
            require((boolean) get(editing, "quickMenu"), "The right-click menu stays blocked after editing");
            call(editing, "undo");
            require(
                    ((SelectionState) get(editing, "state")).phase() == SelectionState.Phase.READY,
                    "Undo returned to the abandoned preview");

            // Undo from a window can replace the preview as well.
            ExactSelectionController undone = toolOn(map, bridge, minecraft, directory, context, true, "preview-undo");
            mapClick(undone, 100, 300, left);
            mapClick(undone, 120, 320, left);
            mapClick(undone, 500, 320, left);
            require(get(undone, "gesture") instanceof MapGesture.Corners, "Third click did not start a new selection");
            call(undone, "updateVisibility");
            press(undone, "areasButton");
            press(undone, "managementUndo");
            require(get(undone, "gesture") == null, "Undo left the replaced two-click selection open");

            // Editing an area leaves the ruler, whose clicks would otherwise not reach the area.
            ExactSelectionController ruler = toolOn(map, bridge, minecraft, directory, context, true, "ruler");
            press(ruler, "rulerButton");
            press(ruler, "areasButton");
            row(ruler, 0, false);
            press(ruler, "editShapeButton");
            require(!(boolean) get(ruler(ruler), "mode"), "Editing an area left the ruler on");

            // Typed coordinates survive collapsing the panel; a map click they hold back says why, and Esc releases it.
            ExactSelectionController typed = toolOn(map, bridge, minecraft, directory, context, true, "typed");
            set(typed, "compact", false);
            call(typed, "layoutMainPanel");
            call(typed, "updateVisibility");
            ((EditBox[]) get(typed, "fields"))[0].setValue("5");
            require((boolean) get(typed, "dirty"), "Typing did not mark the fields");
            press(typed, "panelModeButton");
            call(typed, "updateVisibility");
            require(
                    (boolean) get(typed, "dirty") && "5".equals(((EditBox[]) get(typed, "fields"))[0].getValue()),
                    "Collapsing lost the typed coordinates");
            mapClick(typed, 100, 300, left);
            require(
                    ((String) get(typed, "message")).contains("coordinates_pending"),
                    "A held-back map click does not say why");
            require(
                    ((SelectionState) get(typed, "state")).phase() == SelectionState.Phase.READY,
                    "A map click ignored typed coordinates");
            key(typed, GLFW.GLFW_KEY_ESCAPE, 0);
            mapClick(typed, 100, 300, left);
            require(
                    ((SelectionState) get(typed, "state")).phase() == SelectionState.Phase.PREVIEW,
                    "Esc did not release the map");

            // Redo after turning the tool off and back on clears the selection and keeps the tool on.
            ExactSelectionController reopened = toolOn(map, bridge, minecraft, directory, context, true, "redo");
            SelectionState reopenedState = (SelectionState) get(reopened, "state");
            SelectionBounds kept = new SelectionBounds(40, 40, 50, 50);
            reopenedState.set(kept);
            call(reopened, "toggle");
            call(reopened, "toggle");
            call(reopened, "undo");
            require(kept.equals(reopenedState.bounds()), "Undo did not restore the selection of the tool turned off");
            call(reopened, "redo");
            require(reopened.enabled() && reopenedState.bounds() == null, "Redo turned the tool off");

            // Waypoint filter labels survive the widgets being rebuilt on a resize.
            ExactSelectionController resized = toolOn(map, bridge, minecraft, directory, context, true, "filters");
            call(resized, "cycleVisibilityFilter");
            call(resized, "cycleColorFilter");
            set(map, "children", new ArrayList<>());
            resized.init();
            require(
                    ((Button) get(resized, "pointVisibility"))
                            .getMessage()
                            .getString()
                            .contains("filter_visible"),
                    "The visibility filter label reset on a resize");
            require(
                    ((Button) get(resized, "pointColor"))
                                    .getMessage()
                                    .getString()
                                    .contains("filter_color")
                            && !((Button) get(resized, "pointColor"))
                                    .getMessage()
                                    .getString()
                                    .contains("filter_color_all"),
                    "The color filter label reset on a resize");

            // The Center and Color windows close with a button, not only with Esc.
            ExactSelectionController closing = toolOn(map, bridge, minecraft, directory, context, true, "closing");
            ((SelectionState) get(closing, "state")).set(new SelectionBounds(40, 40, 50, 50));
            Object center = dialog(closing, "CENTER");
            Method open = declared("openDialog", center.getClass());
            open.invoke(receiver(closing, open), center);
            closing.updateVisibility();
            press(closing, "modalCancel");
            require(window(closing).equals("NONE"), "The Center window did not close with its button");
            press(closing, "areasButton");
            row(closing, 0, false);
            press(closing, "areaColor");
            require(window(closing).equals("COLOR"), "The color window did not open");
            press(closing, "modalCancel");
            require(window(closing).equals("AREAS"), "The color window did not close with its button");
            press(closing, "managementClose");

            // Switching units leaves an open saved area as it is, and still snaps a new selection.
            ExactSelectionController units = toolOn(map, bridge, minecraft, directory, context, true, "units");
            SelectionState unitsState = (SelectionState) get(units, "state");
            press(units, "areasButton");
            row(units, 0, false);
            press(units, "editShapeButton");
            SelectionBounds loaded = unitsState.bounds();
            call(units, "toggleChunks");
            require(loaded.equals(unitsState.bounds()), "Switching to chunks changed the open saved area");
            call(units, "toggleChunks");
            call(units, "reset");
            unitsState.set(new SelectionBounds(1, 1, 5, 5));
            call(units, "toggleChunks");
            require(
                    new SelectionBounds(0, 0, 15, 15).equals(unitsState.bounds()),
                    "Switching to chunks did not snap a new selection");

            // Importing a rectangle drawn right to left, in the session that drew it, finds the original.
            var drawn = SavedAreaStore.create(
                    "Drawn", new RectangleGeometry(new SelectionBounds(10, 10, 0, 0)), 0x123456, 25, null);
            var current = new SavedAreaStore.State(List.of(drawn), List.of());
            Path file = AreaTransfer.export(
                    Files.createDirectories(directory.resolve("transfer")), "drawn", List.of(drawn), List.of());
            var merge = AreaTransfer.merge(current, AreaTransfer.read(file));
            require(
                    merge.added() == 0 && merge.skipped() == 1,
                    "The import duplicated a rectangle drawn right to left");
            var polygon = SavedAreaStore.create(
                    "Drawn",
                    new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(11, 0), new GridPoint(11, 11))),
                    0x123456,
                    25,
                    null);
            require(
                    AreaTransfer.merge(new SavedAreaStore.State(List.of(polygon), List.of()), AreaTransfer.read(file))
                                    .added()
                            == 1,
                    "A polygon with the same bounds counted as the same area");
        } finally {
            set(map, "children", children);
            map.setFocused(null);
        }
        System.out.println(
                "PASS: saved areas with the tool on, editing during a selection or the ruler, collapsed typed fields, redo after turning off, filter labels, closing buttons, units on an open area, import of a drawn rectangle");
    }

    static void checkKeepingWork(
            GuiMap map, MapBridge bridge, Minecraft minecraft, Unsafe unsafe, Path directory, MapBridge.Context context)
            throws Exception {
        Object children = get(map, "children");
        int left = GLFW.GLFW_MOUSE_BUTTON_LEFT, ctrl = GLFW.GLFW_MOD_CONTROL;
        try {
            // However a contour is dropped, undo brings it back with its own steps, on the tool.
            for (int way = 0; way < 4; way++) {
                ExactSelectionController contour =
                        toolOn(map, bridge, minecraft, directory, context, true, "contour-" + way);
                press(contour, "polygonModeButton");
                drawContour(contour);
                switch (way) {
                    case 0 -> key(contour, GLFW.GLFW_KEY_ESCAPE, 0);
                    case 1 -> press(contour, "polygonModeButton");
                    case 2 -> call(contour, "reset");
                    default -> {
                        contour.disable();
                        call(contour, "toggle");
                    }
                }
                require(draft(contour).isEmpty(), "The contour was not dropped, way " + way);
                key(contour, GLFW.GLFW_KEY_Z, ctrl);
                require(
                        draft(contour).size() == 5 && (boolean) get(polygon(contour), "mode"),
                        "Undo did not bring the contour back, way " + way);
                key(contour, GLFW.GLFW_KEY_Z, ctrl);
                require(draft(contour).size() == 4, "The contour came back without its own undo steps, way " + way);
            }

            // Closing the map keeps the tool, the selection and the history; a held drag ends.
            RecordingBridge screen = new RecordingBridge(bridge);
            ExactSelectionController kept = toolOn(map, screen, minecraft, directory, context, true, "reopen");
            SelectionState keptState = (SelectionState) get(kept, "state");
            SavedAreaStore keptStore = (SavedAreaStore) get(kept, "areaStore");
            mapClick(kept, 100, 300, left);
            mapClick(kept, 150, 340, left);
            SelectionBounds bounds = keptState.bounds();
            kept.mouseClicked(new MouseButtonEvent(120, 320, new MouseButtonInfo(left, 0)), false);
            kept.beforeRender(140, 330);
            require(get(kept, "gesture") != null && !bounds.equals(keptState.bounds()), "The drag did not start");
            AutosaveSettings.load(keptStore.root()).setEnabled(true);
            SavedAreaStore outside = new SavedAreaStore(keptStore.root());
            outside.useContext(context);
            outside.replace(new SavedAreaStore.State(List.of(), List.of(), true, true));
            screen.noFrame = true;
            reopen(map, kept, screen);
            require(
                    get(kept, "gesture") == null && bounds.equals(keptState.bounds()),
                    "Closing the map did not end the drag");
            kept.beforeRender(5, 5);
            require(
                    kept.enabled() && bounds.equals(keptState.bounds()),
                    "A new screen's first frame turned the tool off");
            screen.noFrame = false;
            kept.beforeRender(5, 5);
            require(
                    kept.enabled()
                            && bounds.equals(keptState.bounds())
                            && ((SelectionHistory) get(kept, "history")).canUndo(),
                    "Reopening the map lost the selection or its history");
            require(
                    ((AutosaveSettings) get(kept, "autosave")).enabled(),
                    "Reopening the map did not read the changed settings");
            require(keptStore.state().areas().isEmpty(), "Reopening the map did not read the changed area file");
            screen.noFrame = true;
            kept.beforeRender(5, 5);
            require(!kept.enabled(), "A map lost after its first frame kept the tool on");

            // The first area of a world stays visible with the tool off.
            ExactSelectionController first = toolOn(map, bridge, minecraft, directory, context, false, "first-area");
            SavedAreaStore firstStore = (SavedAreaStore) get(first, "areaStore");
            firstStore.replace(SavedAreaStore.State.empty());
            ((SelectionState) get(first, "state")).set(new SelectionBounds(0, 0, 99, 99));
            call(first, "updateVisibility");
            call(first, "quickSave");
            text(first, 8).setValue("First");
            press(first, "modalApply");
            require(firstStore.state().showOnMap(), "The first area did not switch the map overlay on");
            call(first, "toggle");
            require(drawsLabel(first, unsafe, "First"), "The first area vanished with the tool off");

            // Edit moves the map to an area out of sight, and leaves the map alone otherwise.
            RecordingBridge camera = new RecordingBridge(bridge);
            ExactSelectionController editing = toolOn(map, camera, minecraft, directory, context, true, "camera");
            SavedAreaStore cameraStore = (SavedAreaStore) get(editing, "areaStore");
            var near = cameraStore.state().areas().get(0);
            var far = SavedAreaStore.create("Far", new SelectionBounds(5000, 5000, 5100, 5100), 0x123456, 25, null);
            cameraStore.replace(new SavedAreaStore.State(List.of(near, far), List.of(), true, true));
            call(editing, "updateVisibility");
            press(editing, "areasButton");
            row(editing, 0, false);
            press(editing, "editShapeButton");
            require(camera.camera == null, "Editing a visible area moved the map");
            press(editing, "areasButton");
            row(editing, 1, false);
            press(editing, "editShapeButton");
            require(
                    camera.camera != null && camera.camera[0] == 5050.5 && camera.camera[1] == 5050.5,
                    "Editing a far area did not move the map to it");

            // Show on the map: the button and a double click on a row, which a click with a modifier is not.
            camera.camera = null;
            press(editing, "areasButton");
            row(editing, 1, false);
            press(editing, "areaShow");
            require(window(editing).equals("NONE") && camera.camera != null, "The show button did not move the map");
            require(
                    Math.abs(camera.camera[2] - 0.8 * 360 / 101) < 1e-9,
                    "Showing an area did not fit it on the screen");
            camera.camera = null;
            press(editing, "areasButton");
            int[] rect = dialogRect(editing);
            MouseButtonEvent plainClick =
                    new MouseButtonEvent(rect[0] + 54, rect[1] + 74, new MouseButtonInfo(left, 0));
            editing.mouseClicked(plainClick, false);
            editing.mouseClicked(
                    new MouseButtonEvent(rect[0] + 54, rect[1] + 74, new MouseButtonInfo(left, GLFW.GLFW_MOD_CONTROL)),
                    false);
            require(
                    window(editing).equals("AREAS") && camera.camera == null,
                    "A click with a modifier counted as a double click");
            editing.mouseClicked(plainClick, false);
            editing.mouseClicked(plainClick, false);
            require(
                    window(editing).equals("NONE") && camera.camera != null,
                    "A double click on a row did not show the area");

            // Undo waits while a window works on the selection it opened with.
            ExactSelectionController modal = toolOn(map, bridge, minecraft, directory, context, true, "modal-undo");
            SelectionState modalState = (SelectionState) get(modal, "state");
            mapClick(modal, 100, 300, left);
            mapClick(modal, 150, 340, left);
            call(modal, "updateVisibility");
            call(modal, "quickSave");
            map.setFocused(null);
            key(modal, GLFW.GLFW_KEY_Z, ctrl);
            require(
                    modalState.phase() == SelectionState.Phase.SELECTED,
                    "Undo changed the selection behind the new area window");
            press(modal, "modalApply");
            require(
                    ((SavedAreaStore) get(modal, "areaStore")).state().areas().size() == 2,
                    "The new area window did not save after an undo key");

            // The limit is named as a limit, and nothing is written.
            ExactSelectionController full = toolOn(map, bridge, minecraft, directory, context, true, "limit");
            SavedAreaStore fullStore = (SavedAreaStore) get(full, "areaStore");
            List<SavedAreaStore.Area> many = new ArrayList<>();
            for (int i = 0; i < 1000; i++)
                many.add(SavedAreaStore.create(
                        "Z" + i, new SelectionBounds(i * 20, 5000, i * 20 + 5, 5005), 0x123456, 25, null));
            fullStore.replace(new SavedAreaStore.State(many, List.of(), true, true));
            ((SelectionState) get(full, "state")).set(new SelectionBounds(40, 40, 50, 50));
            call(full, "updateVisibility");
            call(full, "quickSave");
            press(full, "modalApply");
            require(
                    fullStore.state().areas().size() == 1000
                            && get(full, "message").toString().contains("areas_limit"),
                    "The limit was not named");

            // A damaged area file can be put back from the window.
            ExactSelectionController damaged = toolOn(map, bridge, minecraft, directory, context, true, "restore");
            SavedAreaStore damagedStore = (SavedAreaStore) get(damaged, "areaStore");
            damagedStore.replace(new SavedAreaStore.State(List.of(), List.of(), true, true));
            Files.writeString(areaFile(damagedStore), "{damaged");
            reopen(map, damaged, bridge);
            call(damaged, "checkedFrame");
            require((boolean) get(damaged, "loadFailed"), "The damaged file was read");
            call(damaged, "updateVisibility");
            press(damaged, "areasButton");
            press(damaged, "areaRestore");
            require(
                    !(boolean) get(damaged, "loadFailed")
                            && damagedStore.state().areas().size() == 1,
                    "The damaged file was not put back");
            call(damaged, "cancelDialog");

            // The export button keeps its tooltip through a window resize.
            ExactSelectionController resized = toolOn(map, bridge, minecraft, directory, context, true, "tooltip");
            Method completed = declared("exportCompleted", AreaExportService.Result.class);
            completed.invoke(
                    receiver(resized, completed),
                    new AreaExportService.Result(Files.write(directory.resolve("exported.png"), new byte[] {1}), 1, 1));
            resized.updateVisibility();
            set(map, "children", new ArrayList<>());
            resized.init();
            require(exportTooltipSet(resized), "The export button lost its tooltip on a resize");

            // A default name skips numbers still in use.
            ExactSelectionController naming = toolOn(map, bridge, minecraft, directory, context, true, "names");
            SavedAreaStore namingStore = (SavedAreaStore) get(naming, "areaStore");
            namingStore.replace(new SavedAreaStore.State(
                    List.of(SavedAreaStore.create("Area 2", new SelectionBounds(0, 0, 9, 9), 0x123456, 25, null)),
                    List.of(),
                    true,
                    true));
            ((SelectionState) get(naming, "state")).set(new SelectionBounds(40, 40, 50, 50));
            Language language = Language.getInstance();
            Language.inject(new Language() {
                @Override
                public String getOrDefault(String key, String fallback) {
                    return key.endsWith("area_default_name") ? "Area %s" : fallback;
                }

                @Override
                public boolean has(String key) {
                    return key.endsWith("area_default_name");
                }

                @Override
                public boolean isDefaultRightToLeft() {
                    return false;
                }

                @Override
                public FormattedCharSequence getVisualOrder(FormattedText text) {
                    return FormattedCharSequence.EMPTY;
                }
            });
            try {
                call(naming, "updateVisibility");
                call(naming, "quickSave");
                require(
                        text(naming, 8).getValue().equals("Area 3"),
                        "The default name repeated one in use: "
                                + text(naming, 8).getValue());
            } finally {
                Language.inject(language);
            }

            // Typed coordinates in the expanded panel: a click on the map says why it is held back.
            ExactSelectionController typed = toolOn(map, bridge, minecraft, directory, context, true, "typed-expanded");
            set(typed, "compact", false);
            call(typed, "layoutMainPanel");
            call(typed, "updateVisibility");
            ((EditBox[]) get(typed, "fields"))[0].setValue("5");
            mapClick(typed, 500, 320, left);
            require(
                    get(typed, "message").toString().contains("coordinates_pending_apply"),
                    "A held-back click in the expanded panel says nothing");

            // The map key closes the map with the tool on, unless a text field or a window takes the key.
            RecordingBridge closing = new RecordingBridge(bridge);
            ExactSelectionController closer = toolOn(map, closing, minecraft, directory, context, true, "map-key");
            set(closer, "compact", false);
            call(closer, "layoutMainPanel");
            call(closer, "updateVisibility");
            map.setFocused(((EditBox[]) get(closer, "fields"))[0]);
            key(closer, GLFW.GLFW_KEY_M, 0);
            require(!closing.closed, "The map key closed the map while typing");
            map.setFocused(null);
            press(closer, "areasButton");
            key(closer, GLFW.GLFW_KEY_M, 0);
            require(!closing.closed, "The map key closed the map behind a window");
            call(closer, "cancelDialog");
            key(closer, GLFW.GLFW_KEY_M, 0);
            require(closing.closed, "The map key did not close the map");
        } finally {
            set(map, "children", children);
            map.setFocused(null);
        }
        System.out.println(
                "PASS: dropped contours come back, the map reopens with the tool's work, first area shown, camera, undo behind windows, limits, restore, tooltip, default names, map key");
    }
}
