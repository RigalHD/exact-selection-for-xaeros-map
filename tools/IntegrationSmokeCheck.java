import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

/** Initialize Fabric and apply real Mixins without launching Minecraft or opening a window. */
public final class IntegrationSmokeCheck {
    private static void checkLayerOrder() throws Exception {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of(".mixin.out/class/xaero/map/gui/GuiMap.class")))
                .accept(node, 0);
        org.objectweb.asm.tree.MethodNode render = node.methods.stream()
                .filter(m -> m.name.equals("render") || m.name.equals("method_25394"))
                .filter(m -> m.desc.endsWith(";IIF)V"))
                .findFirst()
                .orElseThrow();
        int overlay = -1, widgets = -1, menu = -1, panel = -1, index = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : render.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (call.name.contains("exactselection$mapOverlays")) overlay = index;
                if (call.owner.equals("xaero/lib/client/gui/ScreenBase")
                        && (call.name.equals("render") || call.name.equals("method_25394"))) widgets = index;
                if (call.owner.equals("xaero/map/gui/dropdown/rightclick/GuiRightClickMenu")
                        && (call.name.equals("render") || call.name.equals("method_25394"))) menu = index;
                if (call.name.endsWith("exactselection$render")) panel = index;
            }
            index++;
        }
        if (overlay < 0 || widgets <= overlay || menu <= widgets || panel <= menu)
            throw new AssertionError(
                    "Incorrect map layer order: " + overlay + ", " + widgets + ", " + menu + ", " + panel);
        org.objectweb.asm.tree.MethodNode released = node.methods.stream()
                .filter(m -> m.name.equals("keyReleased") || m.name.equals("method_16803"))
                .findFirst()
                .orElseThrow();
        boolean shiftRelease = false;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : released.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.name.contains("exactselection$keyReleased")) shiftRelease = true;
        }
        if (!shiftRelease) throw new AssertionError("Shift release hook is missing from the actual map screen");
        System.out.println("PASS: Shift key release hook on transformed Xaero map");
        System.out.println(
                "PASS: transformed render order is map overlays, native widgets, context menu, selection panel");
    }

    private static void checkGuiGeometry(ClassLoader loader) throws Exception {
        Class<?> graphics = Class.forName("net.minecraft.class_332", false, loader);
        Class<?> bridge = Class.forName("dev.exactselection.GuiGraphicsBridge", false, loader);
        if (!bridge.isAssignableFrom(graphics)) throw new AssertionError("GUI geometry bridge mixin was not applied");
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Object canvas = unsafe.allocateInstance(graphics);
        java.lang.reflect.Method getState = bridge.getMethod("exactselection$renderState");
        Class<?> stateType = getState.getReturnType();
        Object state = stateType.getConstructor().newInstance();
        for (java.lang.reflect.Field field : graphics.getDeclaredFields()) {
            if (field.getType() == stateType) {
                field.setAccessible(true);
                field.set(canvas, state);
            }
            if (field.getType().getName().equals("org.joml.Matrix3x2fStack")) {
                field.setAccessible(true);
                field.set(canvas, field.getType().getConstructor(int.class).newInstance(16));
            }
        }
        if (getState.invoke(canvas) != state)
            throw new AssertionError("GUI geometry bridge reads the wrong render state field");
        Class<?> frameType = Class.forName("dev.exactselection.MapBridge$Frame", false, loader);
        Object frame = frameType.getConstructors()[0].newInstance(null, 0.0, 0.0, 1.0, 1.0, 400, 300);
        Class<?> pointType = Class.forName("dev.exactselection.GridPoint", false, loader);
        var point = pointType.getConstructor(long.class, long.class);
        Object polygon = Class.forName("dev.exactselection.PolygonGeometry", false, loader)
                .getConstructor(java.util.List.class)
                .newInstance(java.util.List.of(
                        point.newInstance(-150L, -100L), point.newInstance(150L, -100L), point.newInstance(0L, 100L)));
        Class<?> geometryType = Class.forName("dev.exactselection.AreaGeometry", false, loader);
        Class.forName("dev.exactselection.GeometryScreenRenderer", false, loader)
                .getMethod(
                        "draw",
                        graphics,
                        frameType,
                        geometryType,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        double.class)
                .invoke(null, canvas, frame, polygon, 400, 300, 0x4059E3CE, 0xFF59E3CE, 2.0);
        java.lang.reflect.Method forEach = java.util.Arrays.stream(stateType.getMethods())
                .filter(m -> m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == java.util.function.Consumer.class
                        && m.getParameterTypes()[1].isEnum())
                .findFirst()
                .orElseThrow();
        Object range = java.util.Arrays.stream(forEach.getParameterTypes()[1].getEnumConstants())
                .filter(v -> ((Enum<?>) v).name().equals("ALL"))
                .findFirst()
                .orElseThrow();
        java.util.List<Object> elements = new java.util.ArrayList<>();
        forEach.invoke(state, (java.util.function.Consumer<Object>) elements::add, range);
        if (elements.size() != 2)
            throw new AssertionError("Remapped GUI draw did not queue both polygon fill and outline");
        System.out.println(
                "PASS: actual remapped GuiGraphics bridge and polygon fill/outline submission through Fabric");
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = new Knot(EnvType.CLIENT).init(new String[] {"--gameDir", args[0], "--version", "1.21.11"});
        Class<?> gui = Class.forName("xaero.map.gui.GuiMap", false, loader);
        Class<?> minimapHandler = Class.forName(
                "xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler", false, loader);
        // The minimap mixin config is optional, so a failed injection would otherwise go unnoticed.
        if (java.util.Arrays.stream(minimapHandler.getDeclaredMethods())
                .noneMatch(m -> m.getName().contains("exactselection$renderAreas")))
            throw new AssertionError("Minimap area overlay was not injected");
        System.out.println("PASS: minimap area overlay injected into the installed Xaero's Minimap");
        boolean bridge = java.util.Arrays.stream(gui.getInterfaces())
                .anyMatch(c -> c.getName().equals("dev.exactselection.MapBridge"));
        if (!bridge) throw new AssertionError("Selection mixin was not applied");
        Class<?> controller = Class.forName("dev.exactselection.ExactSelectionController", false, loader);
        java.util.Set<String> controllerMethods = java.util.Arrays.stream(controller.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        if (!controllerMethods.containsAll(java.util.Set.of(
                "beginVertexDrag",
                "finishPolygon",
                "insertPolygonPoint",
                "deleteVertex",
                "quickSave",
                "openWaypoints",
                "keyReleased"))) throw new AssertionError("Polygon vertex editing methods are missing");
        Class<?> host = Class.forName("dev.exactselection.MapGesture$Host", false, loader);
        Class<?> vertexDrag = Class.forName("dev.exactselection.MapGesture$VertexDrag", false, loader);
        if (vertexDrag.getDeclaredMethod("finish", host).getReturnType() != boolean.class
                || vertexDrag.getDeclaredMethod("cancel", host).getReturnType() != boolean.class)
            throw new AssertionError("Map gesture completion contract is missing");
        Class<?> saveLoad = Class.forName("xaero.map.file.MapSaveLoad", false, loader);
        Class<?> exporter = Class.forName("xaero.map.file.export.PNGExporter", false, loader);
        if (java.util.Arrays.stream(saveLoad.getInterfaces())
                .noneMatch(c -> c.getName().equals("dev.exactselection.mixin.MapSaveLoadAccessor")))
            throw new AssertionError("MapSaveLoad accessor was not applied");
        if (java.util.Arrays.stream(exporter.getInterfaces())
                .noneMatch(c -> c.getName().equals("dev.exactselection.mixin.PNGExporterAccessor")))
            throw new AssertionError("PNG exporter accessor was not applied");
        java.nio.file.Path destination = java.nio.file.Path.of(args[0]).resolve("map exports");
        Object nativeExporter =
                exporter.getConstructor(java.nio.file.Path.class).newInstance(destination);
        Class<?> exporterAccessor = Class.forName("dev.exactselection.mixin.PNGExporterAccessor", false, loader);
        java.lang.reflect.Method getDestination = exporterAccessor.getMethod("exactselection$getDestinationPath");
        java.lang.reflect.Method setDestination =
                exporterAccessor.getMethod("exactselection$setDestinationPath", java.nio.file.Path.class);
        if (!getDestination.invoke(nativeExporter).equals(destination))
            throw new AssertionError("Native Xaero export destination was not read correctly");
        setDestination.invoke(nativeExporter, destination.resolve(".raw-test"));
        setDestination.invoke(nativeExporter, destination);
        if (!getDestination.invoke(nativeExporter).equals(destination))
            throw new AssertionError("Native Xaero export destination was not restored correctly");
        Class<?> exportService = Class.forName("dev.exactselection.AreaExportService", false, loader);
        if (java.util.Arrays.stream(exportService.getDeclaredMethods())
                .noneMatch(m -> m.getName().equals("export")
                        && m.getParameterCount() == 6
                        && m.getParameterTypes()[5].getSimpleName().equals("Options")))
            throw new AssertionError("Map export does not take the export window's options");
        System.out.println("PASS: native Xaero export destination access and restoration, including paths with spaces");
        Class.forName("dev.exactselection.AreaRenderPipelines", true, loader);
        checkGuiGeometry(loader);
        if (Boolean.getBoolean("mixin.debug.export")) checkLayerOrder();
        System.out.println("PASS: Fabric discovery and World Map/Minimap mixin transformation; no game launched");
    }
}
