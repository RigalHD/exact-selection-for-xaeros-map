package dev.exactselection.mixin;

import dev.exactselection.MinimapAreaOverlay;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;

@Pseudo
@Mixin(targets = "xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler", remap = false)
public abstract class MinimapOverlayMixin {
    @Shadow
    private double ps;

    @Shadow
    private double pc;

    @Shadow
    private double zoom;

    @Shadow
    private int halfViewW;

    @Shadow
    private int halfViewH;

    @Shadow
    private boolean circle;

    @Inject(method = "beforeRender", at = @At("HEAD"))
    private void exactselection$renderAreas(
            MinimapElementRenderInfo info, MultiBufferSource.BufferSource bufferSource, CallbackInfo ci) {
        MinimapAreaOverlay.render(this, info.renderPos, info.mapDimension, zoom, ps, pc, halfViewW, halfViewH, circle);
    }
}
