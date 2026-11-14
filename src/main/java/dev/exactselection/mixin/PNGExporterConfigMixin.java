package dev.exactselection.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.exactselection.AreaExportConfigOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import xaero.lib.common.config.option.ConfigOption;
import xaero.lib.common.config.single.SingleConfigManager;
import xaero.map.file.export.PNGExporter;

/**
 * Lets one export of ours use its own settings: every setting the exporter reads goes through {@link
 * AreaExportConfigOverride}.
 */
@Pseudo
@Mixin(value = PNGExporter.class, remap = false)
public abstract class PNGExporterConfigMixin {
    @WrapOperation(
            method = "export",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lxaero/lib/common/config/single/SingleConfigManager;getEffective(Lxaero/lib/common/config/option/ConfigOption;)Ljava/lang/Object;"))
    private Object exactselection$overrideConfig(
            SingleConfigManager<?> manager, ConfigOption<?> option, Operation<Object> original) {
        return AreaExportConfigOverride.override(option, original.call(manager, option));
    }
}
