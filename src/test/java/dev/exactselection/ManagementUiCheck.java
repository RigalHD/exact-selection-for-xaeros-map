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
import sun.misc.Unsafe;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;

public final class ManagementUiCheck {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, minecraft);
        FixedFont font = new FixedFont();
        set(minecraft, "font", font);
        GuiMap map = (GuiMap) unsafe.allocateInstance(GuiMap.class);
        set(map, "minecraft", minecraft);
        set(map, "font", font);
        for (String name : List.of("children", "narratables", "renderables")) set(map, name, new ArrayList<>());
        map.width = 640;
        map.height = 360;
        Path directory = Files.createTempDirectory("exact-selection-ui-");
        try {
            MapBridge.Context context = new MapBridge.Context(new Object(), "ui-check", null, "main");
            MapBridge bridge = new MapBridge() {
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
            SavedAreaStore store = new SavedAreaStore(directory);
            store.useContext(context);
            var a = SavedAreaStore.create("First", new SelectionBounds(0, 0, 10, 10), 0x123456, 25, null);
            var b = SavedAreaStore.create("Second", new SelectionBounds(20, 20, 30, 30), 0x654321, 50, null);
            store.replace(new SavedAreaStore.State(List.of(a, b), List.of(), true, true));
            SavedAreaStore minimapStore = new SavedAreaStore(directory);
            minimapStore.useContext(context);
            ExactSelectionController controller = new ExactSelectionController(map, bridge, minecraft, store, null);
            autosaveProperties(controller);
            controller.init();
            SelectionState selection = (SelectionState) get(controller, "state");
            selection.enable();
            selection.set(new SelectionBounds(40, 40, 50, 50));
            set(controller, "context", context);
            set(controller, "historyContext", context);
            call(controller, "updateVisibility");
            checkSeparateCorners(controller, map, minecraft, store, unsafe);
            checkSelectionPanel(controller, map);
            checkHintFitting(controller);
            checkFramedAreasAndMetrics(controller, store);
            checkAreaDraftAndAutosave(controller, map, store, unsafe);
            checkLayersAndOverlap(controller, store);
            checkAreaListAndTransfer(controller, store, directory);
            checkExportWindow(controller, map, directory);
            checkExportNotice(controller, map, directory);
            checkPolygonEditing(controller, map, store, directory, context);
            checkSavedLabelLayering(controller, store, unsafe);
            checkPolygonInsertionAndUpdate(controller, map, store, directory, context);
            checkGeometryGestures(controller, map, unsafe);
            checkGestureEdgeCases(controller, context);
            checkPrecisePointer(controller, minecraft, unsafe);
            checkWaypointRows(controller);
            checkReleaseFeatures(controller, map, store);
            checkQuickMenu(controller, store);
            checkContourOverLoadedArea(controller, store);
            checkRuler(controller, minecraft, store, unsafe, context);
            checkEventsInOneFrame(map, bridge, minecraft, directory, context);
            checkModesAcrossWindows(map, bridge, minecraft, unsafe, directory, context);
            checkKeepingWork(map, bridge, minecraft, unsafe, directory, context);
            checkZonesOnTheMap(map, bridge, minecraft, unsafe, directory, context);
            checkToolKeys(map, bridge, minecraft, directory, context);
            checkZoneSnap(map, bridge, minecraft, directory, context);
            checkRoutes(map, bridge, minecraft, unsafe, directory, context);
            checkColorPicker(controller);
            checkWaypointManagement(map, bridge, minecraft, store, context);
            checkGroups(controller, map, store, minimapStore, selection, directory, context);
            System.out.println(
                    "PASS: management UI callbacks, separate windows, layouts, assignment, visibility, persistence, and undo");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
