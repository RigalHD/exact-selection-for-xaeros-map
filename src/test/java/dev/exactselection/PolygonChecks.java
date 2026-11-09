package dev.exactselection;

import static dev.exactselection.UiHarness.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.gui.GuiMap;

/** Contours and polygons: drawing, editing vertices and the labels over them. */
final class PolygonChecks {
    private PolygonChecks() {}

    static void checkPolygonEditing(
            ExactSelectionController controller,
            GuiMap map,
            SavedAreaStore store,
            Path directory,
            MapBridge.Context context)
            throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        selection.reset();
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        clickMap(controller, 390, 50, 0);
        require(selection.phase() == SelectionState.Phase.PREVIEW, "Rectangle preview did not start");
        press(controller, "polygonModeButton");
        require(
                selection.phase() == SelectionState.Phase.READY && get(controller, "gesture") == null,
                "Entering polygon mode kept a stale rectangle gesture");
        clickMap(controller, 400, 60, 0);
        clickMap(controller, 560, 60, 0);
        clickMap(controller, 560, 260, 0);
        clickMap(controller, 400, 260, 0);
        @SuppressWarnings("unchecked")
        List<GridPoint> draft = (List<GridPoint>) get(polygon(controller), "draft");
        require(draft.size() == 4 && selection.geometry() == null, "Draft became a complete area too early");
        require(
                !((Button) get(controller, "areasButton")).active
                        && !((Button) get(controller, "selectionExportButton")).active,
                "Unfinished contour can operate on an old saved/exported selection");
        List<GridPoint> original = List.copyOf(draft);
        controller.mouseClicked(left(400, 60, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(420, 80);
        require(
                draft.size() == 4 && draft.get(0).equals(new GridPoint(100, -100)),
                "Ctrl did not drag the draft vertex");
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
        require(
                draft.equals(beforeCancel) && (Boolean) get(polygon(controller), "mode"),
                "Esc cancelled the entire path instead of just the vertex drag");
        key(controller, GLFW.GLFW_KEY_BACKSPACE, 0);
        require(draft.size() == 3, "Backspace did not remove the last vertex");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(draft.equals(beforeCancel), "Undo did not restore the removed vertex");
        clickMap(controller, 620, 320, GLFW.GLFW_MOD_CONTROL);
        require(
                draft.size() == 5 && draft.getLast().equals(new GridPoint(300, 140)),
                "Ctrl click away from a vertex added no point");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        // As on a finished shape: a click on a side only shows the controls, a double click adds a vertex on it.
        clickMap(controller, 480, 260, 0);
        require(draft.equals(beforeCancel), "A click on a contour side added a vertex");
        controller.mouseClicked(left(480, 260, 0), true);
        controller.mouseReleased(left(480, 260, 0));
        require(
                draft.size() == 5 && draft.get(3).equals(new GridPoint(160, 80)),
                "A double click on a contour side added no vertex on it: " + draft);
        clickMap(controller, 480, 260, GLFW.GLFW_MOD_SUPER);
        controller.mouseClicked(left(480, 260, GLFW.GLFW_MOD_SUPER), true);
        controller.mouseReleased(left(480, 260, GLFW.GLFW_MOD_SUPER));
        require(draft.equals(beforeCancel), "Cmd+double click did not delete the contour vertex: " + draft);
        // A double click Minecraft did not report (a slow frame between the presses) still deletes the vertex.
        clickMap(controller, 480, 260, 0);
        controller.mouseClicked(left(480, 260, 0), true);
        controller.mouseReleased(left(480, 260, 0));
        clickMap(controller, 480, 260, GLFW.GLFW_MOD_SUPER);
        clickMap(controller, 480, 260, GLFW.GLFW_MOD_SUPER);
        require(draft.equals(beforeCancel), "Two quick Cmd clicks on a contour vertex did not delete it: " + draft);
        clickMap(controller, 480, 260, 0);
        clickMap(controller, 480, 260, 0);
        require(
                draft.size() == 5 && draft.get(3).equals(new GridPoint(160, 80)),
                "Two quick clicks on a contour side added no vertex: " + draft);
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        // The left button alone drags a vertex; a click on one without movement leaves it.
        controller.mouseClicked(left(560, 260, 0), false);
        controller.beforeRender(570, 270);
        controller.mouseReleased(left(570, 270, 0));
        require(
                draft.size() == 4 && draft.get(2).equals(new GridPoint(250, 90)),
                "A plain drag did not move the contour vertex: " + draft);
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        // Shift lines a dragged contour vertex up with a neighbour.
        GridPoint before2 = draft.get(1), after2 = draft.get(3);
        controller.mouseClicked(left(560, 260, GLFW.GLFW_MOD_SHIFT), false);
        controller.beforeRender(570, 270);
        controller.mouseReleased(left(570, 270, GLFW.GLFW_MOD_SHIFT));
        GridPoint aligned = draft.get(2);
        require(
                aligned.x() == before2.x()
                        || aligned.z() == before2.z()
                        || aligned.x() == after2.x()
                        || aligned.z() == after2.z(),
                "Shift did not align the contour vertex: " + draft);
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        controller.mouseClicked(left(560, 260, 0), false);
        controller.beforeRender(561, 261);
        controller.mouseReleased(left(561, 261, 0));
        require(draft.equals(beforeCancel), "A jittery click on a contour vertex moved it or added a point: " + draft);
        clickMap(controller, 420, 80, 0);
        require(
                selection.phase() == SelectionState.Phase.SELECTED && selection.geometry() instanceof PolygonGeometry,
                "Clicking the first vertex did not create a complete polygon area");
        PolygonGeometry closed = (PolygonGeometry) selection.geometry();
        require(
                closed.vertices().equals(beforeCancel) && !closed.triangulate().isEmpty() && closed.area() > 0,
                "Closed polygon lost vertices or cannot be filled");
        require(
                (Boolean) get(polygon(controller), "mode") && draft.isEmpty(),
                "Closing the path disabled the polygon tool or left a draft behind");
        call(controller, "updateVisibility");
        require(
                ((Button) get(controller, "areasButton")).active && (Boolean) call(controller, "canExportSelection"),
                "Completed polygon cannot be saved or exported");
        controller.mouseClicked(left(580, 70, GLFW.GLFW_MOD_CONTROL), false);
        controller.mouseReleased(left(590, 90, GLFW.GLFW_MOD_CONTROL));
        PolygonGeometry moved = (PolygonGeometry) selection.geometry();
        require(
                moved.vertices().get(1).equals(new GridPoint(270, -90))
                        && moved.vertices().get(2).equals(closed.vertices().get(2)),
                "Closed vertex edit changed other vertices");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(closed), "Undo did not restore the complete polygon");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
        require(selection.geometry().equals(moved), "Redo did not restore the edited polygon");
        // Shift goes to the nearest axis line through a neighbour, also when pressed during the drag: x 240 of vertex
        // 2.
        controller.mouseClicked(left(590, 90, 0), false);
        controller.beforeRender(595, 120);
        require(
                selection.geometry().vertices().get(1).equals(new GridPoint(275, -60)),
                "The vertex did not follow the pointer");
        key(controller, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_MOD_SHIFT);
        controller.beforeRender(595, 120);
        require(
                selection.geometry().vertices().get(1).equals(new GridPoint(240, -60)),
                "Shift did not align the side: " + selection.geometry().vertices());
        controller.keyReleased(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0));
        controller.beforeRender(595, 120);
        require(
                selection.geometry().vertices().get(1).equals(new GridPoint(275, -60)),
                "Releasing Shift kept the vertex aligned");
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        require(selection.geometry().equals(moved), "Esc did not cancel the aligned drag");
        controller.mouseReleased(left(595, 120, 0));
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_SUPER), false);
        controller.beforeRender(400, 260);
        require(((MapGesture.VertexDrag) get(controller, "gesture")).invalid, "Duplicate closed vertex was accepted");
        controller.mouseReleased(left(400, 260, GLFW.GLFW_MOD_SUPER));
        require(selection.geometry().equals(moved), "Rejected vertex edit did not restore original geometry");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(450, 290);
        require(
                ((MapGesture.VertexDrag) get(controller, "gesture")).invalid,
                "Self-intersecting vertex move was accepted");
        controller.mouseReleased(left(450, 290, GLFW.GLFW_MOD_CONTROL));
        require(selection.geometry().equals(moved), "Self-intersection rejection changed the completed area");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(30_000_400, 100);
        require(
                ((MapGesture.VertexDrag) get(controller, "gesture")).invalid,
                "World-boundary vertex move was accepted");
        controller.mouseReleased(left(30_000_400, 100, GLFW.GLFW_MOD_CONTROL));
        require(selection.geometry().equals(moved), "World-boundary rejection changed the completed area");
        controller.mouseClicked(left(480, 160, GLFW.GLFW_MOD_SHIFT), false);
        controller.beforeRender(500, 180);
        controller.mouseReleased(left(500, 180, GLFW.GLFW_MOD_SHIFT));
        require(
                selection.geometry().equals(moved.translated(20, 20)),
                "Shift drag cannot move a closed polygon while the polygon tool remains enabled");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(moved), "Undo whole-polygon move did not restore geometry");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT);
        require(
                selection.geometry().equals(moved.translated(20, 20)),
                "Cmd+Shift+Z did not redo the whole-polygon move");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        // In contour mode a plain drag inside moves the shape too; a click without movement starts a new contour.
        controller.mouseClicked(left(480, 160, 0), false);
        controller.beforeRender(500, 180);
        controller.mouseReleased(left(500, 180, 0));
        require(
                selection.geometry().equals(moved.translated(20, 20)),
                "A plain drag inside a polygon in contour mode did not move it");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(moved), "Undo did not restore the dragged polygon");
        controller.mouseClicked(left(480, 160, 0), false);
        controller.beforeRender(481, 161);
        controller.mouseReleased(left(481, 161, 0));
        require(
                ((List<?>) get(polygon(controller), "draft")).size() == 1,
                "A click inside a polygon in contour mode did not add a vertex");
        require(
                ((SelectionState.Snapshot) get(polygon(controller), "stateBefore"))
                        .geometry()
                        .equals(moved),
                "A jittery click in contour mode also moved the polygon");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(
                ((List<?>) get(polygon(controller), "draft")).isEmpty()
                        && selection.geometry().equals(moved),
                "Undo did not drop the new contour and bring the polygon back");
        // As when editing, with the Polygon tool on a click on a side of the closed contour (outside or just inside)
        // keeps it and starts nothing, and a double click adds a vertex on that side.
        MapBridge.Frame frame = (MapBridge.Frame) call(controller, "checkedFrame");
        Method inside = declared("insideBounds", MapBridge.Frame.class, double.class, double.class);
        inside.setAccessible(true);
        GridPoint sideStart = moved.vertices().get(0),
                sideEnd = moved.vertices().get(1);
        int mx = (int) Math.round((frame.screenX(sideStart.x()) + frame.screenX(sideEnd.x())) / 2);
        int my = (int) Math.round((frame.screenY(sideStart.z()) + frame.screenY(sideEnd.z())) / 2);
        int[] outside = (Boolean) inside.invoke(receiver(controller, inside), frame, mx, my - 4.0)
                ? new int[] {mx, my + 4}
                : new int[] {mx, my - 4};
        int[] insideSide = {outside[0], 2 * my - outside[1]};
        for (int[] at : new int[][] {outside, insideSide}) {
            clickMap(controller, at[0], at[1], 0);
            require(
                    draft.isEmpty() && selection.geometry().equals(moved),
                    "A click on a side of the closed contour started a new one");
            set(controller, "clickAt", 0L);
        }
        clickMap(controller, insideSide[0], insideSide[1], 0);
        clickMap(controller, insideSide[0], insideSide[1], 0);
        require(
                draft.isEmpty()
                        && selection.geometry().vertices().size()
                                == moved.vertices().size() + 1,
                "A double click on a side of the closed contour added no vertex");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(
                selection.geometry().equals(moved),
                "Undo did not remove the vertex added on the side: " + selection.geometry());
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_CONTROL), false);
        controller.beforeRender(620, 110);
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        controller.mouseReleased(left(620, 110, 0));
        require(selection.geometry().equals(moved), "Esc did not restore the closed polygon");
        clickMap(controller, 620, 320, 0);
        require(draft.size() == 1, "Next click did not start another polygon");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(
                draft.isEmpty() && selection.geometry().equals(moved),
                "Undo first draft point changed the finished area");
        require(
                !((Button) get(controller, "undo")).active,
                "Exhausted draft undo still exposes unrelated area history");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        require(
                selection.geometry().equals(moved),
                "Undo crossed from the unfinished path into unrelated area history");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
        require(draft.size() == 1 && selection.geometry().equals(moved), "Redo did not restore the first draft point");
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        require(
                draft.isEmpty() && selection.geometry().equals(moved),
                "Cancelling the next path lost the completed area");
        press(controller, "areasButton");
        press(controller, "areaSave");
        enter(controller, map, 8, "Polygon editing check");
        SavedAreaStore.Area area =
                store.state().areas().get(store.state().areas().size() - 1);
        require(
                area.geometry().equals(moved) && area.opacity() > 0 && area.visible(),
                "Saved polygon lost its geometry, visibility, or fill");
        press(controller, "areaLoad");
        controller.mouseClicked(left(590, 90, GLFW.GLFW_MOD_SUPER), false);
        controller.mouseReleased(left(600, 95, GLFW.GLFW_MOD_SUPER));
        AreaGeometry editedSaved = selection.geometry();
        require(!editedSaved.equals(moved), "Loaded saved polygon cannot be edited with Cmd");
        press(controller, "quickSave");
        require(
                store.state().areas().stream()
                        .filter(a -> a.id().equals(area.id()))
                        .findFirst()
                        .orElseThrow()
                        .geometry()
                        .equals(editedSaved),
                "Updating saved polygon converted it to a rectangle or lost edits");
        SavedAreaStore reload = new SavedAreaStore(directory);
        reload.useContext(context);
        require(reload.state().equals(store.state()), "Polygon edit was not persisted");
        require(window(controller).equals("NONE"), "Updating geometry did not return to the map");
        store.replace(saved);
        selection.restore(initial);
        ((SelectionHistory) get(controller, "history")).clear();
        ((Set<?>) get(controller, "selectedAreaIds")).clear();
        set(controller, "areaId", null);
        set(polygon(controller), "mode", false);
        set(controller, "message", "");
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        System.out.println(
                "PASS: polygon click-to-close, Ctrl/Cmd vertex drags, draft/closed undo, Esc, invalid moves, filled saved geometry, and persistence");
    }

    static void checkPolygonInsertionAndUpdate(
            ExactSelectionController controller,
            GuiMap map,
            SavedAreaStore store,
            Path directory,
            MapBridge.Context context)
            throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        PolygonGeometry polygon = new PolygonGeometry(List.of(
                new GridPoint(80, -120), new GridPoint(240, -120), new GridPoint(240, 80), new GridPoint(80, 80)));
        SavedAreaStore.Area area = SavedAreaStore.create("Editable polygon", polygon, 0xAABBCC, 35, "polygon-group");
        store.replace(new SavedAreaStore.State(
                List.of(area), List.of(new SavedAreaStore.Group("polygon-group", "Group", false)), true, true));
        press(controller, "areasButton");
        pick(controller, store, area.id());
        press(controller, "areaLoad");
        require(
                selection.geometry().equals(polygon) && !(Boolean) get(polygon(controller), "mode"),
                "Loading a polygon did not enter safe editing mode");
        clickMap(controller, 400, 60, 0);
        clickMap(controller, 402, 61, 0);
        clickMap(controller, 480, 160, 0);
        doubleClickMap(controller, 480, 160, 0);
        require(
                selection.geometry().equals(polygon) && selection.phase() == SelectionState.Phase.SELECTED,
                "Clicks on a vertex or inside replaced a saved polygon with a small rectangle");
        call(controller, "applyFields");
        require(
                selection.geometry().equals(polygon) && !((Button) get(controller, "apply")).active,
                "Applying rectangle coordinates destroyed a polygon");
        for (EditBox field : (EditBox[]) get(controller, "fields"))
            require(!field.active, "Polygon bounds are editable as rectangle coordinates");
        clickMap(controller, 480, 65, GLFW.GLFW_MOD_SHIFT);
        require(
                selection.geometry().equals(polygon) && ((List<?>) get(polygon(controller), "draft")).isEmpty(),
                "First click of a double click started an unrelated path");
        controller.mouseClicked(left(480, 65, GLFW.GLFW_MOD_SHIFT), true);
        controller.mouseReleased(left(480, 65, GLFW.GLFW_MOD_SHIFT));
        PolygonGeometry inserted = (PolygonGeometry) selection.geometry();
        require(
                inserted.vertices().size() == 5 && inserted.vertices().get(1).equals(new GridPoint(160, -120)),
                "Shift double click did not insert an axis-aligned vertex into the correct side");
        require(
                inserted.bounds().equals(polygon.bounds())
                        && inserted.areaTwice() == polygon.areaTwice()
                        && !inserted.triangulate().isEmpty(),
                "Collinear insertion changed bounds, area, or fill");
        key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        require(selection.geometry().equals(polygon), "Insertion undo did not restore the original polygon");
        key(controller, GLFW.GLFW_KEY_Y, GLFW.GLFW_MOD_CONTROL);
        require(selection.geometry().equals(inserted), "Insertion redo did not restore the new vertex");
        Button update = (Button) get(controller, "quickSave");
        require(update.visible && update.active, "Polygon geometry update is unavailable");
        press(controller, "quickSave");
        require(
                window(controller).equals("NONE")
                        && store.state().areas().get(0).equals(area.withGeometry(inserted)),
                "Update geometry lost polygon vertices or area metadata");
        SavedAreaStore reload = new SavedAreaStore(directory);
        reload.useContext(context);
        require(reload.state().equals(store.state()), "Updated vertices with unchanged bounds were not persisted");
        press(controller, "areasButton");
        press(controller, "managementUndo");
        require(store.state().areas().get(0).equals(area), "Geometry update undo did not restore the saved polygon");
        press(controller, "managementRedo");
        pick(controller, store, area.id());
        Button edit = (Button) get(controller, "areaLoad");
        require(
                !edit.active
                        && edit.getMessage()
                                .getString()
                                .equals(Component.translatable("exactselection.area_editing_now")
                                        .getString()),
                "The territory being edited can be loaded again instead of showing that it is being edited");
        press(controller, "managementClose");
        controller.mouseClicked(left(480, 60, GLFW.GLFW_MOD_SUPER), false);
        controller.mouseReleased(left(480, 40, GLFW.GLFW_MOD_SUPER));
        PolygonGeometry moved = (PolygonGeometry) selection.geometry();
        require(moved.vertices().get(1).equals(new GridPoint(160, -140)), "Inserted vertex cannot be moved with Cmd");
        press(controller, "quickSave");
        require(
                store.state().areas().get(0).equals(area.withGeometry(moved)),
                "Moved inserted vertex did not update saved geometry");
        doubleClickMap(controller, 400, 160, 0);
        PolygonGeometry closing = (PolygonGeometry) selection.geometry();
        require(
                closing.vertices().size() == 6 && closing.vertices().getLast().equals(new GridPoint(80, -20)),
                "Double click did not insert into the closing side");
        doubleClickMap(controller, 400, 160, 0);
        require(selection.geometry().equals(closing), "Double click on an existing vertex created a duplicate");
        call(controller, "reset");
        press(controller, "polygonModeButton");
        clickMap(controller, 400, 60, 0);
        key(controller, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_MOD_SHIFT);
        controller.beforeRender(550, 85);
        require(get(polygon(controller), "cursor").equals(new GridPoint(230, -120)), "Shift preview is not horizontal");
        clickMap(controller, 550, 85, GLFW.GLFW_MOD_SHIFT);
        controller.beforeRender(570, 245);
        require(get(polygon(controller), "cursor").equals(new GridPoint(230, 65)), "Shift preview is not vertical");
        clickMap(controller, 570, 245, GLFW.GLFW_MOD_SHIFT);
        @SuppressWarnings("unchecked")
        List<GridPoint> draft = (List<GridPoint>) get(polygon(controller), "draft");
        require(
                draft.equals(List.of(new GridPoint(80, -120), new GridPoint(230, -120), new GridPoint(230, 65))),
                "Shift draft placement disagrees with preview");
        controller.keyReleased(new KeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, GLFW.GLFW_MOD_SHIFT));
        controller.beforeRender(405, 230);
        require(
                get(polygon(controller), "cursor").equals(new GridPoint(85, 50)),
                "Releasing Shift kept preview constrained");
        clickMap(controller, 405, 230, 0);
        clickMap(controller, 400, 60, 0);
        require(
                selection.geometry() instanceof PolygonGeometry finished
                        && finished.vertices().size() == 4
                        && !finished.triangulate().isEmpty(),
                "Axis-aligned draft could not become a filled polygon");
        store.replace(saved);
        selection.restore(initial);
        ((SelectionHistory) get(controller, "history")).clear();
        ((Set<?>) get(controller, "selectedAreaIds")).clear();
        set(controller, "areaId", null);
        set(polygon(controller), "mode", false);
        set(controller, "shiftDown", false);
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        System.out.println(
                "PASS: edge double clicks, insertion undo/redo, Shift point preview/placement, safe saved polygon editing and geometry update persistence");
    }

    static void checkSavedLabelLayering(ExactSelectionController controller, SavedAreaStore store, Unsafe unsafe)
            throws Exception {
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
                if (active) selection.setGeometry(geometry);
                else selection.disable();
                SelectionState.Snapshot before = selection.snapshot();
                RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
                graphics.events = new ArrayList<>();
                graphics.transform = new Matrix3x2fStack(16);
                graphics.state = new GuiRenderState() {
                    @Override
                    public void submitGuiElement(GuiElementRenderState element) {
                        graphics.events.add(new DrawEvent(null, graphics.stratum));
                        super.submitGuiElement(element);
                    }
                };
                controller.renderMapOverlays(graphics, 600, 330);
                List<GuiElementRenderState> geometryDraws = new ArrayList<>();
                graphics.state.forEachElement(geometryDraws::add, GuiRenderState.TraverseRange.ALL);
                require(
                        geometryDraws.size() == (active ? 6 : 4),
                        "Saved or selected area bypasses the renderer preserving fractional coordinates");
                int lastGeometry = graphics.events.stream()
                        .filter(event -> event.text() == null)
                        .mapToInt(DrawEvent::stratum)
                        .max()
                        .orElseThrow();
                for (String name : List.of("Named area", "Overlapping area")) {
                    DrawEvent label = graphics.events.stream()
                            .filter(event -> name.equals(event.text()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Missing saved area label: " + name));
                    require(
                            label.stratum() > lastGeometry,
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
            require(
                    draws.size() == 4 && graphics.events.stream().noneMatch(event -> event.text() != null),
                    "Hidden area names were still drawn, or the areas vanished with them");
        }
        selection.disable();
        for (SavedAreaStore.Area area : List.of(
                SavedAreaStore.create(
                        "Offscreen",
                        new PolygonGeometry(List.of(
                                new GridPoint(5000, 5000),
                                new GridPoint(5100, 5000),
                                new GridPoint(5100, 5100),
                                new GridPoint(5000, 5100))),
                        0x59E3CE,
                        25,
                        null),
                SavedAreaStore.create(
                        "Concave",
                        new PolygonGeometry(List.of(
                                new GridPoint(0, 0),
                                new GridPoint(100, 0),
                                new GridPoint(100, 20),
                                new GridPoint(20, 20),
                                new GridPoint(20, 100),
                                new GridPoint(0, 100))),
                        0x59E3CE,
                        25,
                        null))) {
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
            graphics.events = new ArrayList<>();
            graphics.transform = new Matrix3x2fStack(16);
            graphics.state = new GuiRenderState() {
                @Override
                public void submitGuiElement(GuiElementRenderState element) {
                    graphics.events.add(new DrawEvent(null, graphics.stratum));
                    super.submitGuiElement(element);
                }
            };
            controller.renderMapOverlays(graphics, 600, 330);
            boolean found =
                    graphics.events.stream().anyMatch(event -> area.name().equals(event.text()));
            require(
                    area.name().equals("Offscreen") ? !found : found,
                    area.name().equals("Offscreen")
                            ? "Offscreen polygon name was pinned to the map edge"
                            : "Concave polygon lost its name because its bounding-box center is outside");
        }
        store.replace(saved);
        selection.restore(initial);
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        System.out.println(
                "PASS: saved polygon and rectangle names above active selection and overlapping areas, selection preserved");
    }

    static void checkContourOverLoadedArea(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        AutosaveSettings autosave = (AutosaveSettings) get(controller, "autosave");
        boolean autosaveWas = autosave.enabled();
        try {
            var area = SavedAreaStore.create("Loaded", new SelectionBounds(80, 80, 239, 239), 0x123456, 25, null);
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            selection.enable();
            selection.load(area.geometry(), area.id());
            call(controller, "updateVisibility");
            autosave.setEnabled(true);
            press(controller, "polygonModeButton");
            clickMap(controller, 400, 60, 0);
            clickMap(controller, 600, 60, 0);
            clickMap(controller, 600, 330, 0);
            key(controller, GLFW.GLFW_KEY_ENTER, 0);
            require(
                    selection.geometry() instanceof PolygonGeometry
                            && selection.source() == null
                            && store.state().areas().get(0).geometry().equals(area.geometry()),
                    "A contour finished over a loaded area wrote into it: " + selection.snapshot());
            key(controller, GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
            require(area.id().equals(selection.source()), "Undo did not return to editing the loaded area");
        } finally {
            autosave.setEnabled(autosaveWas);
            set(polygon(controller), "mode", false);
            store.replace(saved);
            selection.restore(initial);
            ((SelectionHistory) get(controller, "history")).clear();
            call(controller, "syncFields");
            call(controller, "updateVisibility");
        }
        System.out.println("PASS: a new contour over a loaded area is a new selection and never edits that area");
    }
}
