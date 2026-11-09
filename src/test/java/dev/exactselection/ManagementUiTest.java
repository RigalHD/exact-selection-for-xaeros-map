package dev.exactselection;

import static dev.exactselection.AreaChecks.*;
import static dev.exactselection.GestureChecks.*;
import static dev.exactselection.PanelChecks.*;
import static dev.exactselection.PolygonChecks.*;
import static dev.exactselection.SessionChecks.*;
import static dev.exactselection.ToolChecks.*;
import static dev.exactselection.UiHarness.*;
import static dev.exactselection.WindowChecks.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import sun.misc.Unsafe;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;

/**
 * The interface scenarios, in the order they were written: each one starts from the state the one before it left, so
 * they share one controller and run in a fixed order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManagementUiTest {
    private Unsafe unsafe;
    private Minecraft minecraft;
    private GuiMap map;
    private Path directory;
    private MapBridge.Context context;
    private MapBridge bridge;
    private SavedAreaStore store, minimapStore;
    private ExactSelectionController controller;
    private SelectionState selection;

    @BeforeAll
    void setUp() throws Exception {
        SharedConstants.tryDetectVersion();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = (Unsafe) unsafeField.get(null);
        minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, minecraft);
        FixedFont font = new FixedFont();
        set(minecraft, "font", font);
        map = (GuiMap) unsafe.allocateInstance(GuiMap.class);
        set(map, "minecraft", minecraft);
        set(map, "font", font);
        for (String name : List.of("children", "narratables", "renderables")) set(map, name, new ArrayList<>());
        map.width = 640;
        map.height = 360;
        directory = Files.createTempDirectory("exact-selection-ui-");
        context = new MapBridge.Context(new Object(), "ui-check", null, "main");
        bridge = new MapBridge() {
            @Override
            public Frame exactselection$frame() {
                return new Frame(context, 0, 0, 1, 1, map.width, map.height);
            }

            @Override
            public MapProcessor exactselection$processor() {
                return null;
            }

            @Override
            public void exactselection$pan(double x, double y) {}

            @Override
            public void exactselection$clearNativeInput() {}

            @Override
            public void exactselection$moveCamera(double x, double z, double pixelsPerBlock) {}

            @Override
            public void exactselection$closeMap() {}
        };
        store = new SavedAreaStore(directory);
        store.useContext(context);
        var a = SavedAreaStore.create("First", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
        var b = SavedAreaStore.create("Second", new SelectionBounds(20, 20, 30, 30), 0x654321, 50, null);
        store.replace(new SavedAreaStore.State(List.of(a, b), List.of(), true, true));
        minimapStore = new SavedAreaStore(directory);
        minimapStore.useContext(context);
        controller = new ExactSelectionController(map, bridge, minecraft, store, null);
        autosaveProperties(controller);
        controller.init();
        selection = (SelectionState) get(controller, "state");
        selection.enable();
        selection.set(new SelectionBounds(40, 40, 50, 50));
        set(controller, "context", context);
        set(controller, "historyContext", context);
        call(controller, "updateVisibility");
    }

    @AfterAll
    void cleanUp() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    @Order(1)
    void separateCorners() throws Exception {
        checkSeparateCorners(controller, map, minecraft, store, unsafe);
    }

    @Test
    @Order(2)
    void selectionPanel() throws Exception {
        checkSelectionPanel(controller, map);
    }

    @Test
    @Order(3)
    void hintFitting() throws Exception {
        checkHintFitting(controller);
    }

    @Test
    @Order(4)
    void framedAreasAndMetrics() throws Exception {
        checkFramedAreasAndMetrics(controller, store);
    }

    @Test
    @Order(5)
    void areaDraftAndAutosave() throws Exception {
        checkAreaDraftAndAutosave(controller, map, store, unsafe);
    }

    @Test
    @Order(6)
    void layersAndOverlap() throws Exception {
        checkLayersAndOverlap(controller, store);
    }

    @Test
    @Order(7)
    void areaListAndTransfer() throws Exception {
        checkAreaListAndTransfer(controller, store, directory);
    }

    @Test
    @Order(8)
    void exportWindow() throws Exception {
        checkExportWindow(controller, map, directory);
    }

    @Test
    @Order(9)
    void exportNotice() throws Exception {
        checkExportNotice(controller, map, directory);
    }

    @Test
    @Order(10)
    void polygonEditing() throws Exception {
        checkPolygonEditing(controller, map, store, directory, context);
    }

    @Test
    @Order(11)
    void savedLabelLayering() throws Exception {
        checkSavedLabelLayering(controller, store, unsafe);
    }

    @Test
    @Order(12)
    void polygonInsertionAndUpdate() throws Exception {
        checkPolygonInsertionAndUpdate(controller, map, store, directory, context);
    }

    @Test
    @Order(13)
    void geometryGestures() throws Exception {
        checkGeometryGestures(controller, map, unsafe);
    }

    @Test
    @Order(14)
    void gestureEdgeCases() throws Exception {
        checkGestureEdgeCases(controller, context);
    }

    @Test
    @Order(15)
    void precisePointer() throws Exception {
        checkPrecisePointer(controller, minecraft, unsafe);
    }

    @Test
    @Order(16)
    void waypointRows() throws Exception {
        checkWaypointRows(controller);
    }

    @Test
    @Order(17)
    void releaseFeatures() throws Exception {
        checkReleaseFeatures(controller, map, store);
    }

    @Test
    @Order(18)
    void quickMenu() throws Exception {
        checkQuickMenu(controller, store);
    }

    @Test
    @Order(19)
    void contourOverLoadedArea() throws Exception {
        checkContourOverLoadedArea(controller, store);
    }

    @Test
    @Order(20)
    void ruler() throws Exception {
        checkRuler(controller, minecraft, store, unsafe, context);
    }

    @Test
    @Order(21)
    void eventsInOneFrame() throws Exception {
        checkEventsInOneFrame(map, bridge, minecraft, directory, context);
    }

    @Test
    @Order(22)
    void modesAcrossWindows() throws Exception {
        checkModesAcrossWindows(map, bridge, minecraft, unsafe, directory, context);
    }

    @Test
    @Order(23)
    void keepingWork() throws Exception {
        checkKeepingWork(map, bridge, minecraft, unsafe, directory, context);
    }

    @Test
    @Order(24)
    void zonesOnTheMap() throws Exception {
        checkZonesOnTheMap(map, bridge, minecraft, unsafe, directory, context);
    }

    @Test
    @Order(25)
    void toolKeys() throws Exception {
        checkToolKeys(map, bridge, minecraft, directory, context);
    }

    @Test
    @Order(26)
    void zoneSnap() throws Exception {
        checkZoneSnap(map, bridge, minecraft, directory, context);
    }

    @Test
    @Order(27)
    void routes() throws Exception {
        checkRoutes(map, bridge, minecraft, unsafe, directory, context);
    }

    @Test
    @Order(28)
    void colorPicker() throws Exception {
        checkColorPicker(controller);
    }

    @Test
    @Order(29)
    void waypointManagement() throws Exception {
        checkWaypointManagement(map, bridge, minecraft, store, context);
    }

    @Test
    @Order(30)
    void groups() throws Exception {
        checkGroups(controller, map, store, minimapStore, selection, directory, context);
    }
}
