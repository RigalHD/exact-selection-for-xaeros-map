package dev.exactselection;

import net.minecraft.client.gui.render.state.GuiRenderState;

/** Gives the renderer the GUI render state of a GuiGraphics; implemented by GuiGraphicsMixin. */
public interface GuiGraphicsBridge {
    GuiRenderState exactselection$renderState();
}
