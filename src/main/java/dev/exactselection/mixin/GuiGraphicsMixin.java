package dev.exactselection.mixin;

import dev.exactselection.GuiGraphicsBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes the GUI render state, which shapes are submitted to. */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin implements GuiGraphicsBridge {
    @Shadow
    @Final
    private GuiRenderState guiRenderState;

    @Override
    public GuiRenderState exactselection$renderState() {
        return guiRenderState;
    }
}
