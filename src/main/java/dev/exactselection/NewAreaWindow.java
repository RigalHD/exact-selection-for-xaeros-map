package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.ExactSelectionController.DialogField;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import static dev.exactselection.ExactSelectionController.place;

/** The window that names a new area or route, with the style the next one gets. */
final class NewAreaWindow extends UiPart {
    Button newAreaGroup, newAreaOpacityButton;
    Dialog areaNameReturn = Dialog.AREAS;
    /** The ruler's route being named; null while the name window is for the selection. */
    PolylineGeometry routeToSave;
    private String newAreaGroupId;
    /** Style for the next saved area; kept between saves so a series of areas can share it. */
    int newAreaColor = AreaPalette.DEFAULT;
    private int newAreaOpacity = 25;

    NewAreaWindow(ExactSelectionController ui) { super(ui); }

    void openAreaNameDialog() {
        if (ui.state.phase() != SelectionState.Phase.SELECTED || ui.dirty || ui.loadFailed) return;
        routeToSave = null;
        showNameDialog("area_default_name");
    }

    void openRouteNameDialog(PolylineGeometry route) {
        if (ui.loadFailed) return;
        routeToSave = route;
        areaNameReturn = Dialog.NONE;
        showNameDialog("route_default_name");
    }

    private void showNameDialog(String defaultNameKey) {
        ui.dialog = Dialog.AREA_NAME;
        newAreaGroupId = null;
        ui.setDialogValue(DialogField.NEW_AREA, defaultName(defaultNameKey));
        ui.setDialogValue(DialogField.HEX, AreaPalette.format(newAreaColor));
        ui.message = "";
        ui.focus(ui.field(DialogField.NEW_AREA));
    }

    /** The first free numbered name; counting the areas alone repeats a name after a deletion. */
    private String defaultName(String key) {
        List<Area> areas = ui.areas();
        String name = "";
        for (int n = areas.size() + 1; n <= 2 * areas.size() + 1; n++) {
            name = ui.tr(key, n).getString();
            String candidate = name;
            if (areas.stream().noneMatch(a -> a.name().equals(candidate))) break;
        }
        return name;
    }

    void saveArea() {
        boolean route = routeToSave != null;
        SelectionBounds b = ui.state.bounds();
        if (!route && (b == null || ui.dirty || ui.state.phase() != SelectionState.Phase.SELECTED)) return;
        ui.areaEdits.flushDraft();
        String name = ui.field(DialogField.NEW_AREA).getValue().trim();
        if (name.isEmpty()) name = defaultName(route ? "route_default_name" : "area_default_name");
        AreaGeometry geometry = route ? routeToSave : ui.state.geometry() == null ? new RectangleGeometry(b) : ui.state.geometry();
        Area area = SavedAreaStore.create(name, geometry, newAreaColor, newAreaOpacity,
                ui.groupsWindow.findGroup(newAreaGroupId) == null ? null : newAreaGroupId);
        List<Area> areas = new ArrayList<>(ui.areas()); areas.add(area);
        if (!ui.areaEdits.changeAreas(ui.areaEdits.areaState(areas, ui.groups()), "area_save")) return;
        ui.areasWindow.selectedAreaIds.clear(); ui.areasWindow.selectedAreaIds.add(area.id()); ui.areasWindow.areaId = area.id();
        ui.setDialogValue(DialogField.AREA_NAME, area.name());
        ui.dialog = areaNameReturn;
        if (ui.dialog == Dialog.AREAS) {
            List<Area> shown = ui.areasWindow.displayedAreas();
            int index = shown.indexOf(area);
            // Scroll so the new row is visible; a row the search hides leaves the list where it was.
            if (index >= 0)
                ui.areasWindow.areaScroll = Math.max(0, Math.min(index, shown.size() - ui.areasWindow.visibleAreaDialogRows(ui.windows.dialogRect().height())));
        }
        // The saved shape now lives in the list; clearing it (its own undo step) frees the map for the next area.
        if (route) ui.rulerTool.clearRuler(); else ui.reset();
        routeToSave = null;
        ui.say(route ? "route_saved" : areaNameReturn == Dialog.NONE ? "area_saved_named" : "area_created", name);
        ui.focus(null);
    }

    private void cycleNewAreaGroup() {
        List<Group> groups = ui.groups();
        int index = -1;
        for (int i = 0; i < groups.size(); i++) if (Objects.equals(groups.get(i).id(), newAreaGroupId)) index = i;
        newAreaGroupId = index + 1 < groups.size() ? groups.get(index + 1).id() : null;
    }

    void clickAreaNameDialog(MouseButtonEvent event, int x, int y, int w, int h) {
        // The preview of the chosen color opens the full picker.
        int px = ui.colorWindows.swatchX(x, w, AreaPalette.COLUMNS - 1);
        if (event.x() >= px && event.x() < px + ui.colorWindows.colorSampleWidth(w) && event.y() >= y + 170 && event.y() < y + 188) {
            ui.colorWindows.openPicker(Dialog.AREA_NAME, newAreaColor);
            return;
        }
        int i = ui.colorWindows.swatchAt(x, y, w, AreaPalette.COLORS.length, event.x(), event.y());
        if (i >= 0) {
            newAreaColor = AreaPalette.COLORS[i];
            ui.setDialogValue(DialogField.HEX, AreaPalette.format(newAreaColor));
            ui.focus(null);
        }
    }

    void createNewAreaWidgets() {
        newAreaGroup = ui.button("group_none", "area_new_group_tooltip", this::cycleNewAreaGroup);
        newAreaOpacityButton = ui.button("area_fill", "area_new_opacity_tooltip", () -> newAreaOpacity = AreasWindow.nextOpacity(newAreaOpacity));
    }

    void updateNewAreaWidgets() {
        Group newGroup = ui.groupsWindow.findGroup(newAreaGroupId);
        newAreaGroup.setMessage(ui.tr("area_group_button", newGroup == null ? ui.tr("group_none").getString() : newGroup.name()));
        newAreaOpacityButton.setMessage(ui.tr("area_new_opacity", newAreaOpacity + "%"));
    }

    void layoutNewArea(int x, int y, int w) {
        int half = (w - 35) / 2;
        place(newAreaGroup, x + 16, y + 72, half, 18);
        place(newAreaOpacityButton, x + 19 + half, y + 72, w - 35 - half, 18);
        // Custom color: the field ends where the preview takes the last palette column.
        int fieldX = x + 16 + ui.mc.font.width(ui.tr("area_custom_color")) + 6;
        place(ui.field(DialogField.HEX), fieldX, y + 170, ui.colorWindows.swatchX(x, w, AreaPalette.COLUMNS - 1) - 4 - fieldX, 18);
    }

    void renderNewArea(GuiGraphics g, int x, int y, int w) {
        ui.colorWindows.renderSwatches(g, x, y, w, AreaPalette.COLORS, newAreaColor);
        g.drawString(ui.mc.font, ui.tr("area_custom_color"), x + 16, y + 175, Theme.TEXT_MUTED);
        int px = ui.colorWindows.swatchX(x, w, AreaPalette.COLUMNS - 1), sw = ui.colorWindows.colorSampleWidth(w);
        String hex = ui.field(DialogField.HEX).getValue().trim();
        g.fill(px, y + 170, px + sw, y + 188, 0xFF000000 | newAreaColor);
        // A red frame marks text that is not a color yet; the preview keeps the last valid one.
        g.renderOutline(px, y + 170, sw, 18, !hex.isEmpty() && AreaPalette.parse(hex) < 0 ? Theme.INVALID : -1);
    }
}
