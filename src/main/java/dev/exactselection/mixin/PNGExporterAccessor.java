package dev.exactselection.mixin;

import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.file.export.PNGExporter;

@Pseudo
@Mixin(value = PNGExporter.class, remap = false)
public interface PNGExporterAccessor {
    @Accessor("destinationPath") Path exactselection$getDestinationPath();
    @Accessor("destinationPath") void exactselection$setDestinationPath(Path path);
}
