package dev.exactselection;

import static dev.exactselection.UiHarness.*;

import com.mojang.blaze3d.platform.Window;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.gui.GuiMap;

/** Pressing and dragging on the map, and the right-click menu. */
final class GestureChecks {
    private GestureChecks() {}

    static void checkGeometryGestures(ExactSelectionController controller, GuiMap map, Unsafe unsafe) throws Exception {
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
        require(
                selection.bounds().equals(new SelectionBounds(80, 80, 239, 239)),
                "Clicking a rectangle handle without moving changed its inclusive edge");

        set(controller, "chunks", true);
        controller.mouseClicked(left(556, 340, 0), false);
        controller.mouseReleased(left(556, 340, 0));
        require(
                selection.bounds().equals(new SelectionBounds(80, 80, 239, 239)),
                "Clicking a chunk-snapped handle changed its edge without movement");

        set(controller, "chunks", false);
        controller.mouseClicked(left(450, 300, GLFW.GLFW_MOD_SHIFT), false);
        controller.mouseReleased(left(480, 300, GLFW.GLFW_MOD_SHIFT));
        require(
                selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "Pointer movement between the last frame and release was lost");

        selection.set(new SelectionBounds(80, 80, 239, 239));
        controller.mouseClicked(left(560, 340, 0), false);
        controller.mouseReleased(left(570, 340, 0));
        require(
                selection.bounds().equals(new SelectionBounds(80, 80, 249, 239)),
                "The resize release coordinate was not applied");

        // Like a photo editor: a plain drag inside moves the selection; a click inside without moving keeps it.
        selection.set(new SelectionBounds(80, 80, 239, 239));
        controller.mouseClicked(left(450, 300, 0), false);
        controller.mouseReleased(left(480, 300, 0));
        require(
                selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A plain drag inside did not move the selection");
        controller.mouseClicked(left(450, 300, 0), false);
        controller.mouseReleased(left(450, 300, 0));
        require(
                selection.phase() == SelectionState.Phase.SELECTED
                        && selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A click inside the selection started a new one");
        // A click whose pointer jitters a pixel or two is still a click (zoomed out, a pixel is many blocks).
        controller.mouseClicked(left(450, 300, 0), false);
        controller.beforeRender(452, 301);
        require(
                selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A held jittery click moved the selection");
        controller.mouseReleased(left(452, 301, 0));
        require(
                selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A 2-pixel jitter moved the selection: " + selection.bounds());
        controller.mouseClicked(left(586, 340, 0), false);
        controller.beforeRender(588, 341);
        controller.mouseReleased(left(588, 341, 0));
        require(
                selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A 2-pixel jitter on a handle resized the selection: " + selection.bounds());
        // In contour mode a click on a handle still starts a contour instead of resizing.
        set(polygon(controller), "mode", true);
        controller.mouseClicked(left(586, 340, 0), false);
        controller.mouseReleased(left(586, 340, 0));
        require(
                ((List<?>) get(polygon(controller), "draft")).size() == 1
                        && selection.bounds().equals(new SelectionBounds(110, 80, 269, 239)),
                "A click on a handle in contour mode did not add a vertex or changed the frame");
        key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
        require(!(Boolean) get(polygon(controller), "mode"), "Esc did not leave contour mode");
        selection.set(new SelectionBounds(110, 80, 269, 239));
        selection.reset();
        call(controller, "updateVisibility");
        for (String name : List.of("sizeButton", "moveButton", "centerButton"))
            require(!((Button) get(controller, name)).active, name + " works without a selection");
        selection.set(new SelectionBounds(80, 80, 239, 239));
        call(controller, "updateVisibility");
        require(((Button) get(controller, "moveButton")).active, "Move is unavailable for a finished selection");

        selection.enable();
        set(controller, "chunks", true);
        clickMap(controller, 500, 260, 0);
        RecordingGraphics previewGraphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
        previewGraphics.events = new ArrayList<>();
        previewGraphics.transform = new Matrix3x2fStack(16);
        previewGraphics.state = new GuiRenderState();
        controller.beforeRender(480, 240);
        controller.renderMapOverlays(previewGraphics, 480, 240);
        require(
                selection.bounds().equals(new SelectionBounds(191, 95, 160, 48)),
                "Reverse chunk preview did not cover whole chunks before confirmation");
        clickMap(controller, 480, 240, 0);
        require(
                selection.bounds().equals(new SelectionBounds(191, 95, 160, 48)),
                "Reverse chunk selection did not include both complete chunks");

        PolygonGeometry polygon = new PolygonGeometry(List.of(
                new GridPoint(80, 80), new GridPoint(100, 80), new GridPoint(100, 100), new GridPoint(80, 100)));
        selection.setGeometry(polygon);
        controller.mouseClicked(left(410, 270, GLFW.GLFW_MOD_SHIFT), false);
        controller.mouseReleased(left(431, 270, GLFW.GLFW_MOD_SHIFT));
        require(
                selection.geometry().equals(polygon.translated(21, 0)),
                "Rectangle chunk snapping leaked into polygon movement");
        // A press just inside an edge moves the shape too; only double clicks and presses outside act on the side.
        MapBridge.Frame frame = (MapBridge.Frame) call(controller, "checkedFrame");
        for (String source : new String[] {null, "edited-rectangle"}) {
            AreaGeometry shape =
                    source == null ? polygon : new RectangleGeometry(new SelectionBounds(80, 80, 119, 119));
            selection.load(shape, source);
            // Mid-side: a press on a polygon vertex drags that vertex instead.
            int px = (int) Math.round(frame.screenX(80)) + 3, py = (int) Math.round(frame.screenY(90));
            controller.mouseClicked(left(px, py, 0), false);
            controller.beforeRender(px + 20, py);
            controller.mouseReleased(left(px + 20, py, 0));
            require(
                    !selection.geometry().equals(shape)
                            && selection.geometry().bounds().minX() > 80,
                    "A press near the edge did not move " + shape + ": " + selection.geometry());
        }
        selection.load(polygon, null);
        // Outside a loaded polygon a click starts a new selection, which no longer edits the saved area.
        selection.load(polygon, "loaded-area");
        clickMap(controller, 600, 350, 0);
        clickMap(controller, 640, 390, 0);
        require(
                selection.phase() == SelectionState.Phase.SELECTED
                        && selection.geometry() instanceof RectangleGeometry
                        && selection.source() == null,
                "A click outside a loaded polygon did not start a new selection: " + selection.snapshot());

        set(controller, "sizeFromCenter", true);
        set(controller, "moveInChunks", true);
        controller.init();
        Button anchor = (Button) get(controller, "anchorButton");
        Button units = (Button) get(controller, "moveUnitsButton");
        Method translate = declared("tr", String.class, Object[].class);
        translate.setAccessible(true);
        Component expectedAnchor =
                (Component) translate.invoke(controller, new Object[] {"anchor_center", new Object[0]});
        Component expectedUnits =
                (Component) translate.invoke(controller, new Object[] {"units_chunks", new Object[0]});
        require(
                anchor.getMessage().getString().equals(expectedAnchor.getString()),
                "Resize anchor label did not follow its saved mode after rebuilding the screen");
        require(
                units.getMessage().getString().equals(expectedUnits.getString()),
                "Move units label did not follow its saved mode after rebuilding the screen");

        selection.restore(initial);
        set(controller, "chunks", initialChunks);
        set(controller, "sizeFromCenter", initialAnchor);
        set(controller, "moveInChunks", initialMoveUnits);
        ((SelectionHistory) get(controller, "history")).clear();
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        System.out.println(
                "PASS: stationary and offset handle drags, release coordinates, reverse chunk selection, polygon movement, and mode labels");
    }

    static void checkGestureEdgeCases(ExactSelectionController controller, MapBridge.Context context) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        // Frame: scale 1 at the origin, so screen = world + (320, 180). Blocks X 0..15 span screen 320..336.
        SelectionBounds chunk = new SelectionBounds(0, 100, 15, 115);
        require(
                dragHandle(controller, chunk, 320, 288, 352, 288).equals(new SelectionBounds(16, 100, 31, 115)),
                "Dragging the west edge past the east edge changed the width: " + selection.bounds());
        require(
                dragHandle(controller, new SelectionBounds(16, 100, 31, 115), 352, 288, 320, 288)
                        .equals(chunk),
                "Dragging the east edge past the west edge changed the width: " + selection.bounds());
        require(
                dragHandle(controller, chunk, 328, 280, 328, 312).equals(new SelectionBounds(0, 116, 15, 131)),
                "Dragging the north edge past the south edge changed the length: " + selection.bounds());
        require(
                dragHandle(controller, chunk, 320, 288, 336, 288).equals(new SelectionBounds(16, 100, 16, 115)),
                "Dragging onto the opposite edge did not keep the one block under the cursor: " + selection.bounds());
        set(controller, "chunks", true);
        try {
            require(
                    dragHandle(controller, chunk, 320, 288, 352, 288).equals(new SelectionBounds(16, 100, 31, 115)),
                    "Chunk mode lost the 16-block grid after crossing edges: " + selection.bounds());
        } finally {
            set(controller, "chunks", false);
        }

        // Losing the map context while a handle is held must end the gesture instead of crashing on release.
        selection.set(chunk);
        controller.mouseClicked(left(336, 288, 0), false);
        Object grabbed = get(controller, "gesture");
        require(
                grabbed instanceof MapGesture.Drag d && d.handle == MapGesture.Handle.EAST,
                "East handle was not grabbed");
        MapGesture.Drag drag = (MapGesture.Drag) grabbed;
        set(controller, "context", new MapBridge.Context(new Object(), "other", null, "main"));
        require(controller.mouseReleased(left(360, 288, 0)), "Release after a context change was not consumed");
        require(get(controller, "gesture") == null && !selection.enabled(), "Context change did not end the drag");
        MapGesture.Host host = (MapGesture.Host) get(controller, "gestureHost");
        SelectionState.Snapshot afterLoss = selection.snapshot();
        require(
                !drag.finish(host) && !drag.cancel(host) && selection.snapshot().equals(afterLoss),
                "A dropped gesture still committed or restored the selection");
        set(controller, "context", context);
        selection.restore(initial);

        // A double click within the hit tolerance inserts the vertex on the side and keeps the shape.
        PolygonGeometry square = new PolygonGeometry(
                List.of(new GridPoint(0, 100), new GridPoint(40, 100), new GridPoint(40, 140), new GridPoint(0, 140)));
        selection.setGeometry(square);
        doubleClickMap(controller, 340, 285, 0);
        PolygonGeometry inserted = (PolygonGeometry) selection.geometry();
        require(
                inserted.vertices().size() == 5
                        && inserted.vertices().get(1).equals(new GridPoint(20, 100))
                        && inserted.areaTwice() == square.areaTwice(),
                "Inserted vertex bent the side: " + inserted.vertices());
        ((SelectionHistory) get(controller, "history")).clear();
        selection.restore(initial);
        call(controller, "syncFields");
        call(controller, "updateVisibility");
        System.out.println("PASS: edge crossing keeps sizes, release after context loss, and on-side vertex insertion");
    }

    static void checkPrecisePointer(ExactSelectionController controller, Minecraft minecraft, Unsafe unsafe)
            throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        Window window = (Window) unsafe.allocateInstance(Window.class);
        set(window, "width", 640);
        set(window, "height", 360);
        set(window, "guiScaledWidth", 640);
        set(window, "guiScaledHeight", 360);
        MouseHandler mouse = (MouseHandler) unsafe.allocateInstance(MouseHandler.class);
        set(mouse, "xpos", 400.6);
        set(mouse, "ypos", 300.2);
        set(minecraft, "window", window);
        set(minecraft, "mouseHandler", mouse);
        try {
            if (!selection.enabled()) selection.enable();
            set(polygon(controller), "mode", true);
            controller.beforeRender(400, 300);
            GridPoint cursor = (GridPoint) get(polygon(controller), "cursor");
            require(
                    new GridPoint(81, 120).equals(cursor),
                    "Polygon preview used the whole render pixel instead of the click position: " + cursor);
        } finally {
            set(minecraft, "window", null);
            set(minecraft, "mouseHandler", null);
            set(polygon(controller), "mode", false);
            set(polygon(controller), "cursor", null);
            selection.restore(initial);
        }
        System.out.println("PASS: map previews follow the fractional cursor used by clicks");
    }

    static void checkQuickMenu(ExactSelectionController controller, SavedAreaStore store) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot initial = selection.snapshot();
        SavedAreaStore.State saved = store.state();
        try {
            selection.enable();
            selection.set(new SelectionBounds(80, 80, 239, 239));
            call(controller, "updateVisibility");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(490, 300));
            require(!(Boolean) get(controller, "quickMenu"), "Panning with the right button opened the quick menu");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            require(
                    (Boolean) get(controller, "quickMenu")
                            && ((Button) get(controller, "quickMenuSave")).visible
                            && !((Button) get(controller, "quickMenuDelete")).visible,
                    "A new selection did not offer Save and Clear");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(
                    !(Boolean) get(controller, "quickMenu") && selection.bounds() != null,
                    "Esc did not just close the quick menu");
            // Size, Move and Center moved from the panel into this menu; a polygon has no Size.
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            require(
                    ((Button) get(controller, "sizeButton")).visible
                            && ((Button) get(controller, "centerButton")).visible,
                    "Size and Center are not in the quick menu");
            press(controller, "moveButton");
            require(
                    window(controller).equals("MOVE") && !(Boolean) get(controller, "quickMenu"),
                    "Quick Move did not open its window");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(!((Button) get(controller, "sizeButton")).visible, "Size is still shown outside the quick menu");
            selection.setGeometry(new PolygonGeometry(List.of(
                    new GridPoint(80, 80), new GridPoint(239, 80), new GridPoint(239, 239), new GridPoint(80, 239))));
            call(controller, "updateVisibility");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            require(
                    !((Button) get(controller, "sizeButton")).visible
                            && ((Button) get(controller, "moveButton")).visible,
                    "A polygon's quick menu offered Size or lost Move");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);

            var area = SavedAreaStore.create("Edited", new SelectionBounds(80, 80, 239, 239), 0x123456, 25, null);
            store.replace(new SavedAreaStore.State(List.of(area), List.of(), true, true));
            selection.load(area.geometry(), area.id());
            call(controller, "updateVisibility");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            press(controller, "quickMenuOpacity");
            require(
                    store.state().areas().get(0).opacity() == 50 && (Boolean) get(controller, "quickMenu"),
                    "Quick fill did not apply at once or closed the menu");
            press(controller, "quickMenuColor");
            require(window(controller).equals("COLOR"), "Quick color did not open the palette");
            controller.mouseClicked(
                    left(
                            (Integer) call(
                                            controller,
                                            "swatchX",
                                            (dialogRect(controller))[0],
                                            (dialogRect(controller))[2],
                                            2)
                                    + 2,
                            (dialogRect(controller))[1] + 36,
                            0),
                    false);
            require(
                    window(controller).equals("NONE")
                            && store.state().areas().get(0).color() == AreaPalette.COLORS[2],
                    "A quick color pick was not applied to the territory");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            press(controller, "quickMenuDelete");
            require(
                    store.state().areas().size() == 1 && window(controller).equals("AREAS"),
                    "Quick delete did not ask first");
            press(controller, "cancel");
            require(
                    store.state().areas().size() == 1
                            && window(controller).equals("NONE")
                            && selection.bounds() != null,
                    "Cancelling a quick delete did not return to the map untouched");
            call(controller, "updateVisibility");
            controller.mouseClicked(right(450, 300), false);
            controller.mouseReleased(right(450, 300));
            press(controller, "quickMenuDelete");
            press(controller, "confirm");
            require(window(controller).equals("NONE"), "A confirmed quick delete did not return to the map");
            require(
                    store.state().areas().isEmpty() && selection.bounds() == null,
                    "Quick delete kept the zone or the selection");
            call(controller, "updateVisibility");
            press(controller, "undo");
            press(controller, "undo");
            require(
                    store.state().areas().size() == 1 && selection.source() != null,
                    "Undo did not bring the deleted zone back");
        } finally {
            set(controller, "quickMenu", false);
            store.replace(saved);
            selection.restore(initial);
            ((SelectionHistory) get(controller, "history")).clear();
            call(controller, "syncFields");
            call(controller, "updateVisibility");
        }
        System.out.println(
                "PASS: right-click quick menu: pan does not open it, save/clear for new, fill/color/delete for an edited zone, Esc, undo");
    }
}
