package dev.exactselection.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.export.PNGExporter;

/** Reaches Xaero's PNG exporter and its "export in progress" flag. */
@Pseudo
@Mixin(value = MapSaveLoad.class, remap = false)
public interface MapSaveLoadAccessor {
    @Accessor("pngExporter")
    PNGExporter exactselection$getPngExporter();

    @Accessor("exporting")
    boolean exactselection$isExporting();

    @Accessor("exporting")
    void exactselection$setExporting(boolean exporting);
}
