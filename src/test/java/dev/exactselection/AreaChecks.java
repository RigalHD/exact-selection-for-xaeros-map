package dev.exactselection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.gui.GuiMap;
import static dev.exactselection.UiHarness.*;

/** Saved areas and groups: their windows, unsaved edits, exchange files and what shows on the map. */
final class AreaChecks {
    private AreaChecks() {}

    static void checkZonesOnTheMap(GuiMap map, MapBridge bridge, Minecraft minecraft, Unsafe unsafe,
                                           Path directory, MapBridge.Context context) throws Exception {
        Object children = get(map, "children");
        int right = GLFW.GLFW_MOUSE_BUTTON_RIGHT, left = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        try {
            // Saved: 320..420 x 180..280 on screen; Small inside it: 330..340 x 190..200.
            ExactSelectionController zones = toolOn(map, bridge, minecraft, directory, context, true, "zone-menu");
            SavedAreaStore store = (SavedAreaStore) get(zones, "areaStore");
            SelectionState selection = (SelectionState) get(zones, "state");
            var saved = store.state().areas().get(0);
            var small = SavedAreaStore.create("Small", new SelectionBounds(10, 10, 19, 19), 0x123456, 25, null);
            store.replace(new SavedAreaStore.State(List.of(saved, small), List.of(), true, true));
            call(zones, "updateVisibility");

            // Nothing happens on the empty map; the menu opens on the smallest area under the pointer.
            mapClick(zones, 100, 300, right);
            require(!(boolean) get(zones, "quickMenu"), "The menu opened on the empty map");
            mapClick(zones, 335, 195, right);
            require((boolean) get(zones, "quickMenu"), "The menu did not open on a saved area");
            require(((SavedAreaStore.Area) call(get(zones, "rightClickMenu"), "menuArea")).id().equals(small.id()), "The menu did not take the smallest area");
            press(zones, "quickMenuHide");
            require(!store.state().areas().get(1).visible() && !(boolean) get(zones, "quickMenu"), "Hide did not hide the area");

            // A hidden area no longer catches the pointer; Fill stays in the menu, Color applies at once.
            mapClick(zones, 335, 195, right);
            require(((SavedAreaStore.Area) call(get(zones, "rightClickMenu"), "menuArea")).id().equals(saved.id()), "A hidden area caught the right click");
            // Its details are rows of the menu; a click copies the value and leaves the menu open.
            Object keyboardBefore = get(minecraft, "keyboardHandler");
            ClipboardKeyboard keyboard = (ClipboardKeyboard) unsafe.allocateInstance(ClipboardKeyboard.class);
            set(minecraft, "keyboardHandler", keyboard);
            try {
                require(!((Button) get(zones, "quickInfoGroup")).visible, "An area without a group shows a group row");
                press(zones, "quickInfoName");
                require(saved.name().equals(keyboard.copied) && (boolean) get(zones, "quickMenu"), "The name row did not copy the name: " + keyboard.copied);
                press(zones, "quickInfoSize");
                require(Long.toString((long) saved.geometry().area()).equals(keyboard.copied), "The size row did not copy the area: " + keyboard.copied);
            } finally { set(minecraft, "keyboardHandler", keyboardBefore); }
            press(zones, "quickMenuOpacity");
            require(store.state().areas().get(0).opacity() == 50 && (boolean) get(zones, "quickMenu"), "Fill did not change the area");
            press(zones, "quickMenuColor");
            text(zones, ExactSelectionController.HEX_FIELD).setValue("#FF0000");
            press(zones, "colorHexApply");
            require(store.state().areas().get(0).color() == 0xFF0000 && window(zones).equals("NONE"), "Color did not change the area");

            // Edit opens the area; a right click on it then acts on the selection, as before.
            mapClick(zones, 340, 220, right);
            press(zones, "quickMenuEdit");
            require(saved.id().equals(selection.source()), "Edit did not open the area");
            selection.reset();
            call(zones, "updateVisibility");

            // Delete asks first and leaves an unrelated selection alone.
            SelectionBounds other = new SelectionBounds(-200, -100, -190, -90);
            selection.set(other);
            call(zones, "updateVisibility");
            mapClick(zones, 340, 220, right);
            press(zones, "quickMenuDelete");
            require(store.state().areas().size() == 2 && window(zones).equals("AREAS"), "Delete did not ask first");
            press(zones, "confirm");
            require(store.state().areas().size() == 1 && other.equals(selection.bounds()) && window(zones).equals("NONE"),
                    "Deleting an area from the map lost the selection");

            // Its details are for the right-click menu; hovering, with the tool on or off, draws nothing extra.
            ExactSelectionController hover = toolOn(map, bridge, minecraft, directory, context, true, "hover");
            require(!drawsLabel(hover, unsafe, "exactselection.area", 340, 220), "Hovering a saved area showed its details");
            call(hover, "toggle");
            require(!drawsLabel(hover, unsafe, "exactselection.area", 340, 220), "Hovering with the tool off showed its details");
        } finally {
            set(map, "children", children);
            map.setFocused(null);
        }
        System.out.println("PASS: right-click menu on saved areas, smallest area first, hide, fill, color, edit, delete; hover label");
    }

    static void checkAreaDraftAndAutosave(ExactSelectionController controller, GuiMap map, SavedAreaStore store,
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
                    // Vertex colors; the outline's smoothed fringe fades to transparent and is left out.
                    Method vertices = element.getClass().getDeclaredMethod("vertices");
                    vertices.setAccessible(true);
                    for (Object vertex : (List<?>) vertices.invoke(element)) {
                        Method color = vertex.getClass().getDeclaredMethod("color");
                        color.setAccessible(true);
                        int value = (Integer) color.invoke(vertex);
                        if (value >>> 24 != 0) colors.add(value);
                    }
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
            // Deleting the area being edited leaves its shape on the map as a new selection, not as an edit of nothing.
            SelectionState edited = (SelectionState) get(controller, "state");
            SelectionState.Snapshot selectionBefore = edited.snapshot();
            SavedAreaStore.Area last = store.state().areas().get(0);
            edited.load(last.geometry(), last.id());
            pick(controller, store, last.id());
            press(controller, "areaDelete"); press(controller, "confirm");
            require(store.state().areas().isEmpty() && edited.source() == null && edited.geometry() != null,
                    "Deleting the area being edited left the selection editing it");
            press(controller, "managementUndo");
            require(store.state().areas().size() == 1, "Undo did not bring the deleted area back");
            edited.restore(selectionBefore);
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

    static void checkLayersAndOverlap(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        // Exact shares, also of two separate pieces and far from the origin, where a plain shoelace sum loses precision.
        AreaGeometry square = new RectangleGeometry(new SelectionBounds(0, 0, 9, 9));
        require(square.sharedArea(new RectangleGeometry(new SelectionBounds(5, 5, 14, 14))) == 25, "Overlap of two rectangles is wrong");
        require(square.sharedArea(new RectangleGeometry(new SelectionBounds(10, 0, 19, 9))) == 0, "Rectangles touching along an edge share an area");
        require(square.sharedArea(new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(10, 0), new GridPoint(0, 10)))) == 50, "Overlap with a triangle is wrong");
        AreaGeometry hook = new PolygonGeometry(List.of(new GridPoint(0, 0), new GridPoint(10, 0), new GridPoint(10, 2), new GridPoint(2, 2),
                new GridPoint(2, 8), new GridPoint(10, 8), new GridPoint(10, 10), new GridPoint(0, 10)));
        require(hook.sharedArea(new RectangleGeometry(new SelectionBounds(5, 0, 9, 9))) == 20, "Overlap in two pieces is wrong");
        require(hook.translated(29_000_000, 29_000_000).sharedArea(new RectangleGeometry(new SelectionBounds(29_000_005, 29_000_000, 29_000_009, 29_000_009))) == 20,
                "Overlap far from the origin is wrong");
        require(square.sharedArea(new PolylineGeometry(List.of(new GridPoint(0, 0), new GridPoint(9, 9)))) == 0, "A route shares an area");

        SavedAreaStore.State saved = store.state();
        var low = SavedAreaStore.create("Low", new SelectionBounds(0, 0, 9, 9), 0x111111, 25, null);
        var far = SavedAreaStore.create("Far", new SelectionBounds(500, 500, 509, 509), 0x222222, 25, null);
        var mid = SavedAreaStore.create("Mid", new SelectionBounds(5, 5, 14, 14), 0x333333, 25, null);
        var top = SavedAreaStore.create("Top", new SelectionBounds(8, 8, 12, 12), 0x444444, 25, null);
        java.util.function.Supplier<List<String>> order = () -> store.state().areas().stream().map(SavedAreaStore.Area::name).toList();
        try {
            store.replace(new SavedAreaStore.State(List.of(low, far, mid, top), List.of(), true, true));
            require(controller.areaEdits.nextLayer(top, 1) < 0 && controller.areaEdits.nextLayer(low, -1) < 0, "The top and bottom areas can still move on");
            // One step passes the next area it overlaps, not one elsewhere on the map.
            controller.areaEdits.moveLayer(low, 1);
            require(order.get().equals(List.of("Far", "Mid", "Low", "Top")), "Move up did not pass the overlapping area: " + order.get());
            controller.undo();
            require(order.get().equals(List.of("Low", "Far", "Mid", "Top")), "Undo did not restore the order: " + order.get());
            controller.redo();
            controller.areaEdits.moveLayer(top, -1);
            require(order.get().equals(List.of("Far", "Mid", "Top", "Low")), "Move down did not pass the overlapping area: " + order.get());
            // A hidden area is passed over: moving past it would show no change.
            store.replace(new SavedAreaStore.State(List.of(low, mid.withVisible(false), top), List.of(), true, true));
            controller.areaEdits.moveLayer(low, 1);
            require(order.get().equals(List.of("Mid", "Top", "Low")), "Move up stopped at a hidden area: " + order.get());

            store.replace(new SavedAreaStore.State(List.of(low, far, mid), List.of(), true, true));
            press(controller, "areasButton");
            controller.areasWindow.selectedAreaIds.clear();
            controller.areasWindow.selectedAreaIds.addAll(List.of(low.id(), mid.id()));
            controller.updateVisibility();
            require(((net.minecraft.client.gui.components.Button) get(controller, "overlapCopy")).visible, "Two overlapping areas show no overlap");
            controller.areasWindow.selectedAreaIds.clear();
            controller.areasWindow.selectedAreaIds.addAll(List.of(low.id(), far.id()));
            controller.updateVisibility();
            require(!((net.minecraft.client.gui.components.Button) get(controller, "overlapCopy")).visible, "Areas that do not overlap offer to copy an overlap");
            controller.areasWindow.selectedAreaIds.clear();
            press(controller, "managementClose");
        } finally { store.replace(saved); }
    }

    static void checkAreaListAndTransfer(ExactSelectionController controller, SavedAreaStore store, Path directory) throws Exception {
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
            require(get(controller, "areaSort").toString().equals("LAYER"), "Sort did not cycle back to the layers");
            // Search by name, case-insensitive; All checks only what the search shows.
            EditBox search = (EditBox) get(controller, "areaSearch");
            require(search.visible, "The Areas window has no search field");
            search.setValue("RAV");
            require(((List<?>) call(controller, "displayedAreas")).stream().map(area -> ((SavedAreaStore.Area) area).name()).toList()
                    .equals(List.of("Bravo")), "Search did not find Bravo by part of its name");
            press(controller, "areaClearSelection");
            press(controller, "areaSelectAll");
            require(checkedNames(controller, store).equals(Set.of("Bravo")), "All checked areas the search hides");
            call(controller, "updateVisibility");
            require(!((Button) get(controller, "areaSelectAll")).active, "All stays active with every found area checked");
            search.setValue(""); call(controller, "updateVisibility");
            require(((Button) get(controller, "areaSelectAll")).active, "All is unavailable with unchecked areas shown");
            search.setValue("RAV");
            search.setValue("nothing like it");
            require(((List<?>) call(controller, "displayedAreas")).isEmpty(), "Search kept areas that do not match");
            search.setValue("");
            require(((List<?>) call(controller, "displayedAreas")).size() == 4, "An empty search hid areas");
            checkLayout(controller);
            require(((Button[]) get(controller, "areaCopies")).length == 9, "Area copy buttons lack a name or one of the four corners");

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

    /** Groups in their own window and from the Areas window: creating, assigning, hiding, undo and the layouts. */
    static void checkGroups(ExactSelectionController controller, GuiMap map, SavedAreaStore store, SavedAreaStore minimapStore,
                            SelectionState selection, Path directory, MapBridge.Context context) throws Exception {
        controller.init();
        // Groups left the panel; they open from the Areas window (checked there), here directly.
        Object none = dialog(controller, "NONE");
        Method openGroups = declared("openGroupsWindow", none.getClass());
        openGroups.setAccessible(true);
        openGroups.invoke(receiver(controller, openGroups), none);
        controller.updateVisibility();
        call(controller, "layoutDialog");
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
        set(polygon(controller), "mode", true);
        List<?> draft = (List<?>) get(polygon(controller), "draft");
        List.class.getMethod("add", Object.class).invoke(draft, new GridPoint(70, 70));
        press(controller, "managementUndo");
        require(draft.size() == 1, "Management undo removed a polygon vertex instead of undoing the area operation");
        set(polygon(controller), "mode", false); draft.clear();
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
    }
}
