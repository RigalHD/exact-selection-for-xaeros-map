package dev.exactselection;

import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.SelectionPanel.formatLength;
import static dev.exactselection.Theme.ACCENT;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.ExactSelectionController.DialogField;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import dev.exactselection.SavedAreaStore.State;
import dev.exactselection.SelectionPanel.Metric;
import dev.exactselection.SelectionPanel.MetricLine;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/** The Areas window: its list with search and checked rows, the details of the checked area and its buttons. */
final class AreasWindow extends UiPart {
    private Button areaSave, areaLoad;
    Button areaShow;
    private Button areaRestore, areaDelete, areaSelectAll, areaClearSelection, areaSaveChanges;
    Button autosaveButton;
    private Button autosaveToggle, autosaveClose, roundingButton;
    /** A rectangle corner by compass direction; north is -Z and west is -X, as in Minecraft. */
    enum Corner {
        NORTH_WEST("corner_nw", false, false),
        NORTH_EAST("corner_ne", true, false),
        SOUTH_WEST("corner_sw", false, true),
        SOUTH_EAST("corner_se", true, true);

        /** The label of the corner in the area's details. */
        final String key;

        private final boolean east, south;

        Corner(String key, boolean east, boolean south) {
            this.key = key;
            this.east = east;
            this.south = south;
        }

        /** The corner block as "X Z". */
        String coordinates(SelectionBounds b) {
            return (east ? b.maxX() : b.minX()) + " " + (south ? b.maxZ() : b.minZ());
        }
    }

    /** The copy buttons after the values of the one checked area. */
    enum AreaCopy {
        NAME("copy_name_tooltip", null, null),
        /** A rectangle's north-west corner, or every vertex of a polygon or a route. */
        NORTH_WEST("copy_corner_nw_tooltip", Metric.CORNER_A, Corner.NORTH_WEST),
        AREA("copy_area_tooltip", Metric.AREA, null),
        PERIMETER("copy_perimeter_tooltip", Metric.PERIMETER, null),
        LENGTH("copy_length_tooltip", Metric.LENGTH, null),
        WIDTH("copy_width_tooltip", Metric.WIDTH, null),
        SOUTH_EAST("copy_corner_se_tooltip", null, Corner.SOUTH_EAST),
        NORTH_EAST("copy_corner_ne_tooltip", null, Corner.NORTH_EAST),
        SOUTH_WEST("copy_corner_sw_tooltip", null, Corner.SOUTH_WEST);

        final String tooltip;
        /** What the button copies of a shape that is not a rectangle, or of any shape when it has no corner. */
        final Metric metric;

        final Corner corner;

        AreaCopy(String tooltip, Metric metric, Corner corner) {
            this.tooltip = tooltip;
            this.metric = metric;
            this.corner = corner;
        }
    }

    final Map<AreaCopy, Button> areaCopies = new EnumMap<>(AreaCopy.class);
    /** The copy button of the overlap of two checked areas. */
    private Button overlapCopy;
    /** List order of the Areas window; LAYER is the drawing order, the bottom area first. */
    enum AreaSort {
        LAYER,
        NAME,
        GROUP,
        VISIBILITY
    }

    private AreaSort areaSort = AreaSort.LAYER;
    private Button areaSortButton;
    /** Filters the Areas window list by name; cleared whenever the window opens. */
    EditBox areaSearch;

    private final Map<AutosaveSettings.Field, Button> autosaveFields = new EnumMap<>(AutosaveSettings.Field.class);
    /** The Areas window shows the autosave settings instead of the area properties. */
    boolean autosaveOpen;
    /** The last row clicked without Shift; Shift+click checks the range from it. */
    String areaAnchorId;
    /** The row last clicked plainly and when, to catch the second click of a double click. */
    private String lastRowId;

    private long lastRowAt;
    private Button groupAssign, areaVisibility, areaColor, areaOpacity, areaExportButton, areaMapVisibility;
    private Button areaMinimapVisibility, areaNames;
    boolean areaDeleteConfirmation;
    /** The confirmation was opened from the right-click menu, so it returns to the map. */
    boolean areaDeleteFromMenu;

    String areaId;
    final Set<String> selectedAreaIds = new LinkedHashSet<>();

    private AreaGeometry overlapGeometry;
    private State overlapState;
    private Set<String> overlapIds = Set.of();

    private State sortedState;
    private AreaSort sortedBy;
    private String sortedQuery = "";
    private List<Area> sortedAreas = List.of();

    int areaScroll;

    AreasWindow(ExactSelectionController ui) {
        super(ui);
    }

    int visibleAreaDialogRows(int height) {
        // The list ends above the export/import, select, save and overlay rows at the bottom of the window.
        return Math.max(1, (height - 148) / 20);
    }

    void syncAreaSelection() {
        List<Area> areas = ui.areas();
        Set<String> existing = new HashSet<>();
        for (Area area : areas) existing.add(area.id());
        selectedAreaIds.retainAll(existing);
        if (!selectedAreaIds.contains(areaId)) areaId = lastOf(selectedAreaIds);
        if (ui.groupsWindow.selectedGroup() == null) ui.groupsWindow.groupId = null;
        ui.detachMissingSource();
        // History replaced the stored areas; a draft based on the old values no longer applies.
        ui.areaEdits.areaDraft.clear();
        syncNameField();
    }

    Area area(String id) {
        return ui.areas().stream()
                .filter(a -> Objects.equals(a.id(), id))
                .findFirst()
                .orElse(null);
    }

    Area selectedArea() {
        return area(areaId);
    }

    private static String lastOf(Set<String> ids) {
        return ids.stream().reduce((first, last) -> last).orElse(null);
    }

    /** The name field holds the name of the one checked area, and nothing otherwise. */
    private void syncNameField() {
        ui.setDialogValue(
                DialogField.AREA_NAME,
                selectedAreaIds.size() == 1 && selectedArea() != null
                        ? selectedArea().name()
                        : "");
    }

    /**
     * The shown area under the pointer; the smallest of overlapping ones, so a plot inside a district can be picked,
     * and the upper of equal ones.
     */
    Area areaAt(MapBridge.Frame frame, double x, double y) {
        double worldX = frame.worldX(x), worldZ = frame.worldZ(y);
        Area best = null;
        for (Area area : ui.areas()) {
            AreaGeometry geometry = area.geometry();
            boolean hit = geometry.closed()
                    ? geometry.containsPoint(worldX, worldZ)
                    : ui.polygonTool.hitVertex(frame, geometry.vertices(), x, y) >= 0
                            || ui.polygonTool.hitPolygonEdge(frame, geometry.vertices(), x, y, false) >= 0;
            if (hit
                    && shownOnMap(area)
                    && (best == null || geometry.area() <= best.geometry().area())) best = area;
        }
        return best;
    }

    List<Area> selectedAreas() {
        return ui.areas().stream().filter(a -> selectedAreaIds.contains(a.id())).toList();
    }

    void loadArea(Area area) {
        if (area == null) return;
        ui.areaEdits.flushDraft();
        SelectionState.Snapshot before = ui.historyBase();
        ui.state.load(area.geometry(), area.id());
        ui.gesture = null;
        ui.polygonTool.mode = false;
        ui.rulerTool.setRulerMode(false);
        ui.commitSelection(before, ui.state.snapshot(), "area_load");
        ui.dialog = Dialog.NONE;
        ui.say(ui.state.geometry() instanceof PolygonGeometry ? "polygon_loaded" : "area_loaded");
        ui.focus(null);
        // Off screen the window would seem to close without doing anything.
        MapBridge.Frame frame = ui.checkedFrame();
        if (frame != null && !ui.mapOverlays.onScreen(frame, area.geometry().bounds())) moveMapTo(area);
    }

    /** Moves the map to an area and zooms until it fills most of the screen. */
    private void moveMapTo(Area area) {
        MapBridge.Frame frame = ui.checkedFrame();
        if (frame == null) return;
        GeometryBounds b = area.geometry().bounds();
        ui.bridge.exactselection$moveCamera(
                b.centerX(),
                b.centerZ(),
                0.8 * Math.min(frame.pixelWidth() / (double) b.width(), frame.pixelHeight() / (double) b.length()));
    }

    /** Closes the window and shows the area on the map. */
    private void showArea(Area area) {
        if (area == null) return;
        ui.windows.cancelDialog();
        moveMapTo(area);
    }

    Area editedArea() {
        return area(ui.state.source());
    }

    void applyEditedArea() {
        Area area = editedArea();
        AreaGeometry geometry = ui.state.geometry();
        if (area == null
                || geometry == null
                || ui.dirty
                || !ui.polygonTool.draft.isEmpty()
                || ui.holding()
                || ui.loadFailed
                || ui.state.phase() != SelectionState.Phase.SELECTED
                || area.geometry().equals(geometry)) return;
        if (ui.areaEdits.changeArea(area, a -> a.withGeometry(geometry))) ui.say("area_applied", area.name());
    }

    private void deleteArea() {
        if (ui.checkedFrame() == null || !ui.enabled() || selectedAreas().isEmpty() || ui.loadFailed) return;
        areaDeleteConfirmation = true;
        ui.message = "";
        ui.focus(ui.confirm);
    }

    void confirmAreaDelete() {
        if (ui.checkedFrame() == null
                || !ui.enabled()
                || ui.dialog != Dialog.AREAS
                || !areaDeleteConfirmation
                || ui.loadFailed) return;
        List<Area> targets = selectedAreas();
        if (targets.isEmpty()) {
            areaDeleteConfirmation = false;
            return;
        }
        Set<String> ids = new HashSet<>(selectedAreaIds);
        List<Area> areas = new ArrayList<>(ui.areas());
        areas.removeIf(area -> ids.contains(area.id()));
        int count = targets.size();
        boolean editing = ids.contains(ui.state.source());
        if (!ui.areaEdits.changeAreas(ui.areaEdits.areaState(areas, ui.groups()), "area_delete")) return;
        areaId = null;
        selectedAreaIds.clear();
        ui.areaEdits.areaDraft.clear();
        ui.setDialogValue(DialogField.AREA_NAME, "");
        ui.say("area_deleted", count);
        areaDeleteConfirmation = false;
        if (areaDeleteFromMenu) {
            areaDeleteFromMenu = false;
            ui.dialog = Dialog.NONE;
            // The selection goes only when it was this area opened for editing.
            if (editing) ui.reset();
            ui.say("quick_menu_deleted", targets.get(0).name());
        }
        // Deleted from the window, the area's shape stays on the map as a new selection.
        ui.detachMissingSource();
        ui.focus(null);
    }

    private void selectAllAreas() {
        ui.areaEdits.flushDraft();
        selectedAreaIds.clear();
        // With a search, All checks the areas it shows.
        List<Area> areas = displayedAreas();
        for (Area area : areas) selectedAreaIds.add(area.id());
        areaId = areas.isEmpty() ? null : areas.get(areas.size() - 1).id();
        syncNameField();
    }

    private void clearAreaSelection() {
        ui.areaEdits.flushDraft();
        selectedAreaIds.clear();
        areaId = null;
        ui.setDialogValue(DialogField.AREA_NAME, "");
    }

    private void toggleArea() {
        List<Area> targets = selectedAreas();
        if (targets.isEmpty()) return;
        boolean visible = targets.stream().noneMatch(Area::visible);
        List<Area> areas = ui.areas().stream()
                .map(a -> selectedAreaIds.contains(a.id()) ? a.withVisible(visible) : a)
                .toList();
        if (ui.areaEdits.changeAreas(ui.areaEdits.areaState(areas, ui.groups()), "area_update"))
            ui.say(visible ? "areas_shown" : "areas_hidden", targets.size());
    }

    /** Ids of saved areas sharing a positive area with the finished selection; cached per selection and store state. */
    Set<String> areasInSelection() {
        AreaGeometry geometry = ui.state.phase() == SelectionState.Phase.SELECTED ? ui.state.geometry() : null;
        if (geometry != overlapGeometry || ui.areaStore.state() != overlapState) {
            overlapGeometry = geometry;
            overlapState = ui.areaStore.state();
            Set<String> ids = new LinkedHashSet<>();
            if (geometry != null)
                for (Area area : overlapState.areas()) if (area.geometry().overlaps(geometry)) ids.add(area.id());
            overlapIds = ids;
        }
        return overlapIds;
    }

    void openAreasInSelection() {
        Set<String> ids = areasInSelection();
        if (ids.isEmpty()) return;
        ui.windows.openDialog(Dialog.AREAS);
        if (ui.dialog != Dialog.AREAS) return;
        selectedAreaIds.addAll(ids);
        areaId = lastOf(ids);
        syncNameField();
        ui.say("zones_in_selection_opened", ids.size());
    }

    private void cycleAreaOpacity() {
        Area a = selectedArea();
        if (a == null && !selectedAreas().isEmpty()) a = selectedAreas().get(0);
        if (a == null || ui.loadFailed) return;
        ui.areaEdits.areaDraft.opacity =
                nextOpacity(ui.areaEdits.areaDraft.opacity != null ? ui.areaEdits.areaDraft.opacity : a.opacity());
        ui.areaEdits.stage(AutosaveSettings.Field.OPACITY);
    }

    /** The fill step after the one closest to {@code opacity}: 0 → 25 → 50 → 75 → 100 → 0. */
    static int nextOpacity(int opacity) {
        int[] values = {0, 25, 50, 75, 100};
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - opacity) < Math.abs(values[index] - opacity)) index = i;
        }
        return values[(index + 1) % values.length];
    }

    /** Where the search field goes along the row above the list. */
    private record Span(int x, int width) {
        /** A narrower field could not show what is typed, so it is hidden. */
        boolean fits() {
            return width >= 40;
        }
    }

    /**
     * The search field's place: between the checked count and the order button, over the count when the list column
     * is narrow.
     */
    private static Span areaSearchSpan(int x, int col) {
        int end = x + 14 + col - 134;
        int start = end - (x + 92) >= 60 ? x + 92 : x + 12;
        return new Span(start, end - start);
    }

    /**
     * Areas in the Areas window's display order, matching the search; rows, Shift ranges, Select all and scrolling all
     * use it. Cached per store state, sort and search.
     */
    List<Area> displayedAreas() {
        String query = areaSearch == null ? "" : areaSearch.getValue().trim().toLowerCase(Locale.ROOT);
        if (ui.areaStore.state() != sortedState || areaSort != sortedBy || !query.equals(sortedQuery)) {
            sortedState = ui.areaStore.state();
            sortedBy = areaSort;
            sortedQuery = query;
            sortedAreas = sortAreas(sortedState.areas()).stream()
                    .filter(area -> area.name().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
        }
        return sortedAreas;
    }

    private List<Area> sortAreas(List<Area> stored) {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        Comparator<Area> byName = (a, b) -> collator.compare(a.name(), b.name());
        return switch (areaSort) {
            case LAYER -> stored;
            case NAME -> stored.stream().sorted(byName).toList();
            // Grouped areas by group name, then ungrouped ones.
            case GROUP ->
                stored.stream()
                        .sorted(Comparator.<Area, Boolean>comparing(a -> ui.groupsWindow.findGroup(a.groupId()) == null)
                                .thenComparing((a, b) -> ui.groupsWindow.findGroup(a.groupId()) == null
                                        ? 0
                                        : collator.compare(
                                                ui.groupsWindow
                                                        .findGroup(a.groupId())
                                                        .name(),
                                                ui.groupsWindow
                                                        .findGroup(b.groupId())
                                                        .name()))
                                .thenComparing(byName))
                        .toList();
            // Shown first; an area hidden by itself or by its group counts as hidden.
            case VISIBILITY ->
                stored.stream()
                        .sorted(Comparator.<Area, Boolean>comparing(a -> !shownOnMap(a))
                                .thenComparing(byName))
                        .toList();
        };
    }

    boolean shownOnMap(Area area) {
        Group group = ui.groupsWindow.findGroup(area.groupId());
        return area.visible() && (group == null || group.visible());
    }

    private String selectedGroupName() {
        List<Area> areas = selectedAreas();
        if (areas.isEmpty()) return ui.tr("group_none").getString();
        String id = areas.get(0).groupId();
        if (areas.stream().anyMatch(area -> !Objects.equals(id, area.groupId())))
            return ui.tr("group_mixed").getString();
        Group group = ui.groupsWindow.findGroup(id);
        return group == null ? ui.tr("group_none").getString() : group.name();
    }

    void renderAreasDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (areaDeleteConfirmation) {
            g.drawCenteredString(
                    ui.mc.font, ui.tr("area_delete_confirm", selectedAreas().size()), x + w / 2, y + h / 2 - 12, -1);
            g.drawCenteredString(ui.mc.font, ui.tr("area_delete_undoable"), x + w / 2, y + h / 2 + 6, Theme.TEXT_MUTED);
            return;
        }
        int col = (w - 36) / 2, right = x + w - col - 12;
        List<Area> areas = displayedAreas();
        int rows = visibleAreaDialogRows(h);
        areaScroll = Math.min(areaScroll, Math.max(0, areas.size() - rows));
        Span search = areaSearchSpan(x, col);
        if (search.x() > x + 12 || !search.fits())
            g.drawString(
                    ui.mc.font,
                    ui.mc.font.plainSubstrByWidth(
                            ui.tr("area_list_count", selectedAreaIds.size()).getString(), 76),
                    x + 12,
                    y + 34,
                    Theme.TEXT_MUTED);
        g.fill(x + 10, y + 46, x + 14 + col, y + h - 98, Theme.LIST_BACKGROUND);
        if (areas.isEmpty())
            g.drawString(
                    ui.mc.font,
                    ui.tr(ui.areas().isEmpty() ? "areas_empty" : "area_search_empty"),
                    x + 16,
                    y + 54,
                    Theme.TEXT_MUTED);
        for (int i = 0; i < rows && i + areaScroll < areas.size(); i++) {
            Area area = areas.get(i + areaScroll);
            int rowY = y + 48 + i * 20;
            boolean checked = selectedAreaIds.contains(area.id());
            if (checked) g.fill(x + 10, rowY, x + 14 + col, rowY + 20, Theme.ROW_CHECKED);
            g.renderOutline(x + 14, rowY + 4, 9, 9, checked ? ACCENT : Theme.INACTIVE);
            if (checked) g.drawString(ui.mc.font, "x", x + 16, rowY + 4, ACCENT);
            g.fill(x + 28, rowY + 4, x + 34, rowY + 10, 0xFF000000 | area.color());
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(area.name(), col - 30), x + 39, rowY + 1, -1);
            g.drawString(
                    ui.mc.font,
                    ui.mc.font.plainSubstrByWidth(areaVisibilityDescription(area), col - 30),
                    x + 39,
                    rowY + 11,
                    Theme.TEXT_MUTED);
        }
        if (ui.autosave.enabled()) {
            String indicator = "● " + ui.tr("autosave_indicator").getString();
            g.drawString(ui.mc.font, indicator, x + w - 36 - ui.mc.font.width(indicator), y + h - 20, ACCENT);
        }
        if (autosaveOpen) {
            g.drawString(ui.mc.font, ui.tr("autosave_title"), right, y + 34, ACCENT);
            g.drawString(
                    ui.mc.font,
                    ui.mc.font.plainSubstrByWidth(ui.tr("autosave_hint").getString(), col),
                    right,
                    y + 68 + 20 * autosaveFields.size() + 4,
                    Theme.TEXT_MUTED);
            renderManagementStatus(g, x, y, w, h, "areas_selection_hint");
            return;
        }
        g.drawString(ui.mc.font, ui.tr("area_name_label"), right, y + 34, Theme.TEXT_MUTED);
        int selectedTop = selectedSectionTop(y, h);
        dialogSection(g, ui.tr("selected_header"), right, selectedTop, col);
        placeAreaInfo(y, h, right, g, col);
        renderManagementStatus(g, x, y, w, h, "areas_selection_hint");
    }

    /** Save stays under Edit and Export unless autosave covers every waiting change. */
    private boolean areaSaveShown() {
        return !ui.autosave.enabled() || ui.areaEdits.areaDraft.needsSave(ui.autosave);
    }

    /** The area's details follow its buttons and close the gap a hidden Save leaves. */
    private int areaInfoTop(int y) {
        return y + (areaSaveShown() ? 152 : 132);
    }

    /** The Selected section sits at the bottom of the column, clear of the territory's own controls. */
    private int selectedSectionTop(int y, int h) {
        return Math.max(y + h - 84, areaInfoTop(y) + 4);
    }

    /** The area two checked shapes share; nothing when a route is among them, which has no inside. */
    private List<MetricLine> overlapInfo() {
        List<Area> two = selectedAreas();
        if (two.size() != 2
                || !two.get(0).geometry().closed()
                || !two.get(1).geometry().closed()) return List.of();
        double shared = sharedArea(two);
        return List.of(
                shared > 0
                        ? new MetricLine(
                                List.of(ui.tr("area_overlap", formatLength(shared))
                                        .getString()),
                                List.of(overlapCopy))
                        : new MetricLine(List.of(ui.tr("area_overlap_none").getString()), List.of()));
    }

    private static double sharedArea(List<Area> two) {
        return two.get(0).geometry().sharedArea(two.get(1).geometry());
    }

    List<Button> copyIcons() {
        List<Button> icons = new ArrayList<>(areaCopies.values());
        icons.add(overlapCopy);
        return icons;
    }

    /** Details of the single checked area with copy buttons after the values; lines that do not fit are left out. */
    private List<MetricLine> areaInfo(Area area) {
        List<MetricLine> info = new ArrayList<>();
        info.add(new MetricLine(List.of(areaVisibilityDescription(area)), List.of()));
        SelectionBounds b = area.bounds();
        if (!(area.geometry() instanceof RectangleGeometry))
            info.add(new MetricLine(
                    List.of(ui.tr("vertices_count", area.vertices().size()).getString()),
                    List.of(copy(AreaCopy.NORTH_WEST))));
        else {
            info.add(new MetricLine(
                    List.of(cornerText(b, Corner.NORTH_WEST), cornerText(b, Corner.NORTH_EAST)),
                    List.of(copy(AreaCopy.NORTH_WEST), copy(AreaCopy.NORTH_EAST))));
            info.add(new MetricLine(
                    List.of(cornerText(b, Corner.SOUTH_WEST), cornerText(b, Corner.SOUTH_EAST)),
                    List.of(copy(AreaCopy.SOUTH_WEST), copy(AreaCopy.SOUTH_EAST))));
        }
        if (area.geometry() instanceof RectangleGeometry)
            info.add(new MetricLine(
                    ui.selectionPanel.dimensionParts(b, false), List.of(copy(AreaCopy.WIDTH), copy(AreaCopy.LENGTH))));
        if (!area.geometry().closed()) {
            info.add(new MetricLine(
                    List.of(ui.tr("route_length", formatLength(area.geometry().perimeter()))
                            .getString()),
                    List.of(copy(AreaCopy.PERIMETER))));
            return info;
        }
        info.add(new MetricLine(
                List.of(ui.tr(
                                "area",
                                SelectionDialogs.formatNumber(area.geometry().area()))
                        .getString()),
                List.of(copy(AreaCopy.AREA))));
        info.add(new MetricLine(
                List.of(ui.tr("perimeter", formatLength(area.geometry().perimeter()))
                        .getString()),
                List.of(copy(AreaCopy.PERIMETER))));
        return info;
    }

    private Button copy(AreaCopy copy) {
        return areaCopies.get(copy);
    }

    private String cornerText(SelectionBounds b, Corner corner) {
        return ui.tr(corner.key, corner.coordinates(b)).getString();
    }

    /** Lays out (g == null) or draws the area details. */
    private void placeAreaInfo(int y, int h, int right, GuiGraphics g, int col) {
        Area area = selectedAreaIds.size() == 1 ? selectedArea() : null;
        if (area != null && g == null)
            areaCopies.forEach((copy, button) -> {
                if (copy != AreaCopy.NAME) button.visible = false;
            });
        int lineY = areaInfoTop(y), limit = selectedSectionTop(y, h) - 6;
        for (MetricLine line : area != null ? areaInfo(area) : overlapInfo()) {
            if (lineY + 9 > limit) break;
            if (g == null) {
                for (Button copy : line.copies()) copy.visible = true;
                ui.selectionPanel.placeMetricLine(line, right, lineY, col);
            } else ui.selectionPanel.drawMetricLine(g, line, right, lineY, col, Theme.TEXT_MUTED);
            lineY += 12;
        }
    }

    /** A titled divider inside a window column. */
    private void dialogSection(GuiGraphics g, Component text, int x, int y, int width) {
        g.drawString(ui.mc.font, text, x, y, Theme.TEXT_MUTED);
        int start = x + 4 + ui.mc.font.width(text);
        g.fill(start, y + 4, x + width, y + 5, Theme.BORDER);
    }

    private String areaVisibilityDescription(Area area) {
        Group group = ui.groupsWindow.findGroup(area.groupId());
        if (!area.visible()) return ui.tr("area_state_hidden").getString();
        if (group != null && !group.visible())
            return ui.tr("area_hidden_by_group", group.name()).getString();
        return ui.tr("area_state_shown").getString() + " · "
                + (group == null ? ui.tr("group_none").getString() : group.name());
    }

    void renderManagementStatus(GuiGraphics g, int x, int y, int w, int h, String hint) {
        String text = ui.message.isEmpty()
                ? ui.tr(ui.loadFailed ? ui.loadFailedKey() : hint).getString()
                : ui.message;
        // In Areas the right half of the bottom row holds the autosave button.
        int textWidth = ui.dialog == Dialog.AREAS ? (w - 36) / 2 : w - 24;
        g.drawString(
                ui.mc.font,
                ui.mc.font.plainSubstrByWidth(text, textWidth),
                x + 12,
                y + h - 12,
                ui.message.isEmpty() && !ui.loadFailed ? Theme.TEXT_MUTED : Theme.WARNING);
    }

    void clickAreaRow(MouseButtonEvent event, int x, int y, int w, int h) {
        if (areaDeleteConfirmation) return;
        int col = (w - 36) / 2;
        int rows = visibleAreaDialogRows(h);
        if (event.x() >= x + 10 && event.x() < x + 14 + col && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
            int index = areaScroll + (int) ((event.y() - y - 48) / 20);
            List<Area> areas = displayedAreas();
            if (index < areas.size()) {
                Area area = areas.get(index);
                ui.areaEdits.flushDraft();
                // A second plain click on a row shows the area; 500 ms as for sides and vertices on the map.
                boolean plain = event.x() >= x + 27
                        && (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT))
                                == 0;
                boolean again = plain && area.id().equals(lastRowId) && Util.getMillis() - lastRowAt < 500;
                lastRowId = plain && !again ? area.id() : null;
                lastRowAt = Util.getMillis();
                if (again) {
                    showArea(area);
                    return;
                }
                // Like file managers: Ctrl/Cmd (or the checkbox) toggles one row, Shift checks the range
                // from the last plainly clicked row, and both together add that range.
                boolean toggle =
                        event.x() < x + 27 || (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
                boolean range = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
                int anchor = -1;
                for (int i = 0; i < areas.size(); i++) if (areas.get(i).id().equals(areaAnchorId)) anchor = i;
                if (range && anchor >= 0) {
                    if (!toggle) selectedAreaIds.clear();
                    for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++)
                        selectedAreaIds.add(areas.get(i).id());
                } else {
                    if (!toggle) selectedAreaIds.clear();
                    if (!selectedAreaIds.remove(area.id()) || !toggle) selectedAreaIds.add(area.id());
                    areaAnchorId = area.id();
                }
                areaId = selectedAreaIds.contains(area.id()) ? area.id() : lastOf(selectedAreaIds);
                syncNameField();
                ui.focus(null);
            }
        }
    }

    void layoutAreas(int x, int y, int w, int h, int buttonY) {
        if (areaDeleteConfirmation) {
            place(ui.confirm, x + w / 2 - 116, buttonY, 110, 18);
            place(ui.cancel, x + w / 2 + 6, buttonY, 110, 18);
            return;
        }
        int col = (w - 36) / 2, right = x + w - col - 12, half = (col - 3) / 2;
        place(ui.managementUndo, x + w - 232, y + 7, 22, 18);
        place(ui.managementRedo, x + w - 207, y + 7, 22, 18);
        place(ui.groupsWindow.manageGroups, x + w - 180, y + 7, 92, 18);
        place(ui.managementClose, x + w - 82, y + 7, 70, 18);
        place(autosaveButton, x + w - 30, y + h - 24, 18, 16);
        if (autosaveOpen) {
            place(autosaveToggle, right, y + 46, col, 18);
            int row = 0;
            for (Button button : autosaveFields.values()) place(button, right, y + 68 + 20 * row++, col, 18);
            // The autosave hint follows the switches (renderAreasDialog), then copy rounding and Close.
            place(roundingButton, right, y + 84 + 20 * row, col, 18);
            place(autosaveClose, right, y + 104 + 20 * row, col, 18);
        } else {
            place(ui.field(DialogField.AREA_NAME), right, y + 46, col - 42, 18);
            place(copy(AreaCopy.NAME), right + col - 39, y + 46, 18, 18);
            place(areaShow, right + col - 18, y + 46, 18, 18);
            place(groupAssign, right, y + 66, col, 18);
            place(areaColor, right, y + 86, half, 18);
            place(areaOpacity, right + half + 3, y + 86, col - half - 3, 18);
            place(areaLoad, right, y + 106, half, 18);
            place(areaExportButton, right + half + 3, y + 106, col - half - 3, 18);
            place(areaSaveChanges, right, y + 126, col, 18);
            placeAreaInfo(y, h, right, null, col);
            // "Selected" section: visibility and deletion of every checked area, apart from the territory above.
            int selectedTop = selectedSectionTop(y, h);
            place(areaVisibility, right, selectedTop + 12, half, 18);
            place(areaDelete, right + half + 3, selectedTop + 12, col - half - 3, 18);
        }
        place(ui.mapExport.openExportButton, right, y + h - 44, col, 16);
        // A damaged file allows no export, so its notice cannot be there at the same time.
        place(areaRestore, right, y + h - 44, col, 16);
        place(areaSortButton, x + 14 + col - 130, y + 27, 130, 16);
        Span search = areaSearchSpan(x, col);
        place(areaSearch, search.x(), y + 27, Math.max(1, search.width()), 16);
        // A text field does not clip its hint, which would run under the order button.
        areaSearch.setHint(Component.literal(
                ui.mc.font.plainSubstrByWidth(ui.tr("area_search").getString(), Math.max(0, search.width() - 8))));
        place(ui.transferWindow.areaExportFile, x + 12, y + h - 92, half, 18);
        place(ui.transferWindow.areaImport, x + 15 + half, y + h - 92, col - half - 24, 18);
        place(ui.transferWindow.transferFolderButton, x + 12 + col - 18, y + h - 92, 18, 18);
        place(areaSelectAll, x + 12, y + h - 72, half, 18);
        place(areaClearSelection, x + 15 + half, y + h - 72, col - half - 3, 18);
        place(areaSave, x + 12, y + h - 52, col, 18);
        int third = (col - 6) / 3;
        place(areaMapVisibility, x + 12, y + h - 32, third, 18);
        place(areaMinimapVisibility, x + 15 + third, y + h - 32, third, 18);
        place(areaNames, x + 18 + 2 * third, y + h - 32, col - 6 - 2 * third, 18);
    }

    void showAreaWidgets() {
        if (areaDeleteConfirmation) ui.show(true, ui.confirm, ui.cancel);
        else {
            ui.show(
                    true,
                    ui.managementClose,
                    ui.groupsWindow.manageGroups,
                    ui.managementUndo,
                    ui.managementRedo,
                    areaSave,
                    ui.transferWindow.areaExportFile,
                    ui.transferWindow.areaImport,
                    ui.transferWindow.transferFolderButton,
                    areaSortButton,
                    areaSearch,
                    areaSelectAll,
                    areaClearSelection,
                    areaMapVisibility,
                    areaMinimapVisibility,
                    areaNames,
                    autosaveButton);
            Rect r = ui.windows.dialogRect();
            areaSearch.visible = areaSearchSpan(r.x(), (r.width() - 36) / 2).fits();
            if (autosaveOpen) {
                ui.show(true, autosaveToggle, autosaveClose, roundingButton);
                roundingButton.setMessage(SettingsScreen.roundingLabel(ui.autosave.rounding()));
                ui.show(true, autosaveFields.values().toArray(AbstractWidget[]::new));
            } else {
                ui.show(
                        true,
                        ui.field(DialogField.AREA_NAME),
                        groupAssign,
                        areaLoad,
                        areaExportButton,
                        areaDelete,
                        areaVisibility,
                        areaColor,
                        areaOpacity);
                // Save disappears under autosave unless a change outside its fields still waits.
                ui.show(areaSaveShown(), areaSaveChanges);
                ui.show(
                        selectedAreaIds.size() == 1 && selectedArea() != null,
                        areaCopies.values().toArray(AbstractWidget[]::new));
                overlapCopy.visible = false;
                areaShow.visible = copy(AreaCopy.NAME).visible;
            }
        }
        if (!areaDeleteConfirmation && ui.mapExport.hasExportNotice()) ui.show(true, ui.mapExport.openExportButton);
        areaRestore.visible = !areaDeleteConfirmation && ui.areaStore.canRestore();
    }

    void updateAreaWidgets() {
        List<Area> checked = selectedAreas();
        int selectedCount = checked.size();
        boolean singleArea = selectedCount == 1;
        Area area = selectedArea();
        boolean editingThis = area != null && Objects.equals(area.id(), ui.state.source());
        boolean route = area != null && !area.geometry().closed();
        areaLoad.active = singleArea && !editingThis && !route;
        areaLoad.setMessage(ui.tr(editingThis ? "area_editing_now" : "area_edit"));
        areaSaveChanges.active = !ui.areaEdits.areaDraft.isEmpty() && selectedCount > 0 && !ui.loadFailed;
        for (Button copy : areaCopies.values()) copy.active = singleArea;
        ui.tip(
                copy(AreaCopy.NORTH_WEST),
                ui.tr(
                        area != null && !(area.geometry() instanceof RectangleGeometry)
                                ? "copy_coordinates_tooltip"
                                : "copy_corner_nw_tooltip"));
        autosaveToggle.setMessage(ui.tr(ui.autosave.enabled() ? "autosave_on" : "autosave_off"));
        autosaveFields.forEach((field, button) -> {
            button.setMessage(ui.tr(
                    "autosave_field",
                    ui.tr("autosave_field_" + field.name().toLowerCase(Locale.ROOT)),
                    ui.tr(ui.autosave.includes(field) ? "state_on" : "state_off")));
            button.active = ui.autosave.enabled();
        });
        areaExportButton.active = singleArea && !route && !ui.loadFailed && !ui.mapExport.exportRunning;
        areaDelete.active = selectedCount > 0 && !ui.loadFailed;
        areaColor.active = selectedCount > 0 && !ui.loadFailed;
        areaOpacity.active = areaColor.active && !route;
        areaVisibility.active = selectedCount > 0 && !ui.loadFailed;
        groupAssign.active = selectedCount > 0 && !ui.loadFailed;
        areaSave.active = ui.state.phase() == SelectionState.Phase.SELECTED && !ui.dirty && !ui.loadFailed;
        ui.field(DialogField.AREA_NAME).active = singleArea && !ui.loadFailed;
        ui.transferWindow.areaExportFile.active = !ui.loadFailed && !ui.areas().isEmpty();
        ui.transferWindow.areaExportFile.setMessage(
                selectedCount > 0 ? ui.tr("areas_export_count", selectedCount) : ui.tr("areas_export"));
        ui.transferWindow.areaImport.active = !ui.loadFailed;
        areaSortButton.setMessage(ui.tr("area_sort_" + areaSort.name().toLowerCase(Locale.ROOT)));
        boolean hideAreas = checked.stream().anyMatch(Area::visible);
        areaVisibility.setMessage(ui.tr(hideAreas ? "area_hide_selected" : "area_show_selected", selectedCount));
        // Unsaved values show with a trailing asterisk until Save or autosave writes them.
        Area primary = area != null ? area : checked.isEmpty() ? null : checked.get(0);
        areaOpacity.setMessage(
                primary == null
                        ? ui.tr("area_fill")
                        : Component.literal((ui.areaEdits.areaDraft.opacity != null
                                        ? ui.areaEdits.areaDraft.opacity
                                        : primary.opacity())
                                + "%" + (ui.areaEdits.areaDraft.opacity != null ? " *" : "")));
        String draftGroup = !ui.areaEdits.areaDraft.groupSet
                ? selectedGroupName()
                : ui.groupsWindow.findGroup(ui.areaEdits.areaDraft.groupId) == null
                        ? ui.tr("group_none").getString()
                        : ui.groupsWindow
                                .findGroup(ui.areaEdits.areaDraft.groupId)
                                .name();
        groupAssign.setMessage(Component.literal(
                ui.tr("area_group_button", draftGroup).getString() + (ui.areaEdits.areaDraft.groupSet ? " *" : "")));
        areaColor.setMessage(Component.literal(
                ui.tr("area_color").getString() + (ui.areaEdits.areaDraft.color != null ? " *" : "")));
        areaMapVisibility.setMessage(ui.tr(ui.areaStore.state().showOnMap() ? "area_map_on" : "area_map_off"));
        areaMinimapVisibility.setMessage(
                ui.tr(ui.areaStore.state().showOnMinimap() ? "area_minimap_on" : "area_minimap_off"));
        areaNames.setMessage(ui.tr(ui.areaStore.state().showNames() ? "area_names_on" : "area_names_off"));
        areaMapVisibility.active = areaMinimapVisibility.active = areaNames.active = !ui.loadFailed;
        areaDelete.setMessage(ui.tr("area_delete_count", selectedCount));
        areaSelectAll.active =
                !ui.loadFailed && displayedAreas().stream().anyMatch(a -> !selectedAreaIds.contains(a.id()));
        areaClearSelection.active = selectedCount > 0;
        ui.tip(areaSave, ui.tr(ui.loadFailed ? ui.loadFailedKey() : "area_save_tooltip"));
    }

    void createAreaWidgets() {
        ui.field(DialogField.AREA_NAME).setResponder(value -> {
            if (ui.syncing) return;
            Area area = selectedAreas().size() == 1 ? selectedArea() : null;
            String name = value.trim();
            ui.areaEdits.areaDraft.name = area == null || name.isEmpty() || name.equals(area.name()) ? null : name;
        });
        areaSave = ui.button("area_save_selection", () -> {
            ui.newAreaWindow.areaNameReturn = Dialog.AREAS;
            ui.newAreaWindow.openAreaNameDialog();
        });
        areaLoad = ui.button("area_edit", "area_edit_tooltip", () -> loadArea(selectedArea()));
        areaShow = ui.iconButton("area_show_tooltip", () -> showArea(selectedArea()));
        areaRestore = ui.button("area_restore", "area_restore_tooltip", ui.areaEdits::restoreAreas);
        areaDelete = ui.button("area_delete", "area_delete_tooltip", this::deleteArea);
        areaSaveChanges = ui.button(
                "area_save_changes", "area_save_changes_tooltip", () -> ui.areaEdits.commitDraft(field -> true));
        autosaveButton = ui.iconButton("autosave_tooltip", () -> {
            autosaveOpen = !autosaveOpen;
            ui.focus(null);
        });
        autosaveToggle = ui.button("autosave_off", () -> {
            ui.autosave.setEnabled(!ui.autosave.enabled());
            if (ui.autosave.enabled()) ui.areaEdits.commitDraft(ui.autosave::covers);
        });
        for (AutosaveSettings.Field field : AutosaveSettings.Field.values())
            autosaveFields.put(field, ui.button("autosave_field", () -> {
                ui.autosave.toggle(field);
                if (ui.autosave.covers(field)) ui.areaEdits.commitDraft(f -> f == field);
            }));
        areaSortButton = ui.button("area_sort_layer", "area_sort_tooltip", () -> {
            areaSort = AreaSort.values()[(areaSort.ordinal() + 1) % AreaSort.values().length];
            areaScroll = 0;
        });
        String typedSearch = areaSearch == null ? "" : areaSearch.getValue();
        areaSearch = new EditBox(ui.mc.font, 0, 0, 100, 16, ui.tr("area_search"));
        areaSearch.setMaxLength(64);
        areaSearch.setValue(typedSearch);
        areaSearch.setResponder(value -> areaScroll = 0);
        ui.widgets.add(areaSearch);
        autosaveClose = ui.button("autosave_close", () -> {
            autosaveOpen = false;
            ui.focus(null);
        });
        roundingButton = ui.button("rounding", "rounding_tooltip", () -> ui.autosave.cycleRounding());
        for (AreaCopy copy : AreaCopy.values()) {
            areaCopies.put(copy, ui.iconButton(copy.tooltip, () -> {
                Area area = selectedAreas().size() == 1 ? selectedArea() : null;
                if (area == null) return;
                if (copy == AreaCopy.NAME) ui.selectionPanel.copyToClipboard(area.name());
                else if (copy.corner != null && area.geometry() instanceof RectangleGeometry)
                    ui.selectionPanel.copyToClipboard(copy.corner.coordinates(area.bounds()));
                else if (copy.metric != null) ui.selectionPanel.copyMetric(copy.metric, area.geometry(), area.bounds());
            }));
        }
        overlapCopy = ui.iconButton("copy_overlap_tooltip", () -> {
            List<Area> two = selectedAreas();
            if (two.size() != 2) return;
            double shared = sharedArea(two);
            ui.selectionPanel.copyToClipboard(
                    ui.autosave.rounding() == AutosaveSettings.Rounding.EXACT
                            ? formatLength(shared)
                            : ui.selectionPanel.rounded(shared));
        });
        areaSelectAll = ui.button("area_select_all", "area_select_all_tooltip", this::selectAllAreas);
        areaClearSelection =
                ui.button("area_clear_selection", "area_clear_selection_tooltip", this::clearAreaSelection);
        groupAssign = ui.button("group_choose", "group_picker_tooltip", ui.groupsWindow::openGroupChooser);
        areaVisibility = ui.button("area_toggle", "area_visibility_rules", this::toggleArea);
        areaColor = ui.button("area_color", "area_color_tooltip", () -> ui.colorWindows.openColorDialog(Dialog.AREAS));
        areaOpacity = ui.button("area_fill", "area_opacity_tooltip", this::cycleAreaOpacity);
        areaMapVisibility = ui.button("area_map_on", "area_map_tooltip", ui.areaEdits::toggleAreaMapVisibility);
        areaNames = ui.button("area_names_on", "area_names_tooltip", ui.areaEdits::toggleAreaNames);
        areaMinimapVisibility =
                ui.button("area_minimap_on", "area_minimap_tooltip", ui.areaEdits::toggleAreaMinimapVisibility);
        areaExportButton = ui.button("area_export", "area_export_tooltip", ui.mapExport::exportSelectedArea);
    }
}
