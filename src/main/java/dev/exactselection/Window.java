package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * One window of the tool, as {@link Windows} sees it: its size and title, where Cancel leads, and what it shows,
 * places, draws and does with a click or the wheel. Each part of the interface describes its own windows.
 */
abstract class Window {
    /** The size the window wants; {@link Windows#dialogRect} fits it to the screen. */
    abstract int width();

    abstract int height();

    /** The translation key of the title. */
    abstract String title();

    /** A list window has its title on the left, beside its buttons; the others centre it. */
    boolean titleOnLeft() {
        return false;
    }

    /** The window Cancel and Esc lead to. */
    Dialog returnTo() {
        return Dialog.NONE;
    }

    /** Makes the window's widgets visible; everything is hidden before each call. */
    void show() {}

    void layout(Rect r, int buttonY) {}

    /** Draws the body under the title. */
    void render(GuiGraphics g, Rect r) {}

    /** A left click that no widget took. */
    void click(MouseButtonEvent event, Rect r) {}

    /** One wheel step; {@code height} is the window's height. */
    void scroll(int step, int height) {}
}
