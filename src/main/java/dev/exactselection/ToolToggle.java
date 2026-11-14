package dev.exactselection;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The switch that turns the tool on, under Xaero's gear: a pixel icon with no button behind it, which rises a pixel and
 * dims under the pointer, as Xaero's own map buttons do.
 */
final class ToolToggle extends Button {
    /** Xaero's tints for its map buttons: resting, under the pointer and unavailable. */
    private static final int XAERO_ICON = 0xFFFCFCFC, XAERO_HOVER = 0xFFE6E6E6, XAERO_INACTIVE = 0xFF404040;
    /** {@link Theme#ACCENT} dimmed under the pointer as Xaero dims its white. */
    private static final int ACCENT_HOVER = 0xFF51CFBC;

    private final ExactSelectionController ui;

    ToolToggle(ExactSelectionController ui) {
        super(0, 0, 0, 0, Component.empty(), button -> ui.toggle(), DEFAULT_NARRATION);
        this.ui = ui;
        setTooltip(Tooltip.create(ui.tr("title")));
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float delta) {
        boolean on = ui.enabled(), raised = active && isHovered();
        String[] icon = on ? PixelIcons.TOOL_ON : PixelIcons.TOOL;
        int color = !active
                ? XAERO_INACTIVE
                : on ? (raised ? ACCENT_HOVER : Theme.ACCENT) : raised ? XAERO_HOVER : XAERO_ICON;
        PixelIcons.renderIcon(
                g,
                icon,
                getX() + (getWidth() - icon[0].length()) / 2,
                getY() + (getHeight() - icon.length) / 2 - (raised ? 1 : 0),
                color);
    }
}
