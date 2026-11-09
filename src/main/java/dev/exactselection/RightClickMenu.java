package dev.exactselection;

import static dev.exactselection.ExactSelectionController.place;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import dev.exactselection.SelectionPanel.Metric;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** The right-click menu on the selection or on a saved area, drawn like Xaero's own. */
final class RightClickMenu extends UiPart {
    /** Row height of the right-click menu, as in Xaero's. */
    private static final int MENU_ROW = 11;
    /** Xaero's menu colors: the title row, the row under the pointer and the line between rows. */
    private static final int TITLE_ROW = 0xFF606060, HOVERED_ROW = 0xFF323232, ROW_LINE = 0xFF323232;

    private Button quickMenuSave, quickMenuEdit, quickMenuHide, quickMenuColor, quickMenuOpacity, quickMenuDelete;
    private Button quickMenuRaise, quickMenuLower;
    private Button quickMenuClear;
    /**
     * The rows with a saved area's details, in place of the title row and above the actions; a click copies the value.
     */
    private Button quickInfoName, quickInfoGroup, quickInfoSize;

    boolean quickMenu;
    /** Where the menu opened; it stays there while it is open. */
    private int quickMenuX;

    private int quickMenuY;
    /** The saved area the menu opened on; null when it opened on the selection. */
    private String zoneId;

    RightClickMenu(ExactSelectionController ui) {
        super(ui);
    }

    /**
     * Opens the quick menu at the pointer when a right click (not a drag) lands inside the finished selection or a
     * saved area.
     */
    boolean openQuickMenu(MapBridge.Frame frame, double x, double y) {
        if (frame == null
                || ui.dialog != Dialog.NONE
                || ui.gesture != null
                || ui.dirty
                || !ui.polygonTool.draft.isEmpty()) return false;
        boolean onSelection = ui.insideBounds(frame, x, y);
        Area zone = onSelection ? null : ui.areasWindow.areaAt(frame, x, y);
        if (!onSelection && zone == null) return false;
        zoneId = zone == null ? null : zone.id();
        quickMenu = true;
        quickMenuX = (int) x;
        quickMenuY = (int) y;
        return true;
    }

    /** The saved area the menu acts on: the one it opened on, or the territory being edited. */
    Area menuArea() {
        return zoneId != null ? ui.areasWindow.area(zoneId) : ui.areasWindow.editedArea();
    }

    List<Button> quickMenuButtons() {
        if (zoneId != null) {
            Area zone = menuArea();
            List<Button> rows = new ArrayList<>(List.of(quickInfoName));
            if (zone != null && ui.groupsWindow.findGroup(zone.groupId()) != null) rows.add(quickInfoGroup);
            rows.add(quickInfoSize);
            rows.addAll(
                    zone != null && !zone.geometry().closed()
                            ? List.of(quickMenuColor, quickMenuRaise, quickMenuLower, quickMenuHide, quickMenuDelete)
                            : List.of(
                                    quickMenuEdit,
                                    quickMenuColor,
                                    quickMenuOpacity,
                                    quickMenuRaise,
                                    quickMenuLower,
                                    quickMenuHide,
                                    quickMenuDelete));
            return rows;
        }
        List<Button> items = new ArrayList<>(
                ui.areasWindow.editedArea() == null
                        ? List.of(quickMenuSave)
                        : List.of(quickMenuColor, quickMenuOpacity, quickMenuRaise, quickMenuLower));
        // A polygon has no width and length to set.
        if (!(ui.state.geometry() instanceof PolygonGeometry)) items.add(ui.selectionPanel.sizeButton);
        items.addAll(List.of(ui.selectionPanel.moveButton, ui.selectionPanel.centerButton));
        if (ui.areasWindow.editedArea() != null) items.add(quickMenuDelete);
        items.add(quickMenuClear);
        return items;
    }

    /**
     * Drawn like Xaero's own right-click menu (XaeroLib's drop-down): a white frame, a grey title row and 11-pixel
     * rows that turn grey under the pointer. The row buttons only take the clicks. On a saved area its name is the
     * title row, and it and the details below it carry a copy icon.
     */
    void renderQuickMenu(GuiGraphics g, List<Button> items, int mouseX, int mouseY) {
        Button first = items.get(0);
        int titleRows = zoneId == null ? 1 : 0;
        int x = first.getX(), w = first.getWidth(), top = first.getY() - titleRows * MENU_ROW;
        g.nextStratum();
        if (titleRows > 0) {
            g.fill(x, top, x + w, top + MENU_ROW, TITLE_ROW);
            g.drawCenteredString(ui.mc.font, ui.tr("quick_menu_title"), x + w / 2, top + 2, 0xFFFFFFFF);
        }
        for (Button item : items) {
            int y = item.getY();
            boolean hovered = item.active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + MENU_ROW;
            int idle = item == quickInfoName ? TITLE_ROW : 0xC8000000,
                    under = item == quickInfoName ? 0xFF787878 : HOVERED_ROW;
            g.fill(x, y, x + w, y + MENU_ROW, hovered ? under : idle);
            g.hLine(x, x + w - 1, y + MENU_ROW - 1, ROW_LINE);
            boolean info = item == quickInfoName || item == quickInfoGroup || item == quickInfoSize;
            g.drawCenteredString(
                    ui.mc.font,
                    item.getMessage(),
                    x + w / 2,
                    y + 2,
                    !item.active ? 0xFF808080 : info && item != quickInfoName ? 0xFFB8C4CE : 0xFFFFFFFF);
            if (info)
                PixelIcons.renderIcon(g, PixelIcons.COPY, x + w - PixelIcons.COPY[0].length() - 2, y + 1, Theme.ICON);
        }
        g.renderOutline(x - 1, top - 1, w + 2, (items.size() + titleRows) * MENU_ROW + 2, 0xFFFFFFFF);
    }

    void placeQuickMenu(boolean main) {
        if (quickMenu
                && (!main
                        || ui.gesture != null
                        || (zoneId == null ? ui.state.phase() != SelectionState.Phase.SELECTED : menuArea() == null)))
            quickMenu = false;
        if (quickMenu) {
            List<Button> items = quickMenuButtons();
            Area area = menuArea();
            if (area != null) {
                quickMenuOpacity.setMessage(ui.tr("area_new_opacity", area.opacity() + "%"));
                quickMenuRaise.active = ui.areaEdits.nextLayer(area, 1) >= 0;
                quickMenuLower.active = ui.areaEdits.nextLayer(area, -1) >= 0;
            }
            if (zoneId != null && area != null) describe(area);
            // As wide as its longest line, like Xaero's menu; the rows start under the title row.
            int titleRows = zoneId == null ? 1 : 0, w = titleRows > 0 ? ui.mc.font.width(ui.tr("quick_menu_title")) : 0;
            for (Button item : items) w = Math.max(w, ui.mc.font.width(item.getMessage()));
            // Room on both sides of the centred text for the copy icons.
            w += zoneId == null ? 8 : 2 * PixelIcons.COPY[0].length() + 10;
            int x = Math.max(3, Math.min(ui.screen.width - w - 3, quickMenuX));
            int y = Math.max(3, Math.min(ui.screen.height - 3 - (items.size() + titleRows) * MENU_ROW, quickMenuY));
            for (int i = 0; i < items.size(); i++) {
                place(items.get(i), x, y + (i + titleRows) * MENU_ROW, w, MENU_ROW);
                items.get(i).visible = true;
            }
        }
    }

    private void describe(Area area) {
        quickInfoName.setMessage(Component.literal(ui.mc.font.plainSubstrByWidth(area.name(), 160)));
        Group group = ui.groupsWindow.findGroup(area.groupId());
        if (group != null)
            quickInfoGroup.setMessage(ui.tr("area_group_button", ui.mc.font.plainSubstrByWidth(group.name(), 140)));
        AreaGeometry shape = area.geometry();
        quickInfoSize.setMessage(
                shape.closed()
                        ? ui.tr("area", SelectionDialogs.formatNumber(shape.area()))
                        : ui.tr("route_length", SelectionPanel.formatLength(shape.perimeter())));
    }

    private void moveLayer(int direction) {
        Area area = menuArea();
        if (area != null) ui.areaEdits.moveLayer(area, direction);
    }

    void createMenuWidgets() {
        quickInfoName = ui.button("quick_menu_title", () -> {
            Area area = menuArea();
            if (area != null) ui.selectionPanel.copyToClipboard(area.name());
        });
        quickInfoGroup = ui.button("group_none", () -> {
            Area area = menuArea();
            Group group = area == null ? null : ui.groupsWindow.findGroup(area.groupId());
            if (group != null) ui.selectionPanel.copyToClipboard(group.name());
        });
        // Rounded like the Areas window's copy buttons, by the same setting.
        quickInfoSize = ui.button("area_fill", () -> {
            Area area = menuArea();
            if (area != null)
                ui.selectionPanel.copyMetric(
                        area.geometry().closed() ? Metric.AREA : Metric.PERIMETER, area.geometry(), area.bounds());
        });
        quickMenuSave = ui.button("quick_menu_save", () -> {
            quickMenu = false;
            ui.selectionPanel.quickSave();
        });
        quickMenuColor = ui.button("area_color", ui.colorWindows::openQuickColor);
        quickMenuOpacity = ui.button("area_new_opacity", () -> {
            Area area = menuArea();
            if (area != null) ui.areaEdits.changeArea(area, a -> a.withOpacity(AreasWindow.nextOpacity(a.opacity())));
        });
        quickMenuRaise = ui.button("quick_menu_raise", () -> moveLayer(1));
        quickMenuLower = ui.button("quick_menu_lower", () -> moveLayer(-1));
        quickMenuEdit = ui.button("quick_menu_edit", () -> {
            quickMenu = false;
            ui.areasWindow.loadArea(menuArea());
        });
        quickMenuHide = ui.button("quick_menu_hide", () -> {
            Area area = menuArea();
            quickMenu = false;
            if (area != null && ui.areaEdits.changeArea(area, a -> a.withVisible(false))) ui.say("areas_hidden", 1);
        });
        quickMenuDelete = ui.button("quick_menu_delete", () -> {
            Area area = menuArea();
            quickMenu = false;
            if (area == null) return;
            ui.areasWindow.askDeleteFromMenu(area);
        });
        quickMenuClear = ui.button("quick_menu_clear", () -> {
            quickMenu = false;
            ui.reset();
        });
    }
}
