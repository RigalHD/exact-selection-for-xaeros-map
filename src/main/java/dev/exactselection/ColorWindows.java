package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SavedAreaStore.Area;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import static dev.exactselection.ExactSelectionController.HEX_FIELD;
import static dev.exactselection.ExactSelectionController.place;

/** The color window with its swatches and the full color picker. */
final class ColorWindows extends UiPart {
    Button colorHexApply;
    Dialog colorReturn = Dialog.AREAS;
    /** What the pointer drags in the full color picker while the left button is held. */
    enum PickerDrag { NONE, SQUARE, HUE }
    /** The full color picker: the window it returns to, then its hue, saturation and brightness. */
    Dialog pickerReturn = Dialog.AREA_NAME;
    private double pickerHue;
    private double pickerSat;
    private double pickerValue;
    PickerDrag pickerDrag = PickerDrag.NONE;

    ColorWindows(ExactSelectionController ui) { super(ui); }

    /** The color window for the area of the right-click menu; a swatch or hex applies at once. */
    void openQuickColor() {
        Area area = ui.rightClickMenu.menuArea();
        ui.rightClickMenu.quickMenu = false;
        if (area == null || ui.loadFailed) return;
        colorReturn = Dialog.NONE;
        ui.dialog = Dialog.COLOR;
        ui.setDialogValue(HEX_FIELD, AreaPalette.format(area.color()));
        ui.message = ""; ui.focus(null);
    }

    void openColorDialog(Dialog returnTo) {
        if (returnTo == Dialog.AREAS && (ui.areasWindow.selectedAreas().isEmpty() || ui.loadFailed)) return;
        if (returnTo == Dialog.WAYPOINTS && !ui.waypointsWindow.validatePointSnapshot()) return;
        if (returnTo == Dialog.WAYPOINTS && ui.waypointsWindow.filteredChosenPoints().isEmpty()) return;
        colorReturn = returnTo;
        ui.dialog = Dialog.COLOR;
        Area area = ui.areasWindow.selectedArea();
        if (area == null && !ui.areasWindow.selectedAreas().isEmpty()) area = ui.areasWindow.selectedAreas().get(0);
        ui.setDialogValue(HEX_FIELD, AreaPalette.format(ui.areaEdits.areaDraft.color != null ? ui.areaEdits.areaDraft.color
                : area == null ? AreaPalette.DEFAULT : area.color()));
        ui.message = ""; ui.focus(null);
    }

    void applyHexColor() {
        int color = AreaPalette.parse(ui.dialogFields[HEX_FIELD].getValue());
        if (color < 0) { ui.say("color_invalid"); return; }
        applyColor(color);
    }

    private void applyColor(int color) {
        if (colorReturn == Dialog.WAYPOINTS) {
            ui.waypointsWindow.editPoints(null, color);
            return;
        }
        if (colorReturn == Dialog.NONE) {
            Area area = ui.rightClickMenu.menuArea();
            ui.dialog = Dialog.NONE;
            if (area != null) ui.areaEdits.changeArea(area, a -> a.withColor(color));
            ui.focus(null);
            return;
        }
        if (ui.loadFailed) return;
        ui.areaEdits.areaDraft.color = color;
        ui.dialog = Dialog.AREAS; ui.message = ""; ui.focus(null);
        ui.areaEdits.stage(AutosaveSettings.Field.COLOR);
    }

    private int[] paletteColors() {
        if (colorReturn != Dialog.WAYPOINTS) return AreaPalette.COLORS;
        int[] colors = new int[WaypointAccess.COLOR_COUNT];
        for (int i = 0; i < colors.length; i++) colors[i] = ui.waypoints.colorRgb(i);
        return colors;
    }

    /** The dialog's side margin; the swatch grid spans the same width as its fields. */
    private int swatchMargin() { return ui.dialog == Dialog.AREA_NAME ? 16 : 12; }

    int colorSampleWidth(int width) {
        return (width - 2 * swatchMargin() - 4 * (AreaPalette.COLUMNS - 1)) / AreaPalette.COLUMNS;
    }

    /** Left edge of a swatch column; the rounding remainder is split evenly on both sides. */
    int swatchX(int x, int w, int column) {
        int sw = colorSampleWidth(w), inner = w - 2 * swatchMargin();
        int spare = inner - sw * AreaPalette.COLUMNS - 4 * (AreaPalette.COLUMNS - 1);
        return x + swatchMargin() + spare / 2 + column * (sw + 4);
    }

    /** Top of the swatch grid: below the title in the color window, below group and fill in the new-area window. */
    private int swatchTop(int y) { return y + (ui.dialog == Dialog.AREA_NAME ? 98 : 34); }

    /** The swatch under the pointer, or -1. */
    int swatchAt(int x, int y, int w, int count, double mouseX, double mouseY) {
        int sw = colorSampleWidth(w);
        for (int i = 0; i < count; i++) {
            int sx = swatchX(x, w, i % AreaPalette.COLUMNS), sy = swatchTop(y) + (i / AreaPalette.COLUMNS) * 22;
            if (mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + 18) return i;
        }
        return -1;
    }

    void renderSwatches(GuiGraphics g, int x, int y, int w, int[] colors, int current) {
        int sw = colorSampleWidth(w);
        for (int i = 0; i < colors.length; i++) {
            int sx = swatchX(x, w, i % AreaPalette.COLUMNS), sy = swatchTop(y) + (i / AreaPalette.COLUMNS) * 22;
            g.fill(sx, sy, sx + sw, sy + 18, 0xFF000000 | colors[i]);
            g.renderOutline(sx, sy, sw, 18, current == colors[i] ? -1 : Theme.BORDER);
        }
    }

    /** The color window's preview, between the hex field and Apply. */
    private static int colorPreviewX(int x, int w) { return x + w - 146; }

    void renderColorDialog(GuiGraphics g, int x, int y, int w, int h) {
        List<WaypointAccess.Point> points = ui.waypointsWindow.filteredChosenPoints();
        int current = colorReturn == Dialog.WAYPOINTS
                ? points.isEmpty() ? -1 : ui.waypoints.colorRgb(points.get(0).color())
                : AreaPalette.parse(ui.dialogFields[HEX_FIELD].getValue());
        renderSwatches(g, x, y, w, paletteColors(), current);
        if (colorReturn != Dialog.WAYPOINTS) {
            int px = colorPreviewX(x, w);
            g.fill(px, y + 110, px + 40, y + 128, current < 0 ? 0xFF000000 : 0xFF000000 | current);
            g.renderOutline(px, y + 110, 40, 18, current < 0 ? Theme.INVALID : -1);
        }
        if (!ui.message.isEmpty()) g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.message, w - 24),
                x + 12, y + (colorReturn == Dialog.WAYPOINTS ? 86 : 98), Theme.WARNING);
    }

    /** Opens the full color picker on {@code color}; Apply hands the color back to {@code returnTo}. */
    void openPicker(Dialog returnTo, int color) {
        double[] hsv = AreaPalette.toHsv(color);
        pickerHue = hsv[0]; pickerSat = hsv[1]; pickerValue = hsv[2];
        pickerReturn = returnTo; pickerDrag = PickerDrag.NONE;
        ui.dialog = Dialog.PICKER;
        ui.message = ""; ui.focus(null);
    }

    private int pickerColor() { return AreaPalette.hsv(pickerHue, pickerSat, pickerValue); }

    void applyPicker() {
        int color = pickerColor();
        ui.dialog = pickerReturn; pickerDrag = PickerDrag.NONE;
        ui.setDialogValue(HEX_FIELD, AreaPalette.format(color));
        // The color window applies a choice at once, like a swatch.
        if (pickerReturn == Dialog.COLOR) { applyColor(color); return; }
        ui.newAreaWindow.newAreaColor = color;
        ui.focus(null);
    }

    /** Saturation and brightness: {x, y, width, height}; the hue bar stands to its right. */
    private static Rect pickerSquare(int x, int y, int w, int h) { return new Rect(x + 16, y + 30, w - 58, h - 92); }

    private static Rect pickerHueBar(int x, int y, int w, int h) { return new Rect(x + w - 34, y + 30, 18, h - 92); }

    void dragPicker(double mouseX, double mouseY) {
        Rect r = ui.windows.dialogRect();
        Rect square = pickerSquare(r.x(), r.y(), r.width(), r.height()), hue = pickerHueBar(r.x(), r.y(), r.width(), r.height());
        if (pickerDrag == PickerDrag.SQUARE) {
            pickerSat = Math.max(0, Math.min(1, (mouseX - square.x()) / Math.max(1, square.width() - 1)));
            pickerValue = 1 - Math.max(0, Math.min(1, (mouseY - square.y()) / Math.max(1, square.height() - 1)));
        } else if (pickerDrag == PickerDrag.HUE) {
            pickerHue = Math.max(0, Math.min(1, (mouseY - hue.y()) / Math.max(1, hue.height() - 1)));
        }
    }

    void renderPicker(GuiGraphics g, int x, int y, int w, int h) {
        Rect square = pickerSquare(x, y, w, h), hue = pickerHueBar(x, y, w, h);
        // Each column runs from its saturation at full brightness down to black.
        for (int i = 0; i < square.width(); i++)
            g.fillGradient(square.x() + i, square.y(), square.x() + i + 1, square.y() + square.height(),
                    0xFF000000 | AreaPalette.hsv(pickerHue, i / (double) Math.max(1, square.width() - 1), 1), 0xFF000000);
        for (int j = 0; j < hue.height(); j++)
            g.fill(hue.x(), hue.y() + j, hue.x() + hue.width(), hue.y() + j + 1, 0xFF000000 | AreaPalette.hsv(j / (double) hue.height(), 1, 1));
        g.renderOutline(square.x() - 1, square.y() - 1, square.width() + 2, square.height() + 2, Theme.BORDER);
        g.renderOutline(hue.x() - 1, hue.y() - 1, hue.width() + 2, hue.height() + 2, Theme.BORDER);
        int mx = square.x() + (int) Math.round(pickerSat * (square.width() - 1));
        int my = square.y() + (int) Math.round((1 - pickerValue) * (square.height() - 1));
        g.renderOutline(mx - 3, my - 3, 7, 7, pickerValue > 0.5 ? 0xFF000000 : -1);
        int hy = hue.y() + (int) Math.round(pickerHue * (hue.height() - 1));
        g.renderOutline(hue.x() - 2, hy - 2, hue.width() + 4, 5, -1);
        int color = pickerColor(), py = y + h - 54;
        g.fill(x + 16, py, x + 56, py + 18, 0xFF000000 | color);
        g.renderOutline(x + 16, py, 40, 18, -1);
        g.drawString(ui.mc.font, AreaPalette.format(color), x + 64, py + 5, -1);
    }

    void clickColorDialog(MouseButtonEvent event, int x, int y, int w, int h) {
        int px = colorPreviewX(x, w);
        if (colorReturn != Dialog.WAYPOINTS && event.x() >= px && event.x() < px + 40
                && event.y() >= y + 110 && event.y() < y + 128) {
            int typed = AreaPalette.parse(ui.dialogFields[HEX_FIELD].getValue());
            openPicker(Dialog.COLOR, typed < 0 ? AreaPalette.DEFAULT : typed);
            return;
        }
        int[] colors = paletteColors();
        int i = swatchAt(x, y, w, colors.length, event.x(), event.y());
        if (i >= 0) applyColor(colorReturn == Dialog.WAYPOINTS ? i : colors[i]);
    }

    void clickPicker(MouseButtonEvent event, int x, int y, int w, int h) {
        Rect square = pickerSquare(x, y, w, h), hue = pickerHueBar(x, y, w, h);
        pickerDrag = square.contains(event.x(), event.y()) ? PickerDrag.SQUARE
                : hue.contains(event.x(), event.y()) ? PickerDrag.HUE : PickerDrag.NONE;
        dragPicker(event.x(), event.y());
    }

    void layoutColorDialog(int x, int y, int w, int h, int buttonY) {
        // The field leaves room for the preview, which opens the full picker.
        place(ui.dialogFields[HEX_FIELD], x + 12, y + 110, colorPreviewX(x, w) - 4 - (x + 12), 18);
        place(colorHexApply, x + w - 102, y + 110, 90, 18);
        place(ui.modalCancel, x + w / 2 - 55, buttonY, 110, 18);
    }

    void createColorWidgets() {
        ui.dialogFields[HEX_FIELD].setResponder(value -> {
            if (ui.syncing) return;
            // The new-area window takes a typed color as soon as it is complete.
            int color = AreaPalette.parse(value);
            if (ui.dialog == Dialog.AREA_NAME && color >= 0) ui.newAreaWindow.newAreaColor = color;
        });
        colorHexApply = ui.button("color_apply", "color_hex_tooltip", this::applyHexColor);
    }
}
