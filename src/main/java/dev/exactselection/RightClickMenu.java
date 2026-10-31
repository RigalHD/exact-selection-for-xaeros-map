package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SavedAreaStore.Area;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import static dev.exactselection.ExactSelectionController.place;

/** The right-click menu on the selection or on a saved area, drawn like Xaero's own. */
final class RightClickMenu extends UiPart {
    /** Row height of the right-click menu, as in Xaero's. */
    private static final int MENU_ROW = 11;
    /** It stays where it opened until an action or a click elsewhere. */
    private Button quickMenuSave, quickMenuEdit, quickMenuHide, quickMenuColor, quickMenuOpacity, quickMenuDelete;
    private Button quickMenuClear;
    boolean quickMenu;
    private int quickMenuX;
    private int quickMenuY;
    /** The saved area the menu opened on; null when it opened on the selection. */
    private String zoneId;

    RightClickMenu(ExactSelectionController ui) { super(ui); }

    /** Opens the quick menu at the pointer when a right click (not a drag) lands inside the finished selection or a saved area. */
    boolean openQuickMenu(MapBridge.Frame frame, double x, double y) {
        if (frame == null || ui.dialog != Dialog.NONE || ui.gesture != null || ui.dirty || !ui.polygonTool.draft.isEmpty()) return false;
        boolean onSelection = ui.insideBounds(frame, x, y);
        Area zone = onSelection ? null : ui.areasWindow.areaAt(frame, x, y);
        if (!onSelection && zone == null) return false;
        zoneId = zone == null ? null : zone.id();
        quickMenu = true;
        quickMenuX = (int) x; quickMenuY = (int) y;
        return true;
    }

    /** The saved area the menu acts on: the one it opened on, or the territory being edited. */
    Area menuArea() { return zoneId != null ? ui.areasWindow.area(zoneId) : ui.areasWindow.editedArea(); }

    private Component title() {
        Area zone = zoneId == null ? null : menuArea();
        return zone == null ? ui.tr("quick_menu_title") : Component.literal(ui.mc.font.plainSubstrByWidth(zone.name(), 160));
    }

    List<Button> quickMenuButtons() {
        if (zoneId != null) {
            Area zone = menuArea();
            return zone != null && !zone.geometry().closed() ? List.of(quickMenuColor, quickMenuHide, quickMenuDelete)
                    : List.of(quickMenuEdit, quickMenuColor, quickMenuOpacity, quickMenuHide, quickMenuDelete);
        }
        List<Button> items = new ArrayList<>(ui.areasWindow.editedArea() == null ? List.of(quickMenuSave) : List.of(quickMenuColor, quickMenuOpacity));
        // A polygon has no width and length to set.
        if (!(ui.state.geometry() instanceof PolygonGeometry)) items.add(ui.selectionPanel.sizeButton);
        items.addAll(List.of(ui.selectionPanel.moveButton, ui.selectionPanel.centerButton));
        if (ui.areasWindow.editedArea() != null) items.add(quickMenuDelete);
        items.add(quickMenuClear);
        return items;
    }

    /**
     * Drawn like Xaero's own right-click menu (XaeroLib's drop-down): a white frame, a grey title row and 11-pixel
     * rows that turn grey under the pointer. The row buttons only take the clicks.
     */
    void renderQuickMenu(GuiGraphics g, List<Button> items, int mouseX, int mouseY) {
        Button first = items.get(0);
        int x = first.getX(), w = first.getWidth(), top = first.getY() - MENU_ROW;
        g.nextStratum();
        g.fill(x, top, x + w, top + MENU_ROW, 0xFF606060);
        g.drawCenteredString(ui.mc.font, title(), x + w / 2, top + 2, 0xFFFFFFFF);
        for (Button item : items) {
            int y = item.getY();
            boolean hovered = item.active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + MENU_ROW;
            g.fill(x, y, x + w, y + MENU_ROW, hovered ? 0xFF323232 : 0xC8000000);
            g.hLine(x, x + w - 1, y + MENU_ROW - 1, 0xFF323232);
            g.drawCenteredString(ui.mc.font, item.getMessage(), x + w / 2, y + 2, item.active ? 0xFFFFFFFF : 0xFF808080);
        }
        g.renderOutline(x - 1, top - 1, w + 2, (items.size() + 1) * MENU_ROW + 2, 0xFFFFFFFF);
    }

    void placeQuickMenu(boolean main) {
        if (quickMenu && (!main || ui.gesture != null
                || (zoneId == null ? ui.state.phase() != SelectionState.Phase.SELECTED : menuArea() == null))) quickMenu = false;
        if (quickMenu) {
            List<Button> items = quickMenuButtons();
            Area area = menuArea();
            if (area != null) quickMenuOpacity.setMessage(ui.tr("area_new_opacity", area.opacity() + "%"));
            // As wide as its longest line, like Xaero's menu; the rows start under the title row.
            int w = ui.mc.font.width(title());
            for (Button item : items) w = Math.max(w, ui.mc.font.width(item.getMessage()));
            w += 8;
            int x = Math.max(3, Math.min(ui.screen.width - w - 3, quickMenuX));
            int y = Math.max(3, Math.min(ui.screen.height - 3 - (items.size() + 1) * MENU_ROW, quickMenuY));
            for (int i = 0; i < items.size(); i++) {
                place(items.get(i), x, y + (i + 1) * MENU_ROW, w, MENU_ROW);
                items.get(i).visible = true;
            }
        }
    }

    void createMenuWidgets() {
        quickMenuSave = ui.button("quick_menu_save", () -> { quickMenu = false; ui.selectionPanel.quickSave(); });
        quickMenuColor = ui.button("area_color", ui.colorWindows::openQuickColor);
        quickMenuOpacity = ui.button("area_new_opacity", () -> {
            Area area = menuArea();
            if (area != null) ui.areaEdits.changeArea(area, a -> a.withOpacity(AreasWindow.nextOpacity(a.opacity())));
        });
        quickMenuEdit = ui.button("quick_menu_edit", () -> { quickMenu = false; ui.areasWindow.loadArea(menuArea()); });
        quickMenuHide = ui.button("quick_menu_hide", () -> {
            Area area = menuArea();
            quickMenu = false;
            if (area != null && ui.areaEdits.changeArea(area, a -> a.withVisible(false))) ui.say("areas_hidden", 1);
        });
        quickMenuDelete = ui.button("quick_menu_delete", () -> {
            Area area = menuArea();
            quickMenu = false;
            if (area == null) return;
            // The Areas window's confirmation; either answer returns to the map.
            ui.areasWindow.selectedAreaIds.clear(); ui.areasWindow.selectedAreaIds.add(area.id()); ui.areasWindow.areaId = area.id();
            ui.areaEdits.areaDraft.clear();
            ui.dialog = Dialog.AREAS;
            ui.areasWindow.areaDeleteConfirmation = ui.areasWindow.areaDeleteFromMenu = true;
            ui.message = "";
            ui.focus(ui.confirm);
        });
        quickMenuClear = ui.button("quick_menu_clear", () -> { quickMenu = false; ui.reset(); });
    }
}
