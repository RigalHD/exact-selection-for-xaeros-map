package dev.exactselection;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import xaero.map.gui.GuiMap;
import static dev.exactselection.UiHarness.*;

/** The ruler and its routes, the tool keys and snapping to other zones. */
final class ToolChecks {
    private ToolChecks() {}

    static void checkRuler(ExactSelectionController controller, Minecraft minecraft, SavedAreaStore store,
                                   Unsafe unsafe, MapBridge.Context context) throws Exception {
        SelectionState selection = (SelectionState) get(controller, "state");
        SelectionState.Snapshot before = selection.snapshot();
        Object previousKeyboard = get(minecraft, "keyboardHandler");
        ClipboardKeyboard keyboard = (ClipboardKeyboard) unsafe.allocateInstance(ClipboardKeyboard.class);
        set(minecraft, "keyboardHandler", keyboard);
        try {
            // The ruler shows the compact panel in place of the selection's fields, and gives the previous one back.
            set(controller, "compact", false); call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
            int rowWidth = ((Button) get(controller, "chunkMode")).getWidth();
            require(rowWidth >= ((Button) get(controller, "clear")).getWidth() - 3, "The ruler still narrows the button row");
            press(controller, "rulerButton");
            require((boolean) get(ruler(controller), "mode"), "The ruler did not turn on");
            require((boolean) get(controller, "compact") && !((EditBox) ((Object[]) get(controller, "fields"))[0]).visible,
                    "The ruler left the selection's fields in the panel");
            press(controller, "rulerButton");
            require(!(boolean) get(controller, "compact"), "Leaving the ruler did not restore the expanded panel");
            press(controller, "rulerButton");
            press(controller, "panelModeButton");
            require(!(boolean) get(ruler(controller), "mode") && !(boolean) get(controller, "compact"), "Expanding did not leave the ruler");
            set(controller, "compact", true); call(controller, "layoutMainPanel"); call(controller, "updateVisibility");
            press(controller, "rulerButton");
            require(!((Button) get(controller, "copyRulerDistance")).active, "The distance can be copied before B is set");
            // Screen x 320 is world X 0: corners (80, 20) and (110, 60) are 30 and 40 blocks apart, 50 in a straight line.
            // A point snaps to the nearest block corner, like a contour vertex.
            require(controller.mouseClicked(new MouseButtonEvent(400.4, 199.6, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false),
                    "Ruler press was not captured");
            controller.mouseReleased(new MouseButtonEvent(400.4, 199.6, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)));
            require(get(ruler(controller), "pointB") == null, "A single click set both ruler points");
            clickMap(controller, 430, 240, 0);
            require(new GridPoint(80, 20).equals(get(ruler(controller), "pointA")) && new GridPoint(110, 60).equals(get(ruler(controller), "pointB")),
                    "Ruler points are wrong: " + get(ruler(controller), "pointA") + " " + get(ruler(controller), "pointB"));
            require((double) call(controller, "rulerDistance") == 50, "Ruler distance is wrong");
            require(selection.snapshot().equals(before), "Ruler clicks changed the selection");
            call(controller, "updateVisibility"); press(controller, "copyRulerDistance");
            require("50".equals(keyboard.copied), "Copied distance is wrong: " + keyboard.copied);
            // A diagonal of 1 x 1 is 1.41: rounded down, up, or kept.
            clickMap(controller, 400, 200, 0); clickMap(controller, 401, 201, 0);
            call(controller, "updateVisibility"); press(controller, "copyRulerDistance");
            require("1".equals(keyboard.copied), "Distance was not rounded down: " + keyboard.copied);
            AutosaveSettings autosave = (AutosaveSettings) get(controller, "autosave");
            AutosaveSettings.Rounding rounding = autosave.rounding();
            try {
                autosave.setRounding(AutosaveSettings.Rounding.UP);
                call(controller, "updateVisibility"); press(controller, "copyRulerDistance");
                require("2".equals(keyboard.copied), "Distance was not rounded up: " + keyboard.copied);
                autosave.setRounding(AutosaveSettings.Rounding.EXACT);
                call(controller, "updateVisibility"); press(controller, "copyRulerDistance");
                require("1.41".equals(keyboard.copied), "Exact distance is wrong: " + keyboard.copied);
            } finally { autosave.setRounding(rounding); }
            // A press after a finished measurement starts a new one, and a drag sets B where it is released.
            require(controller.mouseClicked(left(500, 100, 0), false), "Ruler press was not captured");
            controller.mouseReleased(left(500, 160, 0));
            require(new GridPoint(180, -80).equals(get(ruler(controller), "pointA")) && new GridPoint(180, -20).equals(get(ruler(controller), "pointB")),
                    "A ruler drag did not measure from press to release");
            // Shift keeps B on A's row or column.
            clickMap(controller, 400, 200, 0); clickMap(controller, 460, 210, GLFW.GLFW_MOD_SHIFT);
            require(new GridPoint(140, 20).equals(get(ruler(controller), "pointB")), "Shift did not align B with A");
            require(call(controller, "rulerOtherDimensionText") == null, "An unknown dimension got a Nether distance");
            set(controller, "context", new MapBridge.Context(context.world(), context.worldId(),
                    net.minecraft.resources.ResourceKey.create(net.minecraft.resources.ResourceKey.createRegistryKey(
                            net.minecraft.resources.Identifier.parse("minecraft:dimension")),
                            net.minecraft.resources.Identifier.parse("minecraft:overworld")), context.subworld()));
            require(call(controller, "rulerOtherDimensionText") != null, "The Overworld has no Nether distance");
            set(controller, "context", context);
            // The measurement is drawn above saved area names.
            RecordingGraphics graphics = (RecordingGraphics) unsafe.allocateInstance(RecordingGraphics.class);
            graphics.events = new ArrayList<>();
            graphics.transform = new Matrix3x2fStack(16);
            graphics.state = new GuiRenderState();
            controller.renderMapOverlays(graphics, 600, 330);
            DrawEvent label = graphics.events.stream().filter(event -> event.text() != null && event.text().contains("ruler_label"))
                    .findFirst().orElseThrow(() -> new AssertionError("The ruler distance is not drawn on the map"));
            require(graphics.events.stream().filter(event -> event.text() != null && event != label)
                    .allMatch(event -> event.stratum() < label.stratum()), "The ruler label is covered by area names");
            // Esc clears the measurement, then turns the ruler off; the selection stays.
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require((boolean) get(ruler(controller), "mode") && get(ruler(controller), "pointA") == null, "Esc did not clear the measurement first");
            key(controller, GLFW.GLFW_KEY_ESCAPE, 0);
            require(!(boolean) get(ruler(controller), "mode") && selection.snapshot().equals(before), "Esc did not leave the ruler alone");
            // The Polygon tool and the ruler exclude each other.
            press(controller, "rulerButton"); press(controller, "polygonModeButton");
            require(!(boolean) get(ruler(controller), "mode") && (boolean) get(polygon(controller), "mode"), "Polygon did not turn the ruler off");
            press(controller, "rulerButton");
            require((boolean) get(ruler(controller), "mode") && !(boolean) get(polygon(controller), "mode"), "The ruler did not turn Polygon off");
            press(controller, "rulerButton");
            // Group summary: the sum of the members' areas (11 x 11 blocks each).
            var a = SavedAreaStore.create("A", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, "g");
            var b = SavedAreaStore.create("B", new SelectionBounds(20, 20, 30, 30), 0x123456, 25, "g");
            Method total = declared("totalArea", List.class);
            total.setAccessible(true);
            require((double) total.invoke(null, List.of(a, b)) == 242, "Group area total is wrong");
        } finally {
            set(minecraft, "keyboardHandler", previousKeyboard);
            selection.restore(before); call(controller, "updateVisibility");
        }
        System.out.println("PASS: ruler points, drag, Shift, copy rounding, Nether distance, Esc, tool exclusion, group area total");
    }

    static void checkZoneSnap(GuiMap map, MapBridge bridge, Minecraft minecraft, Path directory, MapBridge.Context context) throws Exception {
        Object children = get(map, "children");
        int left = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        try {
            // Saved: outer corners (0, 0) to (100, 100), on screen 320..420 x 180..280; one block is one pixel.
            ExactSelectionController contour = toolOn(map, bridge, minecraft, directory, context, true, "snap-contour");
            press(contour, "polygonModeButton");
            mapClick(contour, 316, 176, left);
            mapClick(contour, 316, 230, left);
            mapClick(contour, 290, 230, left);
            clickMap(contour, 317, 283, GLFW.GLFW_MOD_ALT);
            require(draft(contour).equals(List.of(new GridPoint(0, 0), new GridPoint(0, 50), new GridPoint(-30, 50), new GridPoint(-3, 103))),
                    "Contour vertices: stick to a corner and a side, not when far or with Alt: " + draft(contour));

            // A dragged vertex sticks too.
            ExactSelectionController vertex = toolOn(map, bridge, minecraft, directory, context, true, "snap-vertex");
            SelectionState vertexState = (SelectionState) get(vertex, "state");
            vertexState.setGeometry(new PolygonGeometry(List.of(new GridPoint(-60, -60), new GridPoint(-20, -60), new GridPoint(-20, -20))));
            call(vertex, "updateVisibility");
            vertex.mouseClicked(new MouseButtonEvent(300, 160, new MouseButtonInfo(left, 0)), false);
            vertex.mouseReleased(new MouseButtonEvent(316, 176, new MouseButtonInfo(left, 0)));
            require(vertexState.geometry().vertices().contains(new GridPoint(0, 0)), "A dragged vertex did not stick: " + vertexState.geometry().vertices());

            // A dragged edge of a rectangle stops at the neighbour's edge, so that they share it.
            ExactSelectionController edge = toolOn(map, bridge, minecraft, directory, context, true, "snap-edge");
            SelectionState edgeState = (SelectionState) get(edge, "state");
            edgeState.set(new SelectionBounds(-60, 10, -31, 39));
            call(edge, "updateVisibility");
            edge.mouseClicked(new MouseButtonEvent(290, 205, new MouseButtonInfo(left, 0)), false);
            edge.mouseReleased(new MouseButtonEvent(317, 205, new MouseButtonInfo(left, 0)));
            require(edgeState.bounds().equals(new SelectionBounds(-60, 10, -1, 39)), "A dragged edge did not stick: " + edgeState.bounds());

            // A moved rectangle sticks by a corner, keeping its size.
            ExactSelectionController moved = toolOn(map, bridge, minecraft, directory, context, true, "snap-move");
            SelectionState movedState = (SelectionState) get(moved, "state");
            movedState.set(new SelectionBounds(-60, 10, -31, 39));
            call(moved, "updateVisibility");
            moved.mouseClicked(new MouseButtonEvent(275, 205, new MouseButtonInfo(left, 0)), false);
            moved.mouseReleased(new MouseButtonEvent(302, 205, new MouseButtonInfo(left, 0)));
            require(movedState.bounds().equals(new SelectionBounds(-30, 10, -1, 39)), "A moved rectangle did not stick: " + movedState.bounds());

            // A rectangle's corner takes the block beside the edge, on the pointer's side, also while it follows the pointer.
            ExactSelectionController corners = toolOn(map, bridge, minecraft, directory, context, true, "snap-corners");
            SelectionState cornerState = (SelectionState) get(corners, "state");
            mapClick(corners, 316, 230, left);
            require(cornerState.bounds().x1() == -1 && cornerState.bounds().z1() == 50, "The first corner did not stick from outside: " + cornerState.bounds());
            mapClick(corners, 323, 240, left);
            require(cornerState.bounds().x2() == 0 && cornerState.bounds().z2() == 60, "The second corner did not stick from inside: " + cornerState.bounds());

            // Chunk mode keeps its own grid, a hidden area and the area being edited attract nothing.
            ZoneSnap snap = (ZoneSnap) get(corners, "zoneSnap");
            MapBridge.Frame frame = (MapBridge.Frame) call(corners, "checkedFrame");
            require(snap.point(frame, 316, 230) != null, "Nothing to stick to");
            set(corners, "chunks", true);
            require(snap.point(frame, 316, 230) == null, "Chunk mode stuck to an area");
            set(corners, "chunks", false);
            SavedAreaStore cornerStore = (SavedAreaStore) get(corners, "areaStore");
            var zone = cornerStore.state().areas().get(0);
            cornerState.load(zone.geometry(), zone.id());
            require(snap.point(frame, 316, 230) == null, "The edited area attracted its own vertices");
            cornerState.reset();
            cornerStore.replace(new SavedAreaStore.State(List.of(zone.withVisible(false)), List.of(), true, true));
            require(snap.point(frame, 316, 230) == null, "A hidden area attracted the pointer");
        } finally {
            set(map, "children", children);
            map.setFocused(null);
        }
        System.out.println("PASS: contour vertices, dragged vertices, edges, moves and corners stick to saved areas; Alt, chunks, hidden and edited areas do not");
    }

    static void checkRoutes(GuiMap map, MapBridge bridge, Minecraft minecraft, Unsafe unsafe, Path directory, MapBridge.Context context) throws Exception {
        Object children = get(map, "children");
        int left = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        try {
            ExactSelectionController routes = toolOn(map, bridge, minecraft, directory, context, true, "routes");
            SavedAreaStore store = (SavedAreaStore) get(routes, "areaStore");
            press(routes, "rulerButton");
            Button save = (Button) get(routes, "quickSave");
            require(!save.visible, "A ruler with no line offers to save it");
            // Ctrl/Cmd + click goes on from B; the length adds up, the same point twice adds nothing.
            mapClick(routes, 100, 300, left);
            mapClick(routes, 160, 300, left);
            require((double) call(get(routes, "rulerTool"), "rulerDistance") == 60, "The ruler's first segment is wrong");
            clickMap(routes, 160, 340, GLFW.GLFW_MOD_CONTROL);
            clickMap(routes, 160, 340, GLFW.GLFW_MOD_CONTROL);
            require((double) call(get(routes, "rulerTool"), "rulerDistance") == 100, "The route's length is wrong");
            key(routes, GLFW.GLFW_KEY_BACKSPACE, 0);
            require((double) call(get(routes, "rulerTool"), "rulerDistance") == 60, "Backspace did not take the last point back");
            // Cmd does the same on a Mac, where Minecraft turns Ctrl + left click into a right click.
            clickMap(routes, 160, 340, GLFW.GLFW_MOD_SUPER);
            require((double) call(get(routes, "rulerTool"), "rulerDistance") == 100, "Cmd + click did not add a point");
            // Saving makes it an entry of the Areas list, and leaves the ruler on and empty.
            call(routes, "updateVisibility");
            require(save.visible && save.active, "A finished line cannot be saved");
            press(routes, "quickSave");
            require(window(routes).equals("AREA_NAME") && !((Button) get(routes, "newAreaOpacityButton")).visible, "The route's name window is wrong");
            text(routes, 8).setValue("Road");
            press(routes, "modalApply");
            require(window(routes).equals("NONE") && (boolean) get(ruler(routes), "mode") && get(ruler(routes), "pointA") == null, "Saving did not clear the ruler");
            var road = store.state().areas().get(1);
            require(road.name().equals("Road") && road.geometry() instanceof PolylineGeometry && road.geometry().perimeter() == 100,
                    "The route was not stored as a line");
            // It survives a reload, an exchange file and an import without a copy.
            SavedAreaStore reloaded = new SavedAreaStore(store.root());
            reloaded.useContext(context);
            require(reloaded.state().equals(store.state()) && reloaded.state().areas().get(1).geometry().equals(road.geometry()), "The route did not survive a reload");
            Path file = AreaTransfer.export(Files.createDirectories(directory.resolve("route-transfer")), "road", List.of(road), List.of());
            require(AreaTransfer.merge(store.state(), AreaTransfer.read(file)).skipped() == 1, "Importing the route again made a copy");
            var straight = new PolylineGeometry(List.of(new GridPoint(0, 5), new GridPoint(30, 5)));
            require(straight.bounds().length() == 1 && straight.area() == 0 && straight.perimeter() == 30, "A straight route has wrong bounds or length");

            // On the map it is drawn open, named on hover and has its own menu, without Edit and Fill.
            press(routes, "rulerButton");
            MapBridge.Frame frame = (MapBridge.Frame) call(routes, "checkedFrame");
            GuiRenderState state = new GuiRenderState();
            GeometryScreenRenderer.draw(state, new Matrix3x2fStack(16), frame, road.geometry(), 640, 360, 0x40FF0000, 0xFFFF0000, 1.5);
            int[] count = {0};
            state.forEachElement(element -> {
                try {
                    Method vertices = element.getClass().getDeclaredMethod("vertices");
                    vertices.setAccessible(true);
                    count[0] += ((List<?>) vertices.invoke(element)).size();
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }, GuiRenderState.TraverseRange.ALL);
            require(count[0] == 2 * 24, "A route of two segments was drawn with " + count[0] + " vertices, closed or filled");
            mapClick(routes, 130, 300, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            require((boolean) get(routes, "quickMenu") && ((Button) get(routes, "quickMenuHide")).visible
                    && !((Button) get(routes, "quickMenuEdit")).visible && !((Button) get(routes, "quickMenuOpacity")).visible, "The route's menu is wrong");
            key(routes, GLFW.GLFW_KEY_ESCAPE, 0);
            // In the Areas window it shows its length and cannot be edited or exported as a territory.
            press(routes, "areasButton");
            row(routes, 1, false);
            require(!((Button) get(routes, "areaLoad")).active && !((Button) get(routes, "areaExportButton")).active, "A route offers to be edited or exported");
            call(routes, "cancelDialog");
            // A saved route's points stick like an area's, but not its open end to its start.
            ZoneSnap snap = (ZoneSnap) get(routes, "zoneSnap");
            require(new GridPoint(-160, 160).equals(snap.point(frame, 160, 340)), "A route's point did not attract the pointer");
            // A route across a selection counts as inside it when a point is in there.
            require(road.geometry().overlaps(new RectangleGeometry(new SelectionBounds(-170, 110, -150, 130))), "A route's point in a shape did not count");
            require(!road.geometry().overlaps(new RectangleGeometry(new SelectionBounds(0, 0, 10, 10))), "A far shape counted for a route");
        } finally {
            set(map, "children", children);
            map.setFocused(null);
        }
        System.out.println("PASS: ruler routes add up, step back, save as an open line, reload, re-import, draw, hover, menu, Areas window, stick");
    }

    static void checkToolKeys(GuiMap map, MapBridge bridge, Minecraft minecraft, Path directory, MapBridge.Context context) throws Exception {
        Object children = get(map, "children");
        try {
            ExactSelectionController keys = toolOn(map, bridge, minecraft, directory, context, true, "keys");
            key(keys, GLFW.GLFW_KEY_O, 0);
            require((boolean) get(polygon(keys), "mode"), "The Polygon key did not switch the tool on");
            key(keys, GLFW.GLFW_KEY_O, 0);
            require(!(boolean) get(polygon(keys), "mode"), "The Polygon key did not switch the tool off");
            key(keys, GLFW.GLFW_KEY_R, 0);
            require((boolean) get(ruler(keys), "mode"), "The ruler key did not switch the ruler on");
            key(keys, GLFW.GLFW_KEY_R, 0);
            key(keys, GLFW.GLFW_KEY_N, 0);
            require((boolean) get(keys, "chunks") && get(keys, "message").toString().contains("units_chunks"), "The units key did not say the new units");
            key(keys, GLFW.GLFW_KEY_N, 0);
            key(keys, GLFW.GLFW_KEY_J, 0);
            require(window(keys).equals("AREAS"), "The Areas key did not open the window");
            // A window or a text field keeps the keys.
            key(keys, GLFW.GLFW_KEY_R, 0);
            require(!(boolean) get(ruler(keys), "mode"), "A key acted behind a window");
            call(keys, "cancelDialog");
            set(keys, "compact", false); call(keys, "layoutMainPanel"); call(keys, "updateVisibility");
            map.setFocused(((EditBox[]) get(keys, "fields"))[0]);
            key(keys, GLFW.GLFW_KEY_O, 0);
            require(!(boolean) get(polygon(keys), "mode"), "A key acted while typing");
            // An unavailable action stays unavailable from its key: a held contour blocks the ruler.
            map.setFocused(null);
            key(keys, GLFW.GLFW_KEY_O, 0);
            drawContour(keys);
            key(keys, GLFW.GLFW_KEY_R, 0);
            require(!(boolean) get(ruler(keys), "mode") && draft(keys).size() == 5, "The ruler key went past its button's rules");
        } finally {
            set(map, "children", children);
            map.setFocused(null);
        }
        System.out.println("PASS: tool keys press their buttons, not behind windows, while typing or when unavailable");
    }
}
