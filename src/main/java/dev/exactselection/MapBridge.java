package dev.exactselection;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;

/** Implemented on GuiMap by the mixin; no cached texture/block coordinates. */
public interface MapBridge {
    record Context(Object world, String worldId, ResourceKey<Level> dimension, String subworld) {}

    record Frame(
            Context context,
            double cameraX,
            double cameraZ,
            double scale,
            double guiScale,
            int pixelWidth,
            int pixelHeight) {
        public double worldX(double x) {
            return (x * guiScale - pixelWidth / 2) / scale + cameraX;
        }

        public double worldZ(double y) {
            return (y * guiScale - pixelHeight / 2) / scale + cameraZ;
        }

        public double screenX(double x) {
            return ((x - cameraX) * scale + pixelWidth / 2) / guiScale;
        }

        public double screenY(double z) {
            return ((z - cameraZ) * scale + pixelHeight / 2) / guiScale;
        }
    }

    Frame exactselection$frame();

    MapProcessor exactselection$processor();

    void exactselection$pan(double dx, double dy);

    void exactselection$clearNativeInput();
    /** Glides the map to a world point and zooms to {@code pixelsPerBlock}, within Xaero's zoom limits. */
    void exactselection$moveCamera(double x, double z, double pixelsPerBlock);

    void exactselection$closeMap();
}
