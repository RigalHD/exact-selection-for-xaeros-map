package dev.exactselection;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;
import xaero.map.gui.GuiMap;

public final class ExactSelectionController {
    private static final int PANEL_X = 34, PANEL_Y = 26;
    private static final int COMPACT_HEIGHT = 186, EXPANDED_HEIGHT = 314;
    /** -12 while the perimeter shares the area line: everything below moves up one line and the panel gets shorter. */
    private int metricsShift;
    private static final int RENAME_FIELD = 9, HEX_FIELD = 10;
    private static final int ACCENT = 0xFF59E3CE;
    private record MapLabel(Component text, int x, int y, int color) {}
    private record ScreenPoint(double x, double y) {}
    private enum Dialog { NONE, SIZE, MOVE, CENTER, AREAS, GROUPS, ASSIGN_GROUP, AREA_NAME, GROUP_NAME, WAYPOINTS, COLOR }
    private final GuiMap screen;
    private final MapBridge bridge;
    private final Minecraft mc;
    private final SelectionState state = new SelectionState();
    private final SelectionHistory history = new SelectionHistory();
    private final WaypointAccess waypoints;
    private final SavedAreaStore areaStore;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final EditBox[] fields = new EditBox[4];
    // 0–3 dimensions/offsets, 4–8 area/group/waypoint names and search, 9 waypoint rename, 10 RGB color.
    private final EditBox[] dialogFields = new EditBox[11];
    private Button toggle, apply, clear, pointsButton, confirm, cancel;
    private Button copyCoordinates, copyArea, copyPerimeter, copyLength, copyWidth;
    private Button undo, redo, chunkMode, sizeButton, moveButton, centerButton, areasButton, groupsButton;
    private Button polygonModeButton, panelModeButton, selectionExportButton;
    private boolean compact = true;
    private int panelHeight, panelScroll;
    private Button modalApply, modalCancel, anchorButton, moveUnitsButton;
    private Button centerCopy, centerCreate, areaSave, areaLoad, areaDelete;
    private Button areaSelectAll, areaClearSelection, areaSaveChanges, autosaveButton, autosaveToggle, autosaveClose;
    /** Copy buttons of the Areas window, in the panel's order: coordinates, area, perimeter, length, width. */
    private final Button[] areaCopies = new Button[5];
    private final Map<AutosaveSettings.Field, Button> autosaveFields = new java.util.EnumMap<>(AutosaveSettings.Field.class);
    private final AreaDraft areaDraft = new AreaDraft();
    private final AutosaveSettings autosave;
    /** The Areas window shows the autosave settings instead of the area properties. */
    private boolean autosaveOpen;
    private Button groupCreate, groupRename, groupAssign, groupVisibility, areaVisibility, areaColor, areaOpacity;
    private Button groupDelete, manageGroups, managementClose, managementUndo, managementRedo;
    private Button areaExportButton, openExportButton;
    private Path lastExport;
    private boolean exportRunning;
    private String exportNotice = "";
    private Button areaMapVisibility, areaMinimapVisibility;
    private Button pointVisibility, pointColor, pointScope, pointRename, pointRecolor;
    private Button quickSave, zonesButton, newAreaGroup, newAreaOpacityButton, colorHexApply;
    private Dialog areaNameReturn = Dialog.AREAS, colorReturn = Dialog.AREAS;
    private String newAreaGroupId;
    /** Style for the next saved area; kept between saves so a series of areas can share it. */
    private int newAreaColor = AreaPalette.DEFAULT, newAreaOpacity = 25;
    private boolean pointsInSelection;
    private MapBridge.Context context;
    private WaypointAccess.Snapshot selected, pending;
    private boolean syncing, dirty, dragging, chunks, sizeFromCenter, moveInChunks, loadFailed;
    private boolean areaDeleteConfirmation;
    private boolean groupDeleteConfirmation;
    private boolean renamingGroup;
    private Dialog groupsReturnDialog = Dialog.NONE;
    private final Set<String> assignmentTargets = new LinkedHashSet<>();
    private int assignmentScroll;
    private double lastMouseX, lastMouseY;
    private int panelWidth, coordinateLabelWidth, zLabelWidth, fieldWidth, fieldGap, modalScroll;
    private Dialog dialog = Dialog.NONE;
    /**
     * The map gesture in progress, or null. It ends through its finish or cancel; disable, reset and changes that
     * replace the selection drop it outright, which turns a later finish or cancel of it into a no-op.
     */
    private MapGesture gesture;
    private final MapGesture.Host gestureHost = new MapGesture.Host() {
        @Override public MapGesture gesture() { return gesture; }
        @Override public void clearGesture() { gesture = null; }
        @Override public SelectionState state() { return state; }
        @Override public List<GridPoint> draft() { return polygonDraft; }
        @Override public boolean chunks() { return chunks; }
        @Override public void commit(SelectionState.Snapshot before, String key) { commitSelection(before, state.snapshot(), key); }
        @Override public void recordDraftChange(List<GridPoint> before) { ExactSelectionController.this.recordDraftChange(before); }
        @Override public void selectionMoved() { syncFields(); updateSelection(); }
        @Override public void refresh() { refreshSelectionState(); }
        @Override public void message(String key) { message = tr(key).getString(); }
        @Override public void updateVisibility() { ExactSelectionController.this.updateVisibility(); }
    };
    private String areaId, groupId;
    private final Set<String> selectedAreaIds = new LinkedHashSet<>();
    private final Set<Object> chosenPoints = new HashSet<>();
    private int visibilityFilter, colorFilter = -1;
    private MapBridge.Context historyContext;
    private String message = "";
    private boolean polygonMode, shiftDown;
    private final List<GridPoint> polygonDraft = new ArrayList<>();
    private GridPoint polygonCursor;
    private SelectionState.Snapshot polygonBefore;
    private final Deque<List<GridPoint>> polygonDraftUndo = new ArrayDeque<>();
    private final Deque<List<GridPoint>> polygonDraftRedo = new ArrayDeque<>();

    public ExactSelectionController(GuiMap screen, MapBridge bridge) {
        this(screen, bridge, Minecraft.getInstance(), new SavedAreaStore(FabricLoader.getInstance().getConfigDir()),
                FabricLoader.getInstance().isModLoaded("xaerominimap") ? new XaeroWaypointBridge() : null);
    }

    ExactSelectionController(GuiMap screen, MapBridge bridge, Minecraft mc, SavedAreaStore areaStore, WaypointAccess waypoints) {
        this.screen = screen;
        this.bridge = bridge;
        this.mc = mc;
        this.areaStore = areaStore;
        this.waypoints = waypoints;
        this.autosave = AutosaveSettings.load(areaStore.root());
    }

    private Component tr(String key, Object... args) {
        return Component.translatable("exactselection." + key, args);
    }

    public void init() {
        // Xaero rebuilds its widgets on resize and when changing dimensions/settings.
        String[] draft = fields[0] == null ? null : java.util.Arrays.stream(fields).map(EditBox::getValue).toArray(String[]::new);
        String[] dialogDraft = dialogFields[0] == null ? null : java.util.Arrays.stream(dialogFields).map(EditBox::getValue).toArray(String[]::new);
        widgets.clear();
        int toggleWidth = 24;
        toggle = button("toggle_icon", 6, 28, toggleWidth, this::toggle);
        toggle.setTooltip(Tooltip.create(tr("title")));
        coordinateLabelWidth = Math.max(mc.font.width("A: X"), mc.font.width("B: X"));
        zLabelWidth = mc.font.width("Z");
        fieldGap = 6;
        for (int i = 0; i < 4; i++) {
            int col = i % 2, row = i / 2;
            EditBox field = new EditBox(mc.font, 0, 0, 100, 18,
                    tr("coordinate", row == 0 ? "A" : "B", col == 0 ? "X" : "Z"));
            field.setMaxLength(10);
            field.setResponder(value -> { if (!syncing) { dirty = true; message = ""; updateVisibility(); } });
            fields[i] = field;
            widgets.add(field);
        }
        panelModeButton = button("panel_expand", 0, 0, 72, this::togglePanelMode);
        selectionExportButton = button("selection_export", 0, 0, 120, this::exportCurrentSelection);
        selectionExportButton.setTooltip(Tooltip.create(tr("selection_export_tooltip")));
        openExportButton = button("export_show_file", 0, 0, 120, this::openLastExport);
        apply = button("apply", 0, 0, 100, this::applyFields);
        copyCoordinates = copyButton("copy_coordinates_short", "copy_coordinates_tooltip", this::copyCoordinates);
        copyArea = copyButton("copy_area_short", "copy_area_tooltip", this::copyArea);
        copyPerimeter = copyButton("copy_perimeter_short", "copy_perimeter_tooltip", this::copyPerimeter);
        copyLength = copyButton("copy_length_short", "copy_length_tooltip", this::copyLength);
        copyWidth = copyButton("copy_width_short", "copy_width_tooltip", this::copyWidth);
        undo = arrowButton("↶", this::undo);
        redo = arrowButton("↷", this::redo);
        chunkMode = button("units_blocks", 0, 0, 100, this::toggleChunks);
        sizeButton = button("size", 0, 0, 100, () -> openDialog(Dialog.SIZE));
        moveButton = button("move", 0, 0, 100, () -> openDialog(Dialog.MOVE));
        centerButton = button("center", 0, 0, 100, () -> openDialog(Dialog.CENTER));
        polygonModeButton = button("polygon_mode", 0, 0, 100, this::togglePolygonMode);
        areasButton = button("areas_window", 0, 0, 100, () -> openDialog(Dialog.AREAS));
        groupsButton = button("groups_window", 0, 0, 100, () -> openGroupsWindow(Dialog.NONE));
        clear = button("clear", 0, 0, 100, this::reset);
        quickSave = button("quick_save", 0, 0, 100, this::quickSave);
        zonesButton = button("zones_in_selection", 0, 0, 100, this::openAreasInSelection);
        zonesButton.setTooltip(Tooltip.create(tr("zones_in_selection_tooltip")));
        pointsButton = button("waypoints_all", 0, 0, 100, this::openWaypoints);
        confirm = button("confirm", 0, 0, 110, this::confirmDelete);
        cancel = button("cancel", 0, 0, 110, this::cancelDialog);
        for (int i = 0; i < dialogFields.length; i++) {
            EditBox field = new EditBox(mc.font, 0, 0, 100, 18, Component.empty());
            field.setMaxLength(i < 4 ? 10 : i == HEX_FIELD ? 7 : 128);
            if (i == HEX_FIELD) field.setHint(Component.literal("#RRGGBB"));
            field.setResponder(value -> { if (!syncing) updateVisibility(); });
            dialogFields[i] = field;
            widgets.add(field);
        }
        dialogFields[4].setResponder(value -> {
            if (syncing) return;
            SavedAreaStore.Area area = selectedAreas().size() == 1 ? selectedArea() : null;
            String name = value.trim();
            areaDraft.name = area == null || name.isEmpty() || name.equals(area.name()) ? null : name;
            updateVisibility();
        });
        modalApply = button("apply", 0, 0, 110, this::applyDialog);
        modalCancel = button("cancel", 0, 0, 110, this::cancelDialog);
        anchorButton = button("anchor_lower_left", 0, 0, 150, () -> {
            sizeFromCenter = !sizeFromCenter;
            anchorButton.setMessage(tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        });
        anchorButton.setMessage(tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        moveUnitsButton = button("units_blocks", 0, 0, 110, () -> {
            moveInChunks = !moveInChunks;
            moveUnitsButton.setMessage(tr(moveInChunks ? "units_chunks" : "units_blocks"));
        });
        moveUnitsButton.setMessage(tr(moveInChunks ? "units_chunks" : "units_blocks"));
        centerCopy = button("copy_center", 0, 0, 130, this::copyCenter);
        centerCreate = button("create_center_waypoint", 0, 0, 180, this::createCenterWaypoint);
        areaSave = button("area_save_selection", 0, 0, 110, () -> { areaNameReturn = Dialog.AREAS; openAreaNameDialog(); });
        areaLoad = button("area_edit", 0, 0, 110, this::loadArea);
        areaDelete = button("area_delete", 0, 0, 110, this::deleteArea);
        areaSaveChanges = button("area_save_changes", 0, 0, 110, () -> commitDraft(field -> true));
        autosaveButton = button("autosave_button", 0, 0, 120, () -> { autosaveOpen = !autosaveOpen; focus(null); updateVisibility(); });
        autosaveToggle = button("autosave_off", 0, 0, 120, () -> {
            autosave.setEnabled(!autosave.enabled());
            if (autosave.enabled()) commitDraft(autosave::covers);
            updateVisibility();
        });
        for (AutosaveSettings.Field field : AutosaveSettings.Field.values())
            autosaveFields.put(field, button("autosave_field", 0, 0, 120, () -> {
                autosave.toggle(field);
                if (autosave.covers(field)) commitDraft(f -> f == field);
                updateVisibility();
            }));
        autosaveClose = button("autosave_close", 0, 0, 120, () -> { autosaveOpen = false; focus(null); updateVisibility(); });
        String[][] copies = {{"copy_coordinates_short", "copy_coordinates_tooltip"}, {"copy_area_short", "copy_area_tooltip"},
                {"copy_perimeter_short", "copy_perimeter_tooltip"}, {"copy_length_short", "copy_length_tooltip"},
                {"copy_width_short", "copy_width_tooltip"}};
        for (int i = 0; i < areaCopies.length; i++) {
            int kind = i;
            areaCopies[i] = copyButton(copies[i][0], copies[i][1], () -> {
                SavedAreaStore.Area area = selectedAreas().size() == 1 ? selectedArea() : null;
                if (area != null) copyMetric(kind, area.geometry(), area.bounds());
            });
        }
        areaSelectAll = button("area_select_all", 0, 0, 110, this::selectAllAreas);
        areaClearSelection = button("area_clear_selection", 0, 0, 110, this::clearAreaSelection);
        groupCreate = button("group_create", 0, 0, 110, () -> openGroupNameDialog(false));
        groupRename = button("group_rename_action", 0, 0, 110, () -> openGroupNameDialog(true));
        groupAssign = button("group_choose", 0, 0, 120, this::openGroupChooser);
        groupVisibility = button("group_toggle", 0, 0, 120, this::toggleGroup);
        areaVisibility = button("area_toggle", 0, 0, 120, this::toggleArea);
        areaColor = button("area_color", 0, 0, 100, () -> openColorDialog(Dialog.AREAS));
        areaOpacity = button("area_fill", 0, 0, 100, this::cycleAreaOpacity);
        areaMapVisibility = button("area_map_on", 0, 0, 120, this::toggleAreaMapVisibility);
        areaMinimapVisibility = button("area_minimap_on", 0, 0, 120, this::toggleAreaMinimapVisibility);
        manageGroups = button("groups_manage", 0, 0, 120,
                () -> openGroupsWindow(dialog == Dialog.ASSIGN_GROUP ? Dialog.ASSIGN_GROUP : Dialog.AREAS));
        managementClose = button("done", 0, 0, 80, this::cancelDialog);
        managementUndo = arrowButton("↶", this::undo);
        managementRedo = arrowButton("↷", this::redo);
        groupDelete = button("group_delete", 0, 0, 120, this::askDeleteGroup);
        areaExportButton = button("area_export", 0, 0, 120, this::exportSelectedArea);
        pointVisibility = button("filter_all", 0, 0, 120, this::cycleVisibilityFilter);
        pointColor = button("filter_color_all", 0, 0, 120, this::cycleColorFilter);
        pointScope = button("point_scope_all", 0, 0, 100, this::togglePointScope);
        pointRename = button("point_rename", 0, 0, 110, this::renamePoints);
        pointRecolor = button("area_color", 0, 0, 110, () -> openColorDialog(Dialog.WAYPOINTS));
        newAreaGroup = button("group_none", 0, 0, 100, this::cycleNewAreaGroup);
        newAreaOpacityButton = button("area_fill", 0, 0, 100, () -> {
            newAreaOpacity = nextOpacity(newAreaOpacity);
            updateVisibility();
        });
        newAreaOpacityButton.setTooltip(Tooltip.create(tr("area_new_opacity_tooltip")));
        colorHexApply = button("color_apply", 0, 0, 90, this::applyHexColor);
        pointsButton.setTooltip(Tooltip.create(tr(waypoints == null ? "minimap_missing" : "waypoints_tooltip")));
        pointScope.setTooltip(Tooltip.create(tr("point_scope_tooltip")));
        pointRename.setTooltip(Tooltip.create(tr("point_rename_tooltip")));
        pointRecolor.setTooltip(Tooltip.create(tr("point_color_tooltip")));
        newAreaGroup.setTooltip(Tooltip.create(tr("area_new_group_tooltip")));
        colorHexApply.setTooltip(Tooltip.create(tr("color_hex_tooltip")));
        areaVisibility.setTooltip(Tooltip.create(tr("area_visibility_rules")));
        groupVisibility.setTooltip(Tooltip.create(tr("group_toggle_tooltip")));
        groupAssign.setTooltip(Tooltip.create(tr("group_picker_tooltip")));
        areaLoad.setTooltip(Tooltip.create(tr("area_edit_tooltip")));
        areaColor.setTooltip(Tooltip.create(tr("area_color_tooltip")));
        areaOpacity.setTooltip(Tooltip.create(tr("area_opacity_tooltip")));
        areaMapVisibility.setTooltip(Tooltip.create(tr("area_map_tooltip")));
        areaMinimapVisibility.setTooltip(Tooltip.create(tr("area_minimap_tooltip")));
        areaDelete.setTooltip(Tooltip.create(tr("area_delete_tooltip")));
        areaSaveChanges.setTooltip(Tooltip.create(tr("area_save_changes_tooltip")));
        autosaveButton.setTooltip(Tooltip.create(tr("autosave_tooltip")));
        areaSelectAll.setTooltip(Tooltip.create(tr("area_select_all_tooltip")));
        areaClearSelection.setTooltip(Tooltip.create(tr("area_clear_selection_tooltip")));
        groupDelete.setTooltip(Tooltip.create(tr("group_delete_tooltip")));
        areaExportButton.setTooltip(Tooltip.create(tr("area_export_tooltip")));
        layoutMainPanel();
        for (AbstractWidget widget : widgets) screen.addWidget(widget);
        if (dirty && draft != null) {
            syncing = true;
            for (int i = 0; i < 4; i++) fields[i].setValue(draft[i]);
            syncing = false;
        } else syncFields();
        if (dialogDraft != null) {
            for (int i = 0; i < dialogFields.length; i++) setDialogValue(i, dialogDraft[i]);
        }
        updateVisibility();
    }

    private void togglePanelMode() {
        compact = !compact;
        panelScroll = 0;
        focus(null);
        layoutMainPanel();
        updateVisibility();
    }

    private void layoutMainPanel() {
        panelWidth = Math.min(compact ? 256 : 420, Math.max(224, screen.width - 68));
        metricsShift = perimeterInline() ? -12 : 0;
        panelHeight = Math.min(contentHeight(), screen.height - PANEL_Y - 8);
        panelScroll = Math.max(0, Math.min(panelScroll, maxPanelScroll()));
        int x = PANEL_X + 8, top = PANEL_Y - (compact ? 0 : panelScroll), y = top + metricsShift, w = panelWidth - 16;
        place(panelModeButton, PANEL_X + panelWidth - 80, PANEL_Y + 4, 72, 16);
        Button[] copies = {copyCoordinates, copyArea, copyPerimeter, copyLength, copyWidth};
        if (compact) {
            place(quickSave, PANEL_X + panelWidth - 86, top + 33, 78, 18);
            mainRow(copies, x, y + 105, w, 16);
            arrowRow(new Button[]{clear, polygonModeButton}, x, y + 128, w);
            mainRow(new Button[]{areasButton, groupsButton}, x, y + 149, w, 18);
        } else {
            fieldWidth = (panelWidth - 16 - coordinateLabelWidth - zLabelWidth - fieldGap * 3) / 2;
            int xField = x + coordinateLabelWidth + fieldGap;
            int zField = xField + fieldWidth + fieldGap * 2 + zLabelWidth;
            for (int i = 0; i < fields.length; i++)
                place(fields[i], i % 2 == 0 ? xField : zField, top + 34 + i / 2 * 24, fieldWidth, 18);
            place(apply, x, top + 82, w, 18);
            mainRow(copies, x, y + 154, w, 16);
            arrowRow(new Button[]{chunkMode, sizeButton, moveButton, centerButton}, x, y + 180, w);
            mainRow(new Button[]{polygonModeButton, quickSave, groupsButton, clear}, x, y + 202, w, 18);
            mainRow(new Button[]{areasButton, zonesButton, pointsButton}, x, y + 224, w, 18);
            place(selectionExportButton, x, y + 246, w, 18);
        }
    }

    private int contentHeight() { return (compact ? COMPACT_HEIGHT : EXPANDED_HEIGHT) + metricsShift; }

    private Component areaText() {
        return state.bounds() == null ? tr("area_empty") : tr("area", formatMetric(state.geometry().area()));
    }

    private Component perimeterText() {
        return tr("perimeter", state.bounds() == null ? "—" : formatLength(state.geometry().perimeter()));
    }

    /** The perimeter goes on the area line when both fit in the panel width. */
    private boolean perimeterInline() {
        return mc.font.width(areaText().getString() + " · " + perimeterText().getString()) <= panelWidth - 16;
    }

    private void arrowRow(Button[] buttons, int x, int y, int width) {
        place(undo, x, y, 20, 18); place(redo, x + 23, y, 20, 18);
        mainRow(buttons, x + 46, y, width - 46, 18);
    }

    private Button arrowButton(String arrow, Runnable action) {
        Button result = Button.builder(Component.literal(arrow), button -> action.run()).bounds(0, 0, 20, 18).build();
        widgets.add(result);
        return result;
    }

    private void mainRow(Button[] buttons, int x, int y, int width, int height) {
        int step = (width + 3) / buttons.length;
        for (int i = 0; i < buttons.length; i++)
            place(buttons[i], x + step * i, y, i == buttons.length - 1 ? width - step * i : step - 3, height);
    }

    private int maxPanelScroll() {
        return compact ? 0 : Math.max(0, contentHeight() - panelHeight + (panelHeight < contentHeight() ? 12 : 0));
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        Button result = Button.builder(tr(key), button -> action.run()).bounds(x, y, width, 18).build();
        widgets.add(result);
        return result;
    }

    private Button copyButton(String labelKey, String tooltipKey, Runnable action) {
        Button result = Button.builder(tr(labelKey), button -> action.run())
                .bounds(0, 0, 100, 16).build();
        result.setTooltip(Tooltip.create(tr(tooltipKey)));
        widgets.add(result);
        return result;
    }

    private void openDialog(Dialog next) {
        if (dirty || !polygonDraft.isEmpty() || holding() || (pending != null && next != Dialog.WAYPOINTS)) return;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        polygonBefore = null;
        dialog = next;
        if (next == Dialog.SIZE) {
            SelectionBounds b = state.bounds();
            setDialogValue(0, b == null ? "1" : Long.toString(b.width()));
            setDialogValue(1, b == null ? "1" : Long.toString(b.length()));
        } else if (next == Dialog.MOVE) {
            setDialogValue(2, "0"); setDialogValue(3, "0");
        } else if (next == Dialog.CENTER) {
            setDialogValue(6, tr("center_waypoint_default").getString());
        } else if (next == Dialog.AREAS) {
            // The window opens with nothing checked; properties apply only to areas the user picks.
            selectedAreaIds.clear();
            areaId = null;
            areaDraft.clear();
            autosaveOpen = false;
            areaDeleteConfirmation = groupDeleteConfirmation = false;
            setDialogValue(4, "");
            message = "";
        } else if (next == Dialog.WAYPOINTS) {
            setDialogValue(7, "");
            visibilityFilter = 0; colorFilter = -1;
            pointVisibility.setMessage(tr("filter_all")); pointColor.setMessage(tr("filter_color_all"));
            chosenPoints.clear();
            if (pending != null) for (WaypointAccess.Point p : pending.points()) chosenPoints.add(p.identity());
        }
        if (next == Dialog.SIZE) anchorButton.setMessage(tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        if (next == Dialog.MOVE) moveUnitsButton.setMessage(tr(moveInChunks ? "units_chunks" : "units_blocks"));
        focus(null);
        updateVisibility();
    }

    private void openGroupsWindow(Dialog returnTo) {
        if (dirty || pending != null || !polygonDraft.isEmpty() || holding()) return;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        polygonBefore = null;
        groupsReturnDialog = returnTo;
        dialog = Dialog.GROUPS;
        groupDeleteConfirmation = false;
        if (selectedGroup() == null && !areaStore.state().groups().isEmpty())
            groupId = areaStore.state().groups().get(0).id();
        message = "";
        focus(null);
        updateVisibility();
    }

    private void openAreaNameDialog() {
        if (state.phase() != SelectionState.Phase.SELECTED || dirty || loadFailed) return;
        dialog = Dialog.AREA_NAME;
        newAreaGroupId = null;
        setDialogValue(8, tr("area_default_name", areaStore.state().areas().size() + 1).getString());
        message = "";
        updateVisibility();
        focus(dialogFields[8]);
    }

    private void openGroupNameDialog(boolean rename) {
        if (loadFailed || rename && selectedGroup() == null) return;
        renamingGroup = rename;
        dialog = Dialog.GROUP_NAME;
        setDialogValue(5, rename ? selectedGroup().name() : "");
        message = "";
        updateVisibility();
        focus(dialogFields[5]);
    }

    private void openGroupChooser() {
        if (selectedAreaIds.isEmpty() || loadFailed) return;
        assignmentTargets.clear();
        assignmentTargets.addAll(selectedAreaIds);
        assignmentScroll = 0;
        dialog = Dialog.ASSIGN_GROUP;
        message = "";
        focus(null);
        updateVisibility();
    }

    private void setDialogValue(int index, String value) {
        syncing = true; dialogFields[index].setValue(value); syncing = false;
    }

    private void cancelDialog() {
        if (dialog == Dialog.AREAS && areaDeleteConfirmation) {
            areaDeleteConfirmation = false;
            message = "";
            focus(null);
            updateVisibility();
            return;
        }
        if (dialog == Dialog.GROUPS && groupDeleteConfirmation) {
            groupDeleteConfirmation = false;
            focus(null);
            updateVisibility();
            return;
        }
        if (dialog == Dialog.WAYPOINTS) pending = null;
        if (dialog == Dialog.AREAS) flushDraft();
        dialog = switch (dialog) {
            case AREA_NAME -> areaNameReturn;
            case COLOR -> colorReturn;
            case ASSIGN_GROUP -> Dialog.AREAS;
            case GROUP_NAME -> Dialog.GROUPS;
            case GROUPS -> groupsReturnDialog;
            default -> Dialog.NONE;
        };
        focus(null);
        updateVisibility();
    }

    private int[] dialogRect() {
        int width = Math.min(switch (dialog) {
            case AREAS, GROUPS -> 760;
            case ASSIGN_GROUP, AREA_NAME, GROUP_NAME, COLOR -> 420;
            default -> 560;
        }, screen.width - 20);
        int height = switch (dialog) {
            case SIZE, MOVE -> Math.min(180, screen.height - 16);
            case CENTER -> Math.min(150, screen.height - 16);
            case AREAS, GROUPS -> Math.min(460, screen.height - 16);
            case ASSIGN_GROUP -> Math.min(330, screen.height - 16);
            case AREA_NAME -> Math.min(210, screen.height - 16);
            case GROUP_NAME -> Math.min(140, screen.height - 16);
            case COLOR -> Math.min(140, screen.height - 16);
            case WAYPOINTS -> Math.min(330, screen.height - 16);
            default -> 0;
        };
        return new int[]{(screen.width - width) / 2, (screen.height - height) / 2, width, height};
    }

    private int areaDialogRows() {
        return Math.max(1, Math.min(24, areaStore.state().areas().size()));
    }

    private int groupDialogRows() {
        return Math.max(1, Math.min(24, areaStore.state().groups().size()));
    }

    private int visibleAreaDialogRows(int height) {
        int fitting = Math.max(1, (height - 128) / 20);
        return Math.min(areaDialogRows(), fitting);
    }

    private int visibleGroupDialogRows(int height) {
        int fitting = Math.max(1, (height - 100) / 20);
        return Math.min(groupDialogRows(), fitting);
    }

    private int visibleAssignmentRows(int height) {
        return Math.max(1, (height - 110) / 20);
    }

    private void place(AbstractWidget widget, int x, int y, int width, int height) {
        widget.setX(x); widget.setY(y); widget.setWidth(Math.max(1, width)); widget.setHeight(height);
    }

    private void layoutDialog() {
        if (dialog == Dialog.NONE) return;
        int[] r = dialogRect(); int x = r[0], y = r[1], w = r[2], h = r[3];
        int buttonY = y + h - 28;
        switch (dialog) {
            case SIZE -> {
                place(dialogFields[0], x + 18, y + 52, (w - 54) / 2, 18);
                place(dialogFields[1], x + 36 + (w - 54) / 2, y + 52, (w - 54) / 2, 18);
                place(anchorButton, x + 18, y + 82, w - 36, 18);
                place(modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case MOVE -> {
                place(dialogFields[2], x + 18, y + 52, (w - 54) / 2, 18);
                place(dialogFields[3], x + 36 + (w - 54) / 2, y + 52, (w - 54) / 2, 18);
                place(moveUnitsButton, x + 18, y + 82, w - 36, 18);
                place(modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case CENTER -> {
                place(dialogFields[6], x + 18, y + 58, w - 36, 18);
                place(centerCopy, x + 18, buttonY, Math.max(70, (w - 48) / 2), 18);
                place(centerCreate, x + 30 + (w - 48) / 2, buttonY, Math.max(70, (w - 48) / 2), 18);
            }
            case AREAS -> {
                if (areaDeleteConfirmation) {
                    place(confirm, x + w / 2 - 116, buttonY, 110, 18);
                    place(cancel, x + w / 2 + 6, buttonY, 110, 18);
                    break;
                }
                int col = (w - 36) / 2, right = x + w - col - 12, half = (col - 3) / 2;
                place(managementUndo, x + w - 232, y + 7, 22, 18);
                place(managementRedo, x + w - 207, y + 7, 22, 18);
                place(manageGroups, x + w - 180, y + 7, 92, 18);
                place(managementClose, x + w - 82, y + 7, 70, 18);
                place(autosaveButton, x + w - 132, y + h - 24, 120, 16);
                if (autosaveOpen) {
                    place(autosaveToggle, right, y + 46, col, 18);
                    int row = 0;
                    for (Button button : autosaveFields.values()) place(button, right, y + 68 + 20 * row++, col, 18);
                    place(autosaveClose, right, y + 68 + 20 * row, col, 18);
                } else {
                    place(dialogFields[4], right, y + 46, col, 18);
                    place(groupAssign, right, y + 66, col, 18);
                    place(areaColor, right, y + 86, half, 18);
                    place(areaOpacity, right + half + 3, y + 86, col - half - 3, 18);
                    place(areaSaveChanges, right, y + 106, col, 18);
                    place(areaLoad, right, y + 126, half, 18);
                    place(areaExportButton, right + half + 3, y + 126, col - half - 3, 18);
                    // "Selected" section: visibility and deletion of every checked area.
                    place(areaVisibility, right, y + 160, half, 18);
                    place(areaDelete, right + half + 3, y + 160, col - half - 3, 18);
                    mainRow(areaCopies, right, y + 194, col, 16);
                }
                place(openExportButton, right, y + h - 44, col, 16);
                place(areaSelectAll, x + 12, y + h - 72, half, 18);
                place(areaClearSelection, x + 15 + half, y + h - 72, col - half - 3, 18);
                place(areaSave, x + 12, y + h - 52, col, 18);
                place(areaMapVisibility, x + 12, y + h - 32, half, 18);
                place(areaMinimapVisibility, x + 15 + half, y + h - 32, col - half - 3, 18);
            }
            case GROUPS -> {
                if (groupDeleteConfirmation) {
                    place(confirm, x + w / 2 - 116, buttonY, 110, 18);
                    place(cancel, x + w / 2 + 6, buttonY, 110, 18);
                    break;
                }
                int col = (w - 36) / 2, right = x + w - col - 12, half = (col - 3) / 2;
                place(managementUndo, x + w - 154, y + 7, 22, 18);
                place(managementRedo, x + w - 129, y + 7, 22, 18);
                place(managementClose, x + w - 102, y + 7, 90, 18);
                place(groupCreate, right, y + 78, col, 18);
                place(groupRename, right, y + 100, col, 18);
                place(groupVisibility, right, y + 122, col, 18);
                place(groupDelete, right, y + 144, col, 18);
            }
            case ASSIGN_GROUP -> {
                place(managementClose, x + w - 82, y + 7, 70, 18);
                place(manageGroups, x + 12, y + h - 36, w - 24, 18);
            }
            case AREA_NAME, GROUP_NAME -> {
                place(dialogFields[dialog == Dialog.AREA_NAME ? 8 : 5], x + 16, y + 48, w - 32, 18);
                if (dialog == Dialog.AREA_NAME) {
                    int half = (w - 35) / 2;
                    place(newAreaGroup, x + 16, y + 72, half, 18);
                    place(newAreaOpacityButton, x + 19 + half, y + 72, w - 35 - half, 18);
                }
                place(modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case COLOR -> {
                place(dialogFields[HEX_FIELD], x + 12, y + 110, w - 24 - 94, 18);
                place(colorHexApply, x + w - 102, y + 110, 90, 18);
            }
            case WAYPOINTS -> {
                place(dialogFields[7], x + 12, y + 38, w - 24, 18);
                int third = (w - 30) / 3;
                place(pointVisibility, x + 12, y + 60, third, 18);
                place(pointColor, x + 15 + third, y + 60, third, 18);
                place(pointScope, x + 18 + 2 * third, y + 60, w - 30 - 2 * third, 18);
                place(dialogFields[RENAME_FIELD], x + 12, y + h - 52, w - 140, 18);
                place(pointRename, x + w - 122, y + h - 52, 110, 18);
                int actionWidth = Math.min(110, (w - 30) / 3);
                int start = x + (w - actionWidth * 3 - 6) / 2;
                place(confirm, start, buttonY, actionWidth, 18);
                place(pointRecolor, start + actionWidth + 3, buttonY, actionWidth, 18);
                place(cancel, start + (actionWidth + 3) * 2, buttonY, actionWidth, 18);
            }
            default -> {}
        }
    }

    private void applyDialog() {
        if (dialog == Dialog.AREA_NAME) { saveArea(); return; }
        if (dialog == Dialog.GROUP_NAME) {
            if (renamingGroup) renameGroup(); else createGroup();
            return;
        }
        try {
            SelectionBounds current = state.bounds();
            if (dialog == Dialog.SIZE) {
                long width = positive(dialogFields[0].getValue());
                long length = positive(dialogFields[1].getValue());
                SelectionBounds next;
                if (sizeFromCenter) {
                    double cx = current == null ? SelectionBounds.block(bridge.exactselection$frame().cameraX()) + 0.5 : current.centerX();
                    double cz = current == null ? SelectionBounds.block(bridge.exactselection$frame().cameraZ()) + 0.5 : current.centerZ();
                    next = SelectionBounds.aroundCenter(cx, cz, width, length);
                } else {
                    int x = current == null ? SelectionBounds.block(bridge.exactselection$frame().cameraX()) : current.minX();
                    int z = current == null ? SelectionBounds.block(bridge.exactselection$frame().cameraZ()) : current.maxZ();
                    next = SelectionBounds.fromLowerLeft(x, z, width, length);
                }
                if (chunks) next = next.expandedToChunks();
                setSelection(next, "resize");
            } else if (dialog == Dialog.MOVE) {
                if (current == null) throw new IllegalArgumentException();
                long dx = Long.parseLong(dialogFields[2].getValue().trim());
                long dz = Long.parseLong(dialogFields[3].getValue().trim());
                if (moveInChunks) { dx = Math.multiplyExact(dx, 16); dz = Math.multiplyExact(dz, 16); }
                if (state.geometry() instanceof PolygonGeometry) {
                    SelectionState.Snapshot before = state.snapshot();
                    state.setGeometry(state.geometry().translated(dx, dz));
                    commitSelection(before, state.snapshot(), "move");
                } else setSelection(current.translated(dx, dz), "move");
            }
            dialog = Dialog.NONE; focus(null); message = ""; updateVisibility();
        } catch (Exception e) { message = tr("invalid_dimensions").getString(); }
    }

    private static long positive(String value) {
        long n = Long.parseLong(value.trim());
        if (n < 1 || n > 60_000_000L) throw new IllegalArgumentException();
        return n;
    }

    private void copyCenter() {
        SelectionBounds b = state.bounds();
        if (b == null) return;
        copyToClipboard(formatCenter(b.centerX()) + ", " + formatCenter(b.centerZ()));
    }

    private static String formatCenter(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static String formatMetric(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private void createCenterWaypoint() {
        SelectionBounds b = state.bounds();
        if (b == null || state.phase() != SelectionState.Phase.SELECTED || waypoints == null) { message = tr("minimap_missing").getString(); return; }
        String name = dialogFields[6].getValue().trim();
        if (name.isEmpty()) name = tr("center_waypoint_default").getString();
        int x = (int) Math.floor(b.centerX()), z = (int) Math.floor(b.centerZ());
        int y = mc.player == null ? 0 : mc.player.blockPosition().getY();
        String finalName = name;
        try {
            WaypointAccess.Point[] point = {waypoints.create(context, x, y, z, finalName)};
            history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { waypoints.removeCreated(context, point[0]); }
                @Override public void redo() throws Exception { point[0] = waypoints.recreate(context, point[0]); }
                @Override public String label() { return tr("history_waypoint").getString(); }
            });
            message = tr("waypoint_created").getString(); dialog = Dialog.NONE; updateSelection();
        } catch (Exception e) { message = tr("save_failed").getString(); }
        focus(null); updateVisibility();
    }

    private boolean changeAreas(SavedAreaStore.State next, String label) {
        SavedAreaStore.State before = areaStore.state();
        if (before.equals(next)) return false;
        try {
            history.execute(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { areaStore.replace(before); syncAreaSelection(); }
                @Override public void redo() throws Exception { areaStore.replace(next); syncAreaSelection(); }
                @Override public String label() { return tr("history_" + label).getString(); }
            });
            return true;
        } catch (Exception e) {
            message = tr("area_save_failed").getString();
            LoggerFactory.getLogger("xaero-exact-selection").error("Could not save saved areas", e);
            return false;
        }
    }

    private void syncAreaSelection() {
        List<SavedAreaStore.Area> areas = areaStore.state().areas();
        Set<String> existing = new HashSet<>();
        for (SavedAreaStore.Area area : areas) existing.add(area.id());
        selectedAreaIds.retainAll(existing);
        if (!selectedAreaIds.contains(areaId)) areaId = selectedAreaIds.stream().reduce((first, last) -> last).orElse(null);
        if (selectedGroup() == null) groupId = null;
        // History replaced the stored areas; a draft based on the old values no longer applies.
        areaDraft.clear();
        setDialogValue(4, selectedAreaIds.size() == 1 && selectedArea() != null ? selectedArea().name() : "");
    }

    /** Writes the draft fields chosen by {@code fields} to every checked area as one undo step. */
    private boolean commitDraft(java.util.function.Predicate<AutosaveSettings.Field> fields) {
        if (loadFailed || selectedAreaIds.isEmpty()) return false;
        if (areaDraft.groupSet && areaDraft.groupId != null && findGroup(areaDraft.groupId) == null) {
            areaDraft.groupSet = false; areaDraft.groupId = null;
        }
        boolean any = false;
        for (AutosaveSettings.Field field : AutosaveSettings.Field.values()) any |= fields.test(field) && areaDraft.pending(field);
        if (!any) return false;
        List<SavedAreaStore.Area> areas = areaDraft.apply(areaStore.state().areas(), Set.copyOf(selectedAreaIds), fields);
        SavedAreaStore.State next = areaState(areas, areaStore.state().groups());
        AreaDraft kept = new AreaDraft();
        kept.name = areaDraft.name; kept.groupSet = areaDraft.groupSet; kept.groupId = areaDraft.groupId;
        kept.color = areaDraft.color; kept.opacity = areaDraft.opacity;
        if (!next.equals(areaStore.state()) && !changeAreas(next, "area_update")) return false;
        // changeAreas resynced and cleared the whole draft; keep the fields that were not written.
        kept.clear(fields);
        areaDraft.name = kept.name; areaDraft.groupSet = kept.groupSet; areaDraft.groupId = kept.groupId;
        areaDraft.color = kept.color; areaDraft.opacity = kept.opacity;
        SavedAreaStore.Area single = selectedAreaIds.size() == 1 ? selectedArea() : null;
        setDialogValue(4, areaDraft.name != null ? areaDraft.name : single == null ? "" : single.name());
        message = tr("area_changes_saved").getString();
        updateVisibility();
        return true;
    }

    /** Before the checked areas change or the window closes: autosaved fields are written, the rest dropped. */
    private void flushDraft() {
        if (areaDraft.isEmpty()) return;
        commitDraft(autosave::covers);
        if (!areaDraft.isEmpty()) {
            areaDraft.clear();
            message = tr("area_changes_discarded").getString();
        }
    }

    /** Records a changed draft field and writes it at once when autosave covers it. */
    private void stage(AutosaveSettings.Field field) {
        if (autosave.covers(field)) commitDraft(f -> f == field);
        updateVisibility();
    }

    private SavedAreaStore.Area selectedArea() {
        return areaStore.state().areas().stream().filter(a -> Objects.equals(a.id(), areaId)).findFirst().orElse(null);
    }

    private SavedAreaStore.Group selectedGroup() {
        return findGroup(groupId);
    }

    private SavedAreaStore.Group findGroup(String id) {
        return areaStore.state().groups().stream().filter(g -> Objects.equals(g.id(), id)).findFirst().orElse(null);
    }

    private List<SavedAreaStore.Area> selectedAreas() {
        return areaStore.state().areas().stream().filter(a -> selectedAreaIds.contains(a.id())).toList();
    }

    private void saveArea() {
        SelectionBounds b = state.bounds();
        if (b == null || dirty || state.phase() != SelectionState.Phase.SELECTED) return;
        String name = dialogFields[8].getValue().trim();
        if (name.isEmpty()) name = tr("area_default_name", areaStore.state().areas().size() + 1).getString();
        AreaGeometry geometry = state.geometry() == null ? new RectangleGeometry(b) : state.geometry();
        SavedAreaStore.Area area = SavedAreaStore.create(name, geometry, newAreaColor, newAreaOpacity,
                findGroup(newAreaGroupId) == null ? null : newAreaGroupId);
        List<SavedAreaStore.Area> areas = new ArrayList<>(areaStore.state().areas()); areas.add(area);
        if (!changeAreas(areaState(areas, areaStore.state().groups()), "area_save")) return;
        selectedAreaIds.clear(); selectedAreaIds.add(area.id()); areaId = area.id();
        setDialogValue(4, area.name());
        dialog = areaNameReturn;
        if (dialog == Dialog.AREAS) areaScroll = Math.max(0, areas.size() - visibleAreaDialogRows(dialogRect()[3]));
        // The saved shape now lives in the list; clearing it (its own undo step) frees the map for the next area.
        reset();
        message = tr(areaNameReturn == Dialog.NONE ? "area_saved_named" : "area_created", name).getString();
        focus(null); updateVisibility();
    }

    private void loadArea() {
        SavedAreaStore.Area area = selectedArea();
        if (area == null) return;
        flushDraft();
        SelectionState.Snapshot before = state.snapshot();
        state.load(area.geometry(), area.id());
        polygonMode = false;
        commitSelection(before, state.snapshot(), "area_load");
        dialog = Dialog.NONE;
        message = tr(state.geometry() instanceof PolygonGeometry ? "polygon_loaded" : "area_loaded").getString();
        focus(null);
        updateVisibility();
    }

    private SavedAreaStore.Area editedArea() {
        return areaStore.state().areas().stream().filter(a -> Objects.equals(a.id(), state.source())).findFirst().orElse(null);
    }

    private void quickSave() {
        if (editedArea() != null) applyEditedArea();
        else { areaNameReturn = Dialog.NONE; openAreaNameDialog(); }
    }

    private void applyEditedArea() {
        SavedAreaStore.Area area = editedArea();
        AreaGeometry geometry = state.geometry();
        if (area == null || geometry == null || dirty || !polygonDraft.isEmpty() || holding() || loadFailed
                || state.phase() != SelectionState.Phase.SELECTED || area.geometry().equals(geometry)) return;
        List<SavedAreaStore.Area> areas = areaStore.state().areas().stream()
                .map(a -> a.id().equals(area.id()) ? a.withGeometry(geometry) : a).toList();
        if (changeAreas(areaState(areas, areaStore.state().groups()), "area_update"))
            message = tr("area_applied", area.name()).getString();
        updateVisibility();
    }

    private void cycleNewAreaGroup() {
        List<SavedAreaStore.Group> groups = areaStore.state().groups();
        int index = -1;
        for (int i = 0; i < groups.size(); i++) if (Objects.equals(groups.get(i).id(), newAreaGroupId)) index = i;
        newAreaGroupId = index + 1 < groups.size() ? groups.get(index + 1).id() : null;
        updateVisibility();
    }

    private void deleteArea() {
        if (checkedFrame() == null || !enabled() || selectedAreas().isEmpty() || loadFailed) return;
        areaDeleteConfirmation = true;
        message = "";
        updateVisibility();
        focus(confirm);
    }

    private void confirmAreaDelete() {
        if (checkedFrame() == null || !enabled() || dialog != Dialog.AREAS || !areaDeleteConfirmation || loadFailed) return;
        List<SavedAreaStore.Area> targets = selectedAreas();
        if (targets.isEmpty()) {
            areaDeleteConfirmation = false;
            updateVisibility();
            return;
        }
        Set<String> ids = new HashSet<>(selectedAreaIds);
        List<SavedAreaStore.Area> areas = new ArrayList<>(areaStore.state().areas());
        areas.removeIf(area -> ids.contains(area.id()));
        int count = targets.size();
        if (!changeAreas(areaState(areas, areaStore.state().groups()), "area_delete")) return;
        areaId = areas.isEmpty() ? null : areas.get(0).id();
        selectedAreaIds.clear();
        if (areaId != null) selectedAreaIds.add(areaId);
        setDialogValue(4, selectedArea() == null ? "" : selectedArea().name());
        message = tr("area_deleted", count).getString();
        areaDeleteConfirmation = false;
        focus(null);
        updateVisibility();
    }

    private void selectAllAreas() {
        flushDraft();
        selectedAreaIds.clear();
        for (SavedAreaStore.Area area : areaStore.state().areas()) selectedAreaIds.add(area.id());
        if (!selectedAreaIds.isEmpty()) {
            List<SavedAreaStore.Area> areas = areaStore.state().areas();
            areaId = areas.get(areas.size() - 1).id();
        }
        setDialogValue(4, selectedAreaIds.size() == 1 && selectedArea() != null ? selectedArea().name() : "");
        updateVisibility();
    }

    private void clearAreaSelection() {
        flushDraft();
        selectedAreaIds.clear();
        areaId = null;
        setDialogValue(4, "");
        updateVisibility();
    }

    private void toggleArea() {
        List<SavedAreaStore.Area> targets = selectedAreas();
        if (targets.isEmpty()) return;
        boolean visible = targets.stream().noneMatch(SavedAreaStore.Area::visible);
        List<SavedAreaStore.Area> areas = areaStore.state().areas().stream().map(a -> selectedAreaIds.contains(a.id())
                ? new SavedAreaStore.Area(a.id(), a.name(), a.x1(), a.z1(), a.x2(), a.z2(), a.color(), a.opacity(), a.groupId(), visible, a.vertices()) : a).toList();
        if (changeAreas(areaState(areas, areaStore.state().groups()), "area_update"))
            message = tr(visible ? "areas_shown" : "areas_hidden", targets.size()).getString();
    }

    private void openColorDialog(Dialog returnTo) {
        if (returnTo == Dialog.AREAS && (selectedAreas().isEmpty() || loadFailed)) return;
        if (returnTo == Dialog.WAYPOINTS && !validatePointSnapshot()) return;
        if (returnTo == Dialog.WAYPOINTS && filteredChosenPoints().isEmpty()) return;
        colorReturn = returnTo;
        dialog = Dialog.COLOR;
        SavedAreaStore.Area area = selectedArea();
        if (area == null && !selectedAreas().isEmpty()) area = selectedAreas().get(0);
        setDialogValue(HEX_FIELD, AreaPalette.format(areaDraft.color != null ? areaDraft.color
                : area == null ? AreaPalette.DEFAULT : area.color()));
        message = ""; focus(null); updateVisibility();
    }

    private void applyHexColor() {
        int color = AreaPalette.parse(dialogFields[HEX_FIELD].getValue());
        if (color < 0) { message = tr("color_invalid").getString(); return; }
        applyColor(color);
    }

    private void applyColor(int color) {
        if (colorReturn == Dialog.WAYPOINTS) {
            editPoints(null, color);
            return;
        }
        if (loadFailed) return;
        areaDraft.color = color;
        dialog = Dialog.AREAS; message = ""; focus(null);
        stage(AutosaveSettings.Field.COLOR);
    }

    private AreaGeometry overlapGeometry;
    private SavedAreaStore.State overlapState;
    private Set<String> overlapIds = Set.of();

    /** Ids of saved areas sharing a positive area with the finished selection; cached per selection and store state. */
    private Set<String> areasInSelection() {
        AreaGeometry geometry = state.phase() == SelectionState.Phase.SELECTED ? state.geometry() : null;
        if (geometry != overlapGeometry || areaStore.state() != overlapState) {
            overlapGeometry = geometry;
            overlapState = areaStore.state();
            Set<String> ids = new LinkedHashSet<>();
            if (geometry != null) for (SavedAreaStore.Area area : overlapState.areas())
                if (area.geometry().overlaps(geometry)) ids.add(area.id());
            overlapIds = ids;
        }
        return overlapIds;
    }

    private void openAreasInSelection() {
        Set<String> ids = areasInSelection();
        if (ids.isEmpty()) return;
        openDialog(Dialog.AREAS);
        if (dialog != Dialog.AREAS) return;
        selectedAreaIds.addAll(ids);
        areaId = ids.stream().reduce((first, last) -> last).orElse(null);
        setDialogValue(4, selectedAreaIds.size() == 1 && selectedArea() != null ? selectedArea().name() : "");
        message = tr("zones_in_selection_opened", ids.size()).getString();
        updateVisibility();
    }

    private int[] paletteColors() {
        if (colorReturn != Dialog.WAYPOINTS) return AreaPalette.COLORS;
        int[] colors = new int[WaypointAccess.COLOR_COUNT];
        for (int i = 0; i < colors.length; i++) colors[i] = waypoints.colorRgb(i);
        return colors;
    }

    private int colorSampleWidth(int width) { return Math.min(40, (width - 24 - 28) / AreaPalette.COLUMNS); }

    /** Top of the swatch grid: below the title in the color window, below group and fill in the new-area window. */
    private int swatchTop(int y) { return y + (dialog == Dialog.AREA_NAME ? 98 : 34); }

    /** The swatch under the pointer, or -1. */
    private int swatchAt(int x, int y, int w, int count, double mouseX, double mouseY) {
        int sw = colorSampleWidth(w);
        for (int i = 0; i < count; i++) {
            int sx = x + 12 + (i % AreaPalette.COLUMNS) * (sw + 4), sy = swatchTop(y) + (i / AreaPalette.COLUMNS) * 22;
            if (mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + 18) return i;
        }
        return -1;
    }

    private void renderSwatches(GuiGraphics g, int x, int y, int w, int[] colors, int current) {
        int sw = colorSampleWidth(w);
        for (int i = 0; i < colors.length; i++) {
            int sx = x + 12 + (i % AreaPalette.COLUMNS) * (sw + 4), sy = swatchTop(y) + (i / AreaPalette.COLUMNS) * 22;
            g.fill(sx, sy, sx + sw, sy + 18, 0xFF000000 | colors[i]);
            g.renderOutline(sx, sy, sw, 18, current == colors[i] ? -1 : 0xFF42636D);
        }
    }

    private void renderColorDialog(GuiGraphics g, int x, int y, int w, int h) {
        List<WaypointAccess.Point> points = filteredChosenPoints();
        int current = colorReturn == Dialog.WAYPOINTS
                ? points.isEmpty() ? -1 : waypoints.colorRgb(points.get(0).color())
                : AreaPalette.parse(dialogFields[HEX_FIELD].getValue());
        renderSwatches(g, x, y, w, paletteColors(), current);
        if (!message.isEmpty()) g.drawString(mc.font, mc.font.plainSubstrByWidth(message, w - 24),
                x + 12, y + (colorReturn == Dialog.WAYPOINTS ? 86 : 98), 0xFFFFCF62);
    }

    private void cycleAreaOpacity() {
        SavedAreaStore.Area a = selectedArea();
        if (a == null && !selectedAreas().isEmpty()) a = selectedAreas().get(0);
        if (a == null || loadFailed) return;
        areaDraft.opacity = nextOpacity(areaDraft.opacity != null ? areaDraft.opacity : a.opacity());
        stage(AutosaveSettings.Field.OPACITY);
    }

    /** The fill step after the one closest to {@code opacity}: 0 → 25 → 50 → 75 → 100 → 0. */
    private static int nextOpacity(int opacity) {
        int[] values = {0, 25, 50, 75, 100};
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - opacity) < Math.abs(values[index] - opacity)) index = i;
        }
        return values[(index + 1) % values.length];
    }

    private void updateArea(SavedAreaStore.Area old, SavedAreaStore.Area next, String label) {
        List<SavedAreaStore.Area> areas = new ArrayList<>(areaStore.state().areas());
        areas.replaceAll(v -> v.id().equals(old.id()) ? next : v);
        changeAreas(areaState(areas, areaStore.state().groups()), label);
    }

    private void createGroup() {
        String name = dialogFields[5].getValue().trim();
        if (name.isEmpty() || loadFailed) return;
        List<SavedAreaStore.Group> groups = new ArrayList<>(areaStore.state().groups());
        SavedAreaStore.Group group = new SavedAreaStore.Group(UUID.randomUUID().toString(), name, true);
        groups.add(group);
        if (!changeAreas(areaState(areaStore.state().areas(), groups), "group_create")) return;
        groupId = group.id();
        dialog = Dialog.GROUPS;
        groupScroll = Math.max(0, groups.size() - visibleGroupDialogRows(dialogRect()[3]));
        message = tr("group_created", group.name()).getString();
        focus(null); updateVisibility();
    }

    private void renameGroup() {
        SavedAreaStore.Group current = selectedGroup();
        String name = dialogFields[5].getValue().trim();
        if (current == null || name.isEmpty() || loadFailed) return;
        List<SavedAreaStore.Group> groups = new ArrayList<>(areaStore.state().groups());
        groups.replaceAll(group -> group.id().equals(current.id())
                ? new SavedAreaStore.Group(group.id(), name, group.visible()) : group);
        SavedAreaStore.State next = areaState(areaStore.state().areas(), groups);
        if (!areaStore.state().equals(next) && !changeAreas(next, "group_rename")) return;
        dialog = Dialog.GROUPS;
        message = tr("group_renamed").getString();
        focus(null); updateVisibility();
    }

    private void assignGroup(String nextGroupId) {
        if (dialog != Dialog.ASSIGN_GROUP || assignmentTargets.isEmpty() || loadFailed) return;
        SavedAreaStore.Group group = findGroup(nextGroupId);
        if (nextGroupId != null && group == null) return;
        int count = assignmentTargets.size();
        areaDraft.groupSet = true; areaDraft.groupId = nextGroupId;
        dialog = Dialog.AREAS;
        assignmentTargets.clear();
        message = tr(autosave.covers(AutosaveSettings.Field.GROUP) ? "group_assigned" : "group_staged",
                count, group == null ? tr("group_none").getString() : group.name()).getString();
        focus(null);
        stage(AutosaveSettings.Field.GROUP);
    }

    private void toggleGroup() {
        SavedAreaStore.Group g = selectedGroup(); if (g == null || loadFailed) return;
        List<SavedAreaStore.Group> groups = new ArrayList<>(areaStore.state().groups());
        groups.replaceAll(v -> v.id().equals(g.id()) ? new SavedAreaStore.Group(v.id(), v.name(), !v.visible()) : v);
        if (changeAreas(areaState(areaStore.state().areas(), groups), "group_toggle"))
            message = tr(g.visible() ? "group_hidden" : "group_shown", g.name()).getString();
    }

    private void exportSelectedArea() {
        MapBridge.Frame frame = checkedFrame();
        SavedAreaStore.Area area = selectedArea();
        if (area == null || frame == null || loadFailed || exportRunning) return;
        exportGeometry(frame, area.geometry());
    }

    private boolean canExportSelection() {
        return enabled() && dialog == Dialog.NONE && state.phase() == SelectionState.Phase.SELECTED
                && state.geometry() != null && !dirty && !dragging && gesture == null
                && polygonDraft.isEmpty() && !loadFailed && !exportRunning;
    }

    private void exportCurrentSelection() {
        MapBridge.Frame frame = checkedFrame();
        if (frame == null || !canExportSelection()) return;
        exportGeometry(frame, state.geometry());
    }

    private void exportGeometry(MapBridge.Frame frame, AreaGeometry geometry) {
        java.util.concurrent.CompletableFuture<AreaExportService.Result> pendingExport;
        try {
            pendingExport = AreaExportService.export(bridge.exactselection$processor(),
                    frame.context(), geometry, areaStore.state().areas(), areaStore.state().groups());
        } catch (AreaExportService.TooLargeException e) {
            message = tr("area_export_too_large", AreaExportService.MAX_PIXELS).getString();
            updateVisibility();
            return;
        } catch (Exception e) {
            exportFailed(e);
            return;
        }
        exportRunning = true;
        message = tr("area_exporting").getString();
        // The image is composed in the background; the result returns to the client thread for the UI.
        pendingExport.whenComplete((result, failure) -> mc.execute(() -> {
            exportRunning = false;
            if (failure == null) exportCompleted(result);
            else exportFailed(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure);
        }));
        updateVisibility();
    }

    private void exportFailed(Throwable failure) {
        message = tr("area_export_failed").getString();
        LoggerFactory.getLogger("xaero-exact-selection").error("Could not export selected area", failure);
        updateVisibility();
    }

    private void exportCompleted(AreaExportService.Result result) {
        lastExport = result.file().toAbsolutePath().normalize();
        exportNotice = tr("area_exported", result.width(), result.height()).getString();
        message = exportNotice;
        openExportButton.setTooltip(Tooltip.create(Component.literal(lastExport.getFileName().toString())
                .append(" · ").append(exportNotice)));
        layoutMainPanel();
        layoutDialog();
        updateVisibility();
    }

    private boolean hasExportNotice() {
        return lastExport != null && !exportNotice.isEmpty() && message.equals(exportNotice);
    }

    private void openLastExport() {
        if (lastExport == null) return;
        if (!Files.isRegularFile(lastExport)) {
            message = tr("export_file_missing").getString();
            updateVisibility();
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    desktop.browseFileDirectory(lastExport.toFile());
                    return;
                }
            }
        } catch (RuntimeException unavailable) { }
        net.minecraft.util.Util.getPlatform().openPath(lastExport.getParent());
    }

    private void askDeleteGroup() {
        if (selectedGroup() == null || loadFailed) return;
        groupDeleteConfirmation = true;
        updateVisibility();
        focus(confirm);
    }

    private void confirmGroupDelete() {
        SavedAreaStore.Group group = selectedGroup();
        if (group == null || !groupDeleteConfirmation || loadFailed) return;
        List<SavedAreaStore.Group> groups = areaStore.state().groups().stream()
                .filter(g -> !g.id().equals(group.id())).toList();
        List<SavedAreaStore.Area> areas = areaStore.state().areas().stream().map(area ->
                Objects.equals(area.groupId(), group.id())
                        ? new SavedAreaStore.Area(area.id(), area.name(), area.x1(), area.z1(), area.x2(), area.z2(),
                                area.color(), area.opacity(), null, area.visible(), area.vertices()) : area).toList();
        int affected = (int) areaStore.state().areas().stream().filter(a -> Objects.equals(a.groupId(), group.id())).count();
        if (!changeAreas(areaState(areas, groups), "group_delete")) return;
        groupId = null;
        message = tr("group_deleted", affected).getString();
        groupDeleteConfirmation = false;
        focus(null);
        updateVisibility();
    }

    private SavedAreaStore.State areaState(List<SavedAreaStore.Area> areas, List<SavedAreaStore.Group> groups) {
        SavedAreaStore.State current = areaStore.state();
        return new SavedAreaStore.State(areas, groups, current.showOnMap(), current.showOnMinimap());
    }

    private void toggleAreaMapVisibility() {
        SavedAreaStore.State current = areaStore.state();
        changeAreas(new SavedAreaStore.State(current.areas(), current.groups(), !current.showOnMap(), current.showOnMinimap()), "area_map_visibility");
    }

    private void toggleAreaMinimapVisibility() {
        SavedAreaStore.State current = areaStore.state();
        changeAreas(new SavedAreaStore.State(current.areas(), current.groups(), current.showOnMap(), !current.showOnMinimap()), "area_minimap_visibility");
    }

    private void cycleVisibilityFilter() {
        visibilityFilter = (visibilityFilter + 1) % 3;
        pointVisibility.setMessage(tr(new String[]{"filter_all", "filter_visible", "filter_hidden"}[visibilityFilter]));
    }

    private void cycleColorFilter() {
        colorFilter = colorFilter < 0 ? 0 : colorFilter >= 15 ? -1 : colorFilter + 1;
        pointColor.setMessage(tr(colorFilter < 0 ? "filter_color_all" : "filter_color", colorFilter));
    }

    public boolean enabled() { return state.enabled(); }
    public boolean confirmationOpen() { return dialog != Dialog.NONE; }
    public boolean coordinateFieldFocused() {
        return screen.getFocused() instanceof EditBox field && widgets.contains(field);
    }

    private SelectionBounds copyableBounds() {
        return enabled() && !dirty && pending == null ? state.bounds() : null;
    }

    private void copyCoordinates() { copySelection(0); }
    private void copyArea() { copySelection(1); }
    private void copyPerimeter() { copySelection(2); }
    private void copyLength() { copySelection(3); }
    private void copyWidth() { copySelection(4); }

    private void copySelection(int kind) {
        SelectionBounds b = copyableBounds();
        if (b != null) copyMetric(kind, state.geometry(), b);
    }

    /** Copies one value of a shape: 0 coordinates, 1 area, 2 perimeter, 3 length, 4 width. */
    private void copyMetric(int kind, AreaGeometry geometry, SelectionBounds b) {
        copyToClipboard(switch (kind) {
            case 0 -> geometry instanceof PolygonGeometry polygon
                    ? polygon.vertices().stream().map(p -> p.x() + "," + p.z()).collect(java.util.stream.Collectors.joining("; "))
                    : b.x1() + ", " + b.z1() + "; " + b.x2() + ", " + b.z2();
            case 1 -> formatMetric(geometry.area());
            case 2 -> formatLength(geometry.perimeter());
            case 3 -> Long.toString(b.length());
            default -> Long.toString(b.width());
        });
    }

    private static String formatLength(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private void copyToClipboard(String value) {
        mc.keyboardHandler.setClipboard(value);
        message = tr("copied").getString();
    }

    private void toggle() {
        if (enabled()) { disable(); return; }
        MapBridge.Frame frame = bridge.exactselection$frame();
        if (frame == null) return;
        bridge.exactselection$clearNativeInput();
        screen.clearFocus();
        state.enable();
        context = frame.context();
        message = "";
        syncFields();
        updateVisibility();
    }

    private void togglePolygonMode() {
        if (!enabled() || holding()) return;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        if (polygonMode) {
            polygonMode = false;
            polygonDraft.clear();
            polygonCursor = null;
            polygonBefore = null;
            message = tr("polygon_cancelled").getString();
        } else {
            if (state.phase() == SelectionState.Phase.PREVIEW) {
                if (!(gesture instanceof MapGesture.Corners corners && corners.cancel(gestureHost))) state.reset();
                syncFields();
            }
            polygonMode = true;
            polygonDraft.clear();
            polygonCursor = null;
            polygonBefore = state.snapshot();
            message = tr("polygon_controls").getString();
        }
        updateSelection();
        updateVisibility();
    }

    private GridPoint polygonPoint(MapBridge.Frame frame, double x, double y, GridPoint anchor) {
        long wx = Math.round(frame.worldX(x)), wz = Math.round(frame.worldZ(y));
        if (shiftDown && anchor != null) {
            if (Math.abs(wx - anchor.x()) >= Math.abs(wz - anchor.z())) wz = anchor.z();
            else wx = anchor.x();
        }
        return new GridPoint(wx, wz);
    }

    private int hitPolygonEdge(MapBridge.Frame frame, List<GridPoint> vertices, double x, double y) {
        if (hitVertex(frame, vertices, x, y) >= 0) return -1;
        int nearest = -1;
        double distance = 64;
        for (int i = 0; i < vertices.size(); i++) {
            GridPoint a = vertices.get(i), b = vertices.get((i + 1) % vertices.size());
            double ax = frame.screenX(a.x()), ay = frame.screenY(a.z());
            double dx = frame.screenX(b.x()) - ax, dy = frame.screenY(b.z()) - ay;
            double length = dx * dx + dy * dy;
            if (length == 0) continue;
            double t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length));
            double ex = x - ax - t * dx, ey = y - ay - t * dy;
            double candidate = ex * ex + ey * ey;
            if (candidate <= distance) { nearest = i; distance = candidate; }
        }
        return nearest;
    }

    private void insertPolygonPoint(MapBridge.Frame frame, PolygonGeometry polygon, int edge, double x, double y) {
        if (polygon.vertices().size() >= PolygonGeometry.MAX_VERTICES) {
            message = tr("polygon_too_many_points").getString(); return;
        }
        try {
            List<GridPoint> points = new ArrayList<>(polygon.vertices());
            // The hit tolerance is in screen pixels, which can span many blocks when zoomed out; placing the vertex
            // under the cursor would bend the side. Insert on the side itself; Ctrl/Cmd-drag moves it afterwards.
            GridPoint a = points.get(edge), b = points.get((edge + 1) % points.size());
            double ex = b.x() - a.x(), ez = b.z() - a.z();
            double t = ((frame.worldX(x) - a.x()) * ex + (frame.worldZ(y) - a.z()) * ez) / (ex * ex + ez * ez);
            t = Math.max(0, Math.min(1, t));
            GridPoint point = new GridPoint(Math.round(a.x() + t * ex), Math.round(a.z() + t * ez));
            if (points.contains(point)) { message = tr("polygon_duplicate_point").getString(); return; }
            points.add(edge + 1, point);
            PolygonGeometry next = new PolygonGeometry(points);
            next.triangulate();
            SelectionState.Snapshot before = state.snapshot();
            state.setGeometry(next);
            commitSelection(before, state.snapshot(), "vertex");
            message = tr("polygon_vertex_inserted").getString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            message = tr("polygon_invalid").getString();
        }
        updateVisibility();
    }

    private void addPolygonPoint(MapBridge.Frame frame, double mouseX, double mouseY) {
        if (polygonDraft.size() >= 3) {
            GridPoint first = polygonDraft.get(0);
            double dx = frame.screenX(first.x()) - mouseX, dy = frame.screenY(first.z()) - mouseY;
            if (dx * dx + dy * dy <= 64.0) { finishPolygon(); return; }
        }
        GridPoint point;
        try { point = polygonPoint(frame, mouseX, mouseY, polygonDraft.isEmpty() ? null : polygonDraft.getLast()); }
        catch (IllegalArgumentException e) { message = tr("invalid_coordinates").getString(); return; }
        if (polygonDraft.contains(point)) { message = tr("polygon_duplicate_point").getString(); return; }
        if (polygonDraft.size() >= PolygonGeometry.MAX_VERTICES) {
            message = tr("polygon_too_many_points").getString(); return;
        }
        if (polygonDraft.isEmpty()) polygonBefore = state.snapshot();
        recordDraftChange(List.copyOf(polygonDraft));
        polygonDraft.add(point);
        updateSelection();
        message = tr("polygon_point_count", polygonDraft.size()).getString();
        updateVisibility();
    }

    private void finishPolygon() {
        if (polygonDraft.size() < 3) { message = tr("polygon_minimum_points").getString(); return; }
        try {
            PolygonGeometry polygon = new PolygonGeometry(polygonDraft);
            SelectionState.Snapshot before = polygonBefore == null ? state.snapshot() : polygonBefore;
            state.setGeometry(polygon);
            gesture = null;
            polygonDraft.clear();
            polygonDraftUndo.clear(); polygonDraftRedo.clear();
            polygonCursor = null;
            polygonBefore = null;
            commitSelection(before, state.snapshot(), "polygon");
            message = tr("polygon_created").getString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            message = tr("polygon_invalid").getString();
        }
        updateVisibility();
    }

    private void recordDraftChange(List<GridPoint> before) {
        polygonDraftUndo.push(before);
        polygonDraftRedo.clear();
        while (polygonDraftUndo.size() > 100) polygonDraftUndo.removeLast();
    }

    private void removeLastPolygonPoint() {
        if (polygonDraft.isEmpty() || holding()) return;
        recordDraftChange(List.copyOf(polygonDraft));
        polygonDraft.remove(polygonDraft.size() - 1);
        message = tr("polygon_point_removed").getString();
        updateVisibility();
    }

    private int hitVertex(MapBridge.Frame frame, List<GridPoint> vertices, double x, double y) {
        int nearest = -1;
        double distance = 64;
        for (int i = 0; i < vertices.size(); i++) {
            GridPoint point = vertices.get(i);
            double dx = frame.screenX(point.x()) - x, dy = frame.screenY(point.z()) - y;
            double candidate = dx * dx + dy * dy;
            if (candidate <= distance) { nearest = i; distance = candidate; }
        }
        return nearest;
    }

    private void deleteVertex(MapBridge.Frame frame, double x, double y) {
        if (polygonMode && !polygonDraft.isEmpty()) {
            int index = hitVertex(frame, polygonDraft, x, y);
            if (index < 0) return;
            recordDraftChange(List.copyOf(polygonDraft)); polygonDraft.remove(index); polygonCursor = null;
            message = tr("polygon_vertex_deleted").getString(); updateSelection(); updateVisibility();
            return;
        }
        if (state.phase() != SelectionState.Phase.SELECTED || state.geometry() == null) return;
        List<GridPoint> points = new ArrayList<>(state.geometry().vertices());
        int index = hitVertex(frame, points, x, y);
        if (index < 0) return;
        if (points.size() <= 3) { message = tr("polygon_minimum_points").getString(); return; }
        try {
            SelectionState.Snapshot before = state.snapshot(); points.remove(index);
            PolygonGeometry next = new PolygonGeometry(points); next.triangulate();
            state.setGeometry(next); commitSelection(before, state.snapshot(), "vertex_delete");
            message = tr("polygon_vertex_deleted").getString();
        } catch (IllegalArgumentException | IllegalStateException e) { message = tr("polygon_invalid").getString(); }
        updateVisibility();
    }

    private void beginVertexDrag(MapBridge.Frame frame, double x, double y) {
        List<GridPoint> points = polygonMode && !polygonDraft.isEmpty() ? polygonDraft
                : state.geometry() != null && state.phase() == SelectionState.Phase.SELECTED ? state.geometry().vertices() : List.of();
        int index = hitVertex(frame, points, x, y);
        if (index < 0) return;
        gesture = new MapGesture.VertexDrag(state.snapshot(), index, Math.round(frame.worldX(x)), Math.round(frame.worldZ(y)),
                polygonMode && !polygonDraft.isEmpty() ? List.copyOf(points) : null);
        polygonCursor = null;
        message = tr("polygon_vertex_drag").getString();
        updateVisibility();
    }

    /** Cancels the current gesture and resyncs the panel; false when there was none. */
    private boolean cancelGesture() {
        MapGesture current = gesture;
        if (current == null || !current.cancel(gestureHost)) return false;
        refreshSelectionState();
        return true;
    }

    private boolean holding() { return gesture instanceof MapGesture.Held; }

    /** A change made while a two-click selection is open is recorded from before its first click. */
    private SelectionState.Snapshot historyBase() {
        return state.phase() == SelectionState.Phase.PREVIEW && gesture instanceof MapGesture.Corners corners
                ? corners.before : state.snapshot();
    }

    // A preview restored from history has no gesture; its first corner lies in the same chunk as the origin.
    private int chunkOriginX() { return gesture instanceof MapGesture.Corners corners ? corners.originX : state.bounds().x1(); }

    private int chunkOriginZ() { return gesture instanceof MapGesture.Corners corners ? corners.originZ : state.bounds().z1(); }

    public void disable() {
        if (enabled()) bridge.exactselection$clearNativeInput();
        if (state.bounds() != null) {
            var before = historyBase();
            state.disable();
            history.recordApplied(selectionAction(before, state.snapshot(), "disable"));
        } else state.disable();
        selected = pending = null;
        context = null;
        dragging = dirty = false;
        gesture = null;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        polygonMode = false; polygonDraft.clear(); polygonCursor = null; polygonBefore = null;
        dialog = Dialog.NONE;
        message = "";
        focus(null);
        updateVisibility();
    }

    private void reset() {
        var before = historyBase();
        state.reset();
        if (!before.equals(state.snapshot())) history.recordApplied(selectionAction(before, state.snapshot(), "clear"));
        selected = pending = null;
        polygonDraft.clear(); polygonCursor = null; polygonBefore = null;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        gesture = null;
        dirty = dragging = false;
        message = "";
        focus(null);
        syncFields();
        updateVisibility();
    }

    private MapBridge.Frame checkedFrame() {
        MapBridge.Frame frame = bridge.exactselection$frame();
        if (enabled() && (frame == null || !Objects.equals(context, frame.context()))) disable();
        if (frame != null && !Objects.equals(historyContext, frame.context())) {
            history.clear();
            historyContext = frame.context();
            areaId = groupId = null; areaScroll = groupScroll = 0;
            selectedAreaIds.clear();
            try { areaStore.useContext(frame.context()); loadFailed = areaStore.loadFailed(); }
            catch (Exception e) {
                loadFailed = true;
                message = tr("area_load_failed").getString();
                LoggerFactory.getLogger("xaero-exact-selection").error("Could not load saved areas", e);
            }
        }
        return frame;
    }

    private SelectionHistory.Action selectionAction(SelectionState.Snapshot before,
                                                      SelectionState.Snapshot after, String key) {
        return new SelectionHistory.Action() {
            @Override public void undo() { state.restore(before); refreshSelectionState(); }
            @Override public void redo() { state.restore(after); refreshSelectionState(); }
            @Override public String label() { return tr("history_" + key).getString(); }
        };
    }

    private void refreshSelectionState() {
        dirty = false;
        if (holding()) gesture = null;
        dragging = false;
        syncFields();
        updateSelection();
        updateVisibility();
    }

    private void commitSelection(SelectionState.Snapshot before, SelectionState.Snapshot after, String key) {
        if (!before.equals(after)) {
            SelectionHistory.Action action = selectionAction(before, after, key);
            SavedAreaStore.Area edited = editedArea();
            if (autosave.covers(AutosaveSettings.Field.GEOMETRY) && edited != null && !loadFailed
                    && after.phase() == SelectionState.Phase.SELECTED && after.geometry() != null
                    && Objects.equals(after.source(), edited.id()) && !edited.geometry().equals(after.geometry()))
                action = withGeometryAutosave(action, edited, after.geometry());
            history.recordApplied(action);
        }
        refreshSelectionState();
    }

    /**
     * Writes the edited territory's new shape at once and folds it into the selection step, so one undo restores
     * both the selection and the saved area.
     */
    private SelectionHistory.Action withGeometryAutosave(SelectionHistory.Action selection, SavedAreaStore.Area area,
                                                         AreaGeometry geometry) {
        SavedAreaStore.State before = areaStore.state();
        SavedAreaStore.State next = areaState(before.areas().stream()
                .map(a -> a.id().equals(area.id()) ? a.withGeometry(geometry) : a).toList(), before.groups());
        try {
            areaStore.replace(next);
        } catch (Exception e) {
            message = tr("area_save_failed").getString();
            LoggerFactory.getLogger("xaero-exact-selection").error("Could not autosave the edited territory", e);
            return selection;
        }
        return new SelectionHistory.Action() {
            @Override public void undo() throws Exception { areaStore.replace(before); syncAreaSelection(); selection.undo(); }
            @Override public void redo() throws Exception { areaStore.replace(next); syncAreaSelection(); selection.redo(); }
            @Override public String label() { return selection.label(); }
        };
    }

    private void undo() {
        if (dialog == Dialog.NONE && gesture instanceof MapGesture.VertexDrag) {
            cancelGesture(); message = tr("draft_cancelled").getString(); return;
        }
        if (dialog == Dialog.NONE && polygonMode && !polygonDraftUndo.isEmpty()) {
            polygonDraftRedo.push(List.copyOf(polygonDraft));
            polygonDraft.clear(); polygonDraft.addAll(polygonDraftUndo.pop());
            message = tr("history_undone").getString();
            updateSelection(); updateVisibility();
            return;
        }
        if (dialog == Dialog.NONE && polygonMode && polygonDraft.isEmpty() && !polygonDraftRedo.isEmpty()) return;
        if (dialog == Dialog.NONE && gesture instanceof MapGesture.Drag) {
            cancelGesture(); message = tr("draft_cancelled").getString(); return;
        }
        if (dialog == Dialog.NONE && dirty) { dirty = false; syncFields(); focus(null); message = tr("draft_cancelled").getString(); return; }
        if (dialog == Dialog.NONE && gesture instanceof MapGesture.Corners && state.phase() == SelectionState.Phase.PREVIEW) {
            cancelGesture(); message = tr("draft_cancelled").getString(); return;
        }
        if (!history.canUndo()) return;
        try { history.undo(); message = tr("history_undone").getString(); }
        catch (Exception e) { message = tr("history_failed").getString(); }
        refreshSelectionState();
    }

    private void redo() {
        if (dialog == Dialog.NONE && polygonMode && !polygonDraftRedo.isEmpty() && !holding() && !dirty) {
            polygonDraftUndo.push(List.copyOf(polygonDraft));
            polygonDraft.clear(); polygonDraft.addAll(polygonDraftRedo.pop());
            message = tr("history_redone").getString();
            updateSelection(); updateVisibility();
            return;
        }
        if (!history.canRedo() || dialog == Dialog.NONE
                && (dirty || !polygonDraft.isEmpty() || gesture != null)) return;
        try { history.redo(); message = tr("history_redone").getString(); }
        catch (Exception e) { message = tr("history_failed").getString(); }
        refreshSelectionState();
    }

    private void toggleChunks() {
        chunks = !chunks;
        chunkMode.setMessage(tr(chunks ? "units_chunks" : "units_blocks"));
        if (chunks && state.phase() == SelectionState.Phase.PREVIEW && gesture instanceof MapGesture.Corners corners) {
            corners.cancel(gestureHost);
            syncFields();
        }
        if (state.phase() == SelectionState.Phase.SELECTED && state.bounds() != null && chunks) {
            try {
                SelectionBounds next = state.bounds().expandedToChunks();
                if (!next.equals(state.bounds())) setSelection(next, "snap_chunks");
            } catch (IllegalArgumentException e) {
                chunks = false;
                chunkMode.setMessage(tr("units_blocks"));
                message = tr("invalid_coordinates").getString();
            }
        }
    }

    private void setSelection(SelectionBounds bounds, String key) {
        var before = historyBase();
        state.set(bounds);
        gesture = null;
        commitSelection(before, state.snapshot(), key);
    }

    private MapGesture.Handle hitHandle(MapBridge.Frame frame, double x, double y) {
        SelectionBounds b = state.bounds();
        if (b == null || state.phase() != SelectionState.Phase.SELECTED
                || state.geometry() instanceof PolygonGeometry) return null;
        return MapGesture.Drag.handleAt(frame, b, x, y);
    }

    private boolean insideBounds(MapBridge.Frame frame, double x, double y) {
        AreaGeometry geometry = state.geometry();
        if (geometry == null || state.phase() != SelectionState.Phase.SELECTED) return false;
        return geometry.containsPoint(frame.worldX(x), frame.worldZ(y));
    }

    public void beforeRender(int renderMouseX, int renderMouseY) {
        if (!enabled()) return;
        double mouseX = pointerX(renderMouseX), mouseY = pointerY(renderMouseY);
        if (scrollbarDragging) {
            if (dialog == Dialog.WAYPOINTS) dragWaypointScrollbar(mouseY); else scrollbarDragging = false;
        }
        MapBridge.Frame frame = checkedFrame();
        if (frame != null && enabled() && dragging && pending == null && dialog == Dialog.NONE)
            bridge.exactselection$pan(mouseX - lastMouseX, mouseY - lastMouseY);
        if (frame != null && gesture instanceof MapGesture.Held held && dialog == Dialog.NONE)
            held.update(gestureHost, frame, mouseX, mouseY);
        if (frame != null && polygonMode && !(gesture instanceof MapGesture.VertexDrag) && !overPanel(mouseX, mouseY)) {
            try { polygonCursor = polygonPoint(frame, mouseX, mouseY, polygonDraft.isEmpty() ? null : polygonDraft.getLast()); }
            catch (IllegalArgumentException e) { polygonCursor = null; }
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    // Render callbacks get whole GUI pixels while clicks carry the fractional cursor. At high zoom one GUI pixel
    // spans several screen pixels, so previews must use the click precision to land on the same block.
    private double pointerX(int renderMouseX) {
        if (mc.mouseHandler == null || mc.getWindow() == null) return renderMouseX;
        double exact = mc.mouseHandler.getScaledXPos(mc.getWindow());
        return Math.abs(exact - renderMouseX) < 1 ? exact : renderMouseX;
    }

    private double pointerY(int renderMouseY) {
        if (mc.mouseHandler == null || mc.getWindow() == null) return renderMouseY;
        double exact = mc.mouseHandler.getScaledYPos(mc.getWindow());
        return Math.abs(exact - renderMouseY) < 1 ? exact : renderMouseY;
    }

    private void updateSelection() {
        selected = waypoints == null || !polygonDraft.isEmpty() || state.phase() != SelectionState.Phase.SELECTED
                ? null : waypoints.select(context, state.geometry());
    }

    public void renderMapOverlays(GuiGraphics g, int renderMouseX, int renderMouseY) {
        double mouseX = pointerX(renderMouseX), mouseY = pointerY(renderMouseY);
        MapBridge.Frame frame = checkedFrame();
        if (frame == null || !enabled() && (!areaStore.state().showOnMap() || areaStore.state().areas().isEmpty())) return;
        List<MapLabel> mapLabels = new ArrayList<>();
        g.nextStratum();
        if (enabled()) {
            if (dialog == Dialog.NONE && !dirty && !overPanel(mouseX, mouseY) && !overNativeWidget(mouseX, mouseY)) {
                try {
                    int wx = SelectionBounds.block(frame.worldX(mouseX)), wz = SelectionBounds.block(frame.worldZ(mouseY));
                    if (chunks && state.phase() == SelectionState.Phase.PREVIEW) {
                        int originX = chunkOriginX(), originZ = chunkOriginZ();
                        int ax = ChunkSelection.anchor(originX, wx), az = ChunkSelection.anchor(originZ, wz);
                        int ex = ChunkSelection.endpoint(wx, originX), ez = ChunkSelection.endpoint(wz, originZ);
                        state.hover(ax, az, ex, ez);
                    } else state.hover(wx, wz);
                    if (state.phase() == SelectionState.Phase.PREVIEW) syncFields();
                } catch (IllegalArgumentException ignored) { /* Cursor outside world bounds. */ }
            }
            updateSelection();
            if (areaStore.state().showOnMap()) {
                g.nextStratum();
                renderSavedAreas(g, frame, mapLabels);
            }
            if (polygonMode && !polygonDraft.isEmpty()) {
                GeometryScreenRenderer.drawPolyline(g, frame, polygonDraft, polygonCursor,
                        screen.width, screen.height, ACCENT);
                for (int i = 0; i < polygonDraft.size(); i++) {
                    GridPoint point = polygonDraft.get(i);
                    double px = frame.screenX(point.x()), py = frame.screenY(point.z());
                    if (px < -5 || px > screen.width + 5 || py < -5 || py > screen.height + 5) continue;
                    boolean closing = i == 0 && polygonDraft.size() >= 3 && !(gesture instanceof MapGesture.VertexDrag)
                            && Math.hypot(px - mouseX, py - mouseY) <= 8;
                    int color = vertexColor(i, i == 0 ? 0xFF80FF95 : ACCENT);
                    g.fill((int) px - 3, (int) py - 3, (int) px + 4, (int) py + 4, 0xFF101922);
                    g.renderOutline((int) px - 3, (int) py - 3, 7, 7, color);
                    if (closing) {
                        g.renderOutline((int) px - 5, (int) py - 5, 11, 11, -1);
                        label(g, tr("polygon_close"), (int) px, (int) py - 16);
                    }
                }
            }
            if (state.bounds() != null && polygonDraft.isEmpty()) renderBounds(g, frame);
        }
        if (!enabled() && areaStore.state().showOnMap()) {
            g.nextStratum();
            renderSavedAreas(g, frame, mapLabels);
        }
        g.nextStratum();
        for (MapLabel entry : mapLabels) label(g, entry.text(), entry.x(), entry.y(), entry.color());
        g.nextStratum();
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        MapBridge.Frame frame = checkedFrame();
        if (enabled() && frame != null && dialog == Dialog.NONE) renderPanel(g);
        updateVisibility();
        toggle.active = enabled() || frame != null;
        toggle.setMessage(Component.literal(enabled() ? "■" : "□"));
        if (dialog != Dialog.NONE) renderDialog(g);
        for (AbstractWidget widget : widgets) {
            if (widget.visible) widget.render(g, mouseX, mouseY, delta);
        }
    }

    private void renderSavedAreas(GuiGraphics g, MapBridge.Frame frame, List<MapLabel> mapLabels) {
        Map<String, SavedAreaStore.Group> groups = new HashMap<>();
        for (SavedAreaStore.Group group : areaStore.state().groups()) groups.put(group.id(), group);
        for (SavedAreaStore.Area area : areaStore.state().areas()) {
            if (!area.visible()) continue;
            SavedAreaStore.Group group = groups.get(area.groupId());
            if (group != null && !group.visible()) continue;
            AreaGeometry geometry = area.geometry();
            GeometryBounds geometryBounds = geometry.bounds();
            // Cull by screen bounds first: projecting and clipping every triangle of an off-screen polygon is costly.
            if (frame.screenX(geometryBounds.maxX()) <= 0 || frame.screenX(geometryBounds.minX()) >= screen.width
                    || frame.screenY(geometryBounds.maxZ()) <= 0 || frame.screenY(geometryBounds.minZ()) >= screen.height)
                continue;
            // The stored shape of the territory being edited fades behind its live bounds, so old and new are told apart.
            boolean superseded = enabled() && state.phase() == SelectionState.Phase.SELECTED
                    && Objects.equals(area.id(), state.source());
            int rgb = area.color() & 0xFFFFFF;
            int fillAlpha = area.opacity() * 255 / 100 / (superseded ? 3 : 1);
            int fill = (fillAlpha << 24) | rgb, outline = (superseded ? 0x60000000 : 0xFF000000) | rgb;
            int labelColor = (superseded ? 0x99000000 : 0xFF000000) | rgb;
            if (area.vertices().size() > 0) {
                GeometryScreenRenderer.draw(g, frame, geometry, screen.width, screen.height, fill, outline, superseded ? 1.0 : 1.5);
                ScreenPoint anchor = polygonLabelAnchor(frame, (PolygonGeometry) geometry, geometryBounds);
                if (anchor != null) mapLabels.add(new MapLabel(Component.literal(area.name()),
                        clamp(anchor.x(), 0, screen.width - 1), clamp(anchor.y(), 0, screen.height - 1), labelColor));
                continue;
            }
            SelectionBounds b = area.bounds();
            double left = frame.screenX(b.minX()), top = frame.screenY(b.minZ());
            double right = frame.screenX((long) b.maxX() + 1), bottom = frame.screenY((long) b.maxZ() + 1);
            int x1 = clamp(left, 0, screen.width), y1 = clamp(top, 0, screen.height);
            int x2 = clamp(right, 0, screen.width), y2 = clamp(bottom, 0, screen.height);
            GeometryScreenRenderer.draw(g, frame, geometry, screen.width, screen.height, fill, outline, 1.0);
            if ((x2 - x1) > 50 && (y2 - y1) > 14)
                mapLabels.add(new MapLabel(Component.literal(area.name()), (x1 + x2) / 2, y1 + 3, labelColor));
        }
    }

    private ScreenPoint polygonLabelAnchor(MapBridge.Frame frame, PolygonGeometry geometry, GeometryBounds bounds) {
        double left = frame.screenX(bounds.minX()), right = frame.screenX(bounds.maxX());
        double top = frame.screenY(bounds.minZ()), bottom = frame.screenY(bounds.maxZ());
        if (right <= 0 || left >= screen.width || bottom <= 0 || top >= screen.height) return null;
        // Same minimum visible size as rectangle labels, so zoomed-out maps are not covered by names.
        if (clamp(right, 0, screen.width) - clamp(left, 0, screen.width) <= 50
                || clamp(bottom, 0, screen.height) - clamp(top, 0, screen.height) <= 14) return null;
        double bestArea = 0;
        ScreenPoint best = null;
        for (List<GridPoint> triangle : geometry.triangulate()) {
            List<ScreenPoint> visible = new ArrayList<>(3);
            for (GridPoint point : triangle)
                visible.add(new ScreenPoint(frame.screenX(point.x()), frame.screenY(point.z())));
            for (int edge = 0; edge < 4 && !visible.isEmpty(); edge++)
                visible = clipLabelPolygon(visible, edge);
            if (visible.size() < 3) continue;
            double twiceArea = 0, centerX = 0, centerY = 0;
            for (int i = 0; i < visible.size(); i++) {
                ScreenPoint a = visible.get(i), b = visible.get((i + 1) % visible.size());
                double cross = a.x() * b.y() - b.x() * a.y();
                twiceArea += cross;
                centerX += (a.x() + b.x()) * cross;
                centerY += (a.y() + b.y()) * cross;
            }
            double area = Math.abs(twiceArea) / 2.0;
            if (area > bestArea && Math.abs(twiceArea) > 1.0E-9) {
                bestArea = area;
                best = new ScreenPoint(centerX / (3 * twiceArea), centerY / (3 * twiceArea));
            }
        }
        return best;
    }

    private List<ScreenPoint> clipLabelPolygon(List<ScreenPoint> input, int edge) {
        double boundary = edge == 0 ? 0 : edge == 1 ? screen.width : edge == 2 ? 0 : screen.height;
        List<ScreenPoint> output = new ArrayList<>();
        ScreenPoint previous = input.getLast();
        boolean previousInside = insideLabelBoundary(previous, edge, boundary);
        for (ScreenPoint current : input) {
            boolean currentInside = insideLabelBoundary(current, edge, boundary);
            if (currentInside != previousInside) {
                double t = edge < 2 ? (boundary - previous.x()) / (current.x() - previous.x())
                        : (boundary - previous.y()) / (current.y() - previous.y());
                output.add(edge < 2
                        ? new ScreenPoint(boundary, previous.y() + (current.y() - previous.y()) * t)
                        : new ScreenPoint(previous.x() + (current.x() - previous.x()) * t, boundary));
            }
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean insideLabelBoundary(ScreenPoint point, int edge, double boundary) {
        return switch (edge) {
            case 0 -> point.x() >= boundary;
            case 1 -> point.x() <= boundary;
            case 2 -> point.y() >= boundary;
            default -> point.y() <= boundary;
        };
    }

    private void renderBounds(GuiGraphics g, MapBridge.Frame frame) {
        SelectionBounds b = state.bounds();
        if (state.geometry() instanceof PolygonGeometry polygon) {
            GeometryScreenRenderer.draw(g, frame, polygon, screen.width, screen.height, 0x4059E3CE, ACCENT, 2.0);
            for (int i = 0; i < polygon.vertices().size(); i++) {
                GridPoint point = polygon.vertices().get(i);
                double px = frame.screenX(point.x()), py = frame.screenY(point.z());
                if (px < -5 || px > screen.width + 5 || py < -5 || py > screen.height + 5) continue;
                int x = (int) Math.floor(px), y = (int) Math.floor(py);
                g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101922);
                g.renderOutline(x - 3, y - 3, 7, 7, vertexColor(i, ACCENT));
            }
        }
        double left = frame.screenX(b.minX()), top = frame.screenY(b.minZ());
        double right = frame.screenX((long) b.maxX() + 1), bottom = frame.screenY((long) b.maxZ() + 1);
        int x1 = clamp(left, 0, screen.width), y1 = clamp(top, 0, screen.height);
        int x2 = clamp(right, 0, screen.width), y2 = clamp(bottom, 0, screen.height);
        if (!(state.geometry() instanceof PolygonGeometry)) {
            GeometryScreenRenderer.draw(g, frame, state.geometry(), screen.width, screen.height,
                    0x2459E3CE, ACCENT, 1.0);
        }
        if (left < screen.width && right > 0 && top < screen.height && bottom > 0
                && x1 == x2 && y1 == y2) {
            int markerX = clamp((left + right) / 2, 0, screen.width - 1);
            int markerY = clamp((top + bottom) / 2, 0, screen.height - 1);
            g.fill(markerX, markerY, markerX + 1, markerY + 1, ACCENT);
        }
        if (!(state.geometry() instanceof PolygonGeometry) && x1 < x2 && y1 < y2) {
            label(g, tr("width", b.width()), (x1 + x2) / 2, Math.max(2, y1 + 3));
            label(g, tr("length", b.length()), Math.min(screen.width - 60, x2 - 36), (y1 + y2) / 2);
        }
        if (!(state.geometry() instanceof PolygonGeometry)) renderHandles(g, frame, b);
        if (selected != null) {
            g.enableScissor(0, 0, screen.width, screen.height);
            for (WaypointAccess.Point point : selected.points()) {
                double scale = waypoints.dimensionScale();
                double px = frame.screenX(WaypointAccess.mapBlock(point.x(), scale) + 0.5);
                double py = frame.screenY(WaypointAccess.mapBlock(point.z(), scale) + 0.5);
                if (px < -6 || px > screen.width + 6 || py < -6 || py > screen.height + 6) continue;
                int x = (int) Math.floor(px), y = (int) Math.floor(py);
                g.renderOutline(x - 5, y - 5, 11, 11, 0xFFFFCF62);
            }
            g.disableScissor();
        }
    }

    /** White for the dragged vertex, red while its position is rejected. */
    private int vertexColor(int index, int idle) {
        return gesture instanceof MapGesture.VertexDrag vertex && vertex.index == index
                ? vertex.invalid ? 0xFFFF5573 : -1 : idle;
    }

    private static int clamp(double value, int min, int max) {
        return (int) Math.max(min, Math.min(max, Math.floor(value)));
    }

    private void label(GuiGraphics g, Component text, int x, int y) {
        label(g, text, x, y, ACCENT);
    }

    private void label(GuiGraphics g, Component text, int x, int y, int color) {
        int w = mc.font.width(text);
        x = Math.max(w / 2 + 2, Math.min(screen.width - w / 2 - 2, x));
        g.fill(x - w / 2 - 2, y - 1, x + (w + 1) / 2 + 2, y + 10, 0xB0101820);
        g.drawCenteredString(mc.font, text, x, y, color);
    }

    private void renderHandles(GuiGraphics g, MapBridge.Frame frame, SelectionBounds b) {
        double left = frame.screenX(b.minX()), right = frame.screenX((long) b.maxX() + 1);
        double top = frame.screenY(b.minZ()), bottom = frame.screenY((long) b.maxZ() + 1);
        int[][] points = {{(int) left, (int) top}, {(int) ((left + right) / 2), (int) top}, {(int) right, (int) top},
                {(int) left, (int) ((top + bottom) / 2)}, {(int) right, (int) ((top + bottom) / 2)},
                {(int) left, (int) bottom}, {(int) ((left + right) / 2), (int) bottom}, {(int) right, (int) bottom}};
        for (int[] p : points) if (p[0] >= -5 && p[0] <= screen.width + 5 && p[1] >= -5 && p[1] <= screen.height + 5)
            g.fill(p[0] - 3, p[1] - 3, p[0] + 4, p[1] + 4, 0xFF101922);
        for (int[] p : points) if (p[0] >= -4 && p[0] <= screen.width + 4 && p[1] >= -4 && p[1] <= screen.height + 4)
            g.renderOutline(p[0] - 3, p[1] - 3, 7, 7, ACCENT);
    }

    private void renderPanel(GuiGraphics g) {
        g.nextStratum();
        g.fill(PANEL_X, PANEL_Y, PANEL_X + panelWidth, PANEL_Y + panelHeight, 0xEE101922);
        g.renderOutline(PANEL_X, PANEL_Y, panelWidth, panelHeight, 0xFF42636D);
        g.drawString(mc.font, mc.font.plainSubstrByWidth(tr("title").getString(), panelWidth - 96),
                PANEL_X + 8, PANEL_Y + 7, ACCENT);
        String hint = polygonMode ? compact ? "polygon_controls_short" : "polygon_controls"
                : state.geometry() instanceof PolygonGeometry ? "polygon_edit_controls"
                : state.source() != null && state.phase() == SelectionState.Phase.SELECTED ? "rectangle_edit_controls"
                : state.phase() == SelectionState.Phase.PREVIEW ? "second_corner"
                : state.phase() == SelectionState.Phase.SELECTED ? "new_selection" : "first_corner";
        // Two pixels below the mode button so the line never touches it.
        g.drawString(mc.font, fitHint(tr(hint).getString(), panelWidth - 16), PANEL_X + 8, PANEL_Y + 22, 0xFFD0DBE2);
        g.enableScissor(PANEL_X + 1, PANEL_Y + 32, PANEL_X + panelWidth - 1,
                PANEL_Y + panelHeight - (maxPanelScroll() > 0 ? 16 : 3));
        SelectionBounds b = state.bounds();
        if (compact) {
            int available = panelWidth - 16 - (quickSave.visible ? 90 : 0);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(b == null ? "A: —" : "A: X " + b.x1() + "  Z " + b.z1(), available),
                    PANEL_X + 8, PANEL_Y + 33, -1);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(b == null ? "B: —" : "B: X " + b.x2() + "  Z " + b.z2(), available),
                    PANEL_X + 8, PANEL_Y + 45, -1);
        } else {
            int labelX = fields[0].getX() - fieldGap - coordinateLabelWidth;
            int zX = fields[1].getX() - fieldGap - zLabelWidth;
            g.drawString(mc.font, "A: X", labelX, PANEL_Y + 39 - panelScroll, -1);
            g.drawString(mc.font, "Z", zX, PANEL_Y + 39 - panelScroll, -1);
            g.drawString(mc.font, "B: X", labelX, PANEL_Y + 63 - panelScroll, -1);
            g.drawString(mc.font, "Z", zX, PANEL_Y + 63 - panelScroll, -1);
        }
        line(g, b == null ? tr("dimensions_empty") : chunks
                ? tr("dimensions_chunks", b.width(), b.length(), b.chunkCount())
                : tr("dimensions", b.width(), b.length()), compact ? 57 : 106, -1);
        int sh = metricsShift;
        if (sh != 0) line(g, Component.literal(areaText().getString() + " · " + perimeterText().getString()), compact ? 69 : 118, ACCENT);
        else {
            line(g, areaText(), compact ? 69 : 118, ACCENT);
            line(g, perimeterText(), compact ? 81 : 130, ACCENT);
        }
        sectionHeader(g, tr("copy_header"), (compact ? 94 : 144) + sh);
        int lineY = PANEL_Y + sh + (compact ? 124 : 174 - panelScroll);
        g.fill(PANEL_X + 8, lineY, PANEL_X + panelWidth - 8, lineY + 1, 0xFF42636D);
        if (!compact) {
            int n = selected == null ? 0 : selected.points().size();
            Component count = waypoints == null ? tr("minimap_missing") : selected == null
                    ? tr("waypoints_unavailable") : tr("count", n, setNameDisplay(selected.setName()));
            line(g, count, 270 + sh, 0xFFFFCF62);
            line(g, tr(loadFailed ? "area_load_failed" : "scope"), 282 + sh, 0xFF9BAAB7);
        }
        SavedAreaStore.Area editing = editedArea();
        Component status = !message.isEmpty() ? Component.literal(message)
                : editing == null ? tr("controls") : tr("editing_area", editing.name());
        if (!hasExportNotice()) line(g, status, (compact ? 171 : 297) + sh, message.isEmpty() ? 0xFF9BAAB7 : 0xFFFFCF62);
        g.disableScissor();
        if (maxPanelScroll() > 0)
            g.drawString(mc.font, tr("panel_scroll"), PANEL_X + 8, PANEL_Y + panelHeight - 12, 0xFF9BAAB7);
    }

    /**
     * Keeps whole " · "-separated hints that fit, so a narrow panel drops trailing hints instead of cutting a word;
     * a single hint that is still too wide ends with an ellipsis.
     */
    private String fitHint(String text, int width) {
        if (mc.font.width(text) <= width) return text;
        String[] parts = text.split(" · ");
        String fitted = parts[0];
        for (int i = 1; i < parts.length && mc.font.width(fitted + " · " + parts[i]) <= width; i++) fitted += " · " + parts[i];
        if (mc.font.width(fitted) <= width) return fitted;
        return mc.font.plainSubstrByWidth(fitted, width - mc.font.width("…")).stripTrailing() + "…";
    }

    private void sectionHeader(GuiGraphics g, Component text, int y) {
        int drawY = PANEL_Y + y - (compact ? 0 : panelScroll);
        g.drawString(mc.font, text, PANEL_X + 8, drawY, 0xFF9BAAB7);
        int start = PANEL_X + 12 + mc.font.width(text);
        g.fill(start, drawY + 4, PANEL_X + panelWidth - 8, drawY + 5, 0xFF42636D);
    }

    private void line(GuiGraphics g, Component text, int y, int color) {
        g.drawString(mc.font, mc.font.plainSubstrByWidth(text.getString(), panelWidth - 16), PANEL_X + 8,
                PANEL_Y + y - (compact ? 0 : panelScroll), color);
    }

    private static Component setNameDisplay(String name) {
        return "gui.xaero_default".equals(name) ? Component.translatable(name) : Component.literal(name);
    }

    private void updateVisibility() {
        if (toggle == null) return;
        if ((perimeterInline() ? -12 : 0) != metricsShift) layoutMainPanel();
        for (AbstractWidget widget : widgets) widget.visible = false;
        boolean main = enabled() && dialog == Dialog.NONE;
        show(main, panelModeButton, copyCoordinates, copyArea, copyPerimeter, copyLength, copyWidth,
                undo, redo, polygonModeButton, groupsButton, clear);
        show(main, areasButton);
        show(main && (!compact || state.phase() == SelectionState.Phase.SELECTED), quickSave);
        show(main && !compact, fields[0], fields[1], fields[2], fields[3], apply, chunkMode,
                sizeButton, moveButton, centerButton, zonesButton, pointsButton, selectionExportButton);
        if (main && hasExportNotice()) {
            place(openExportButton, PANEL_X + 8, PANEL_Y + metricsShift + (compact ? 168 : 294 - panelScroll),
                    panelWidth - 16, 16);
            show(true, openExportButton);
        }
        String copySuffix = compact ? "_short" : "_full";
        copyCoordinates.setMessage(tr("copy_coordinates" + copySuffix));
        copyArea.setMessage(tr("copy_area" + copySuffix));
        copyPerimeter.setMessage(tr("copy_perimeter" + copySuffix));
        copyLength.setMessage(tr("copy_length" + copySuffix));
        copyWidth.setMessage(tr("copy_width" + copySuffix));
        SavedAreaStore.Area editing = editedArea();
        boolean autoGeometry = editing != null && autosave.covers(AutosaveSettings.Field.GEOMETRY);
        quickSave.setMessage(tr(editing == null ? "quick_save" : autoGeometry ? "quick_autosaved" : "quick_apply"));
        quickSave.setTooltip(Tooltip.create(editing == null ? tr("quick_save_tooltip")
                : autoGeometry ? tr("quick_autosaved_tooltip", editing.name()) : tr("quick_apply_tooltip", editing.name())));
        quickSave.active = state.phase() == SelectionState.Phase.SELECTED && !dirty && polygonDraft.isEmpty()
                && !holding() && !loadFailed && (editing == null || !editing.geometry().equals(state.geometry()));
        panelModeButton.setMessage(tr(compact ? "panel_expand" : "panel_compact"));
        panelModeButton.setTooltip(Tooltip.create(tr(compact ? "panel_expand_tooltip" : "panel_compact_tooltip")));
        if (main && !compact) {
            for (AbstractWidget widget : widgets) {
                if (widget.visible && widget != panelModeButton)
                    widget.visible = widget.getY() >= PANEL_Y + 32
                            && widget.getY() + widget.getHeight() <= PANEL_Y + panelHeight - (maxPanelScroll() > 0 ? 16 : 3);
            }
        }
        boolean contourDraft = !polygonDraft.isEmpty();
        boolean polygonSelection = state.geometry() instanceof PolygonGeometry;
        for (EditBox field : fields) field.active = !polygonSelection && !contourDraft && !holding();
        boolean draftHistory = !polygonDraftUndo.isEmpty() || !polygonDraftRedo.isEmpty();
        undo.active = (draftHistory ? !polygonDraftUndo.isEmpty() : history.canUndo())
                || dirty || gesture != null;
        redo.active = (!polygonDraftRedo.isEmpty() || !contourDraft && history.canRedo())
                && !dirty && gesture == null;
        // Name the stored action only when the button would apply it rather than discard a draft or gesture.
        boolean historyStep = !draftHistory && !dirty && gesture == null;
        undo.setTooltip(historyTooltip("undo_tooltip", historyStep ? history.undoLabel() : ""));
        redo.setTooltip(historyTooltip("redo_tooltip", historyStep && redo.active ? history.redoLabel() : ""));
        managementUndo.setTooltip(historyTooltip("undo_tooltip", history.undoLabel()));
        managementRedo.setTooltip(historyTooltip("redo_tooltip", history.redoLabel()));
        chunkMode.setMessage(tr(chunks ? "units_chunks" : "units_blocks"));
        selectionExportButton.active = canExportSelection();
        toggle.visible = dialog == Dialog.NONE;
        toggle.setMessage(Component.literal(enabled() ? "■" : "□"));
        polygonModeButton.setMessage(tr(polygonMode ? "polygon_mode_on" : "polygon_mode"));
        polygonModeButton.active = enabled() && !dirty && !holding() && dialog == Dialog.NONE;
        polygonModeButton.setTooltip(Tooltip.create(tr(polygonMode ? "polygon_controls" : "polygon_mode_tooltip")));
        sizeButton.active = !(state.geometry() instanceof PolygonGeometry) && !dirty && !contourDraft && !holding();
        chunkMode.active = !(state.geometry() instanceof PolygonGeometry) && !contourDraft && !holding();
        moveButton.active = centerButton.active = !dirty && !contourDraft && !holding();
        areasButton.active = groupsButton.active = !dirty && !contourDraft && !holding();
        int framed = compact ? 0 : areasInSelection().size();
        zonesButton.setMessage(tr("zones_in_selection", framed));
        zonesButton.active = framed > 0 && !dirty && !contourDraft && !holding();
        if (dialog == Dialog.SIZE) show(true, dialogFields[0], dialogFields[1], anchorButton, modalApply, modalCancel);
        if (dialog == Dialog.MOVE) show(true, dialogFields[2], dialogFields[3], moveUnitsButton, modalApply, modalCancel);
        if (dialog == Dialog.CENTER) show(true, dialogFields[6], centerCopy, centerCreate);
        if (dialog == Dialog.AREA_NAME) show(true, dialogFields[8], newAreaGroup, newAreaOpacityButton, modalApply, modalCancel);
        if (dialog == Dialog.COLOR) {
            // A swatch or Apply chooses the color; Esc leaves without one.
            if (colorReturn == Dialog.AREAS) show(true, dialogFields[HEX_FIELD], colorHexApply);
        }
        if (dialog == Dialog.GROUP_NAME) show(true, dialogFields[5], modalApply, modalCancel);
        if (dialog == Dialog.ASSIGN_GROUP) show(true, manageGroups, managementClose);
        if (dialog == Dialog.AREAS) {
            if (areaDeleteConfirmation) show(true, confirm, cancel);
            else {
                show(true, managementClose, manageGroups, managementUndo, managementRedo, areaSave,
                        areaSelectAll, areaClearSelection, areaMapVisibility, areaMinimapVisibility, autosaveButton);
                if (autosaveOpen) {
                    show(true, autosaveToggle, autosaveClose);
                    show(true, autosaveFields.values().toArray(AbstractWidget[]::new));
                } else {
                    show(true, dialogFields[4], groupAssign, areaLoad, areaExportButton, areaDelete,
                            areaVisibility, areaColor, areaOpacity);
                    // Save disappears under autosave unless a change outside its fields still waits.
                    show(!autosave.enabled() || areaDraft.needsSave(autosave), areaSaveChanges);
                    show(areaCopiesFit(dialogRect()[3]), areaCopies);
                }
            }
            if (!areaDeleteConfirmation && hasExportNotice()) show(true, openExportButton);
        }
        if (dialog == Dialog.GROUPS) {
            if (groupDeleteConfirmation) show(true, confirm, cancel);
            else show(true, managementClose, managementUndo, managementRedo, groupCreate, groupRename, groupVisibility, groupDelete);
        }
        if (dialog == Dialog.WAYPOINTS) show(true, dialogFields[7], dialogFields[RENAME_FIELD],
                pointVisibility, pointColor, pointScope, pointRename, pointRecolor, confirm, cancel);
        apply.active = enabled() && !polygonSelection && !contourDraft && !holding() && (dirty || state.bounds() != null);
        clear.active = state.bounds() != null || dirty || contourDraft;
        int pointCount = selected == null ? 0 : selected.points().size();
        pointsButton.setMessage(state.phase() == SelectionState.Phase.SELECTED ? tr("waypoints_button", pointCount) : tr("waypoints_all"));
        pointsButton.active = waypoints != null && !dirty && !contourDraft && !holding();
        pointScope.setMessage(tr(pointsInSelection ? "point_scope_selection" : "point_scope_all"));
        pointScope.active = state.phase() == SelectionState.Phase.SELECTED;
        pointRename.active = !filteredChosenPoints().isEmpty() && !dialogFields[RENAME_FIELD].getValue().trim().isEmpty();
        pointRecolor.active = !filteredChosenPoints().isEmpty();
        if (dialog == Dialog.WAYPOINTS) {
            confirm.setMessage(tr("delete_count", filteredChosenPoints().size()));
            cancel.setMessage(tr("done"));
        }
        colorHexApply.active = true;
        SavedAreaStore.Group newGroup = findGroup(newAreaGroupId);
        newAreaGroup.setMessage(tr("area_group_button", newGroup == null ? tr("group_none").getString() : newGroup.name()));
        newAreaOpacityButton.setMessage(tr("area_new_opacity", newAreaOpacity + "%"));
        boolean canCopy = enabled() && !dirty && !contourDraft && !holding() && dialog == Dialog.NONE && state.bounds() != null;
        copyCoordinates.active = copyArea.active = copyPerimeter.active = copyLength.active = copyWidth.active = canCopy;
        if (dialog == Dialog.WAYPOINTS && pending != null) confirm.active = !filteredChosenPoints().isEmpty();
        if (areaDeleteConfirmation && dialog == Dialog.AREAS || groupDeleteConfirmation && dialog == Dialog.GROUPS) {
            confirm.setMessage(tr(groupDeleteConfirmation ? "group_delete_confirm_button" : "area_delete_confirm_button"));
            cancel.setMessage(tr("cancel"));
            confirm.active = (groupDeleteConfirmation ? selectedGroup() != null : !selectedAreas().isEmpty()) && !loadFailed;
        }
        centerCopy.active = state.bounds() != null;
        centerCreate.active = state.phase() == SelectionState.Phase.SELECTED && waypoints != null;
        managementUndo.active = history.canUndo();
        managementRedo.active = history.canRedo();
        managementClose.setMessage(tr(dialog == Dialog.AREAS || dialog == Dialog.GROUPS && groupsReturnDialog == Dialog.NONE
                ? "back_to_map" : "back"));
        modalCancel.setMessage(tr("cancel"));
        modalApply.setMessage(tr(dialog == Dialog.AREA_NAME ? "area_create_button"
                : dialog == Dialog.GROUP_NAME ? renamingGroup ? "save_name" : "group_create" : "apply"));
        modalApply.active = switch (dialog) {
            case AREA_NAME -> !dialogFields[8].getValue().trim().isEmpty() && !loadFailed;
            case GROUP_NAME -> !dialogFields[5].getValue().trim().isEmpty() && !loadFailed;
            default -> true;
        };
        int selectedCount = selectedAreas().size();
        boolean singleArea = selectedCount == 1;
        SavedAreaStore.Area area = selectedArea();
        SavedAreaStore.Group group = selectedGroup();
        boolean editingThis = area != null && Objects.equals(area.id(), state.source());
        areaLoad.active = singleArea && !editingThis;
        areaLoad.setMessage(tr(editingThis ? "area_editing_now" : "area_edit"));
        areaSaveChanges.active = !areaDraft.isEmpty() && selectedCount > 0 && !loadFailed;
        for (Button copy : areaCopies) copy.active = singleArea;
        autosaveToggle.setMessage(tr(autosave.enabled() ? "autosave_on" : "autosave_off"));
        autosaveFields.forEach((field, button) -> {
            button.setMessage(tr("autosave_field", tr("autosave_field_" + field.name().toLowerCase(Locale.ROOT)),
                    tr(autosave.includes(field) ? "state_on" : "state_off")));
            button.active = autosave.enabled();
        });
        areaExportButton.active = singleArea && !loadFailed && !exportRunning;
        areaDelete.active = selectedCount > 0 && !loadFailed;
        areaColor.active = areaOpacity.active = selectedCount > 0 && !loadFailed;
        areaVisibility.active = selectedCount > 0 && !loadFailed;
        groupAssign.active = selectedCount > 0 && !loadFailed;
        areaSave.active = state.phase() == SelectionState.Phase.SELECTED && !dirty && !loadFailed;
        dialogFields[4].active = singleArea && !loadFailed;
        groupCreate.active = !loadFailed;
        groupRename.active = groupVisibility.active = groupDelete.active = group != null && !loadFailed;
        groupVisibility.setMessage(tr(group != null && group.visible() ? "group_hide" : "group_show"));
        boolean hideAreas = selectedAreas().stream().anyMatch(SavedAreaStore.Area::visible);
        areaVisibility.setMessage(tr(hideAreas ? "area_hide_selected" : "area_show_selected", selectedCount));
        // Unsaved values show with a trailing asterisk until Save or autosave writes them.
        SavedAreaStore.Area primary = area != null ? area : selectedAreas().isEmpty() ? null : selectedAreas().get(0);
        areaOpacity.setMessage(primary == null ? tr("area_fill") : Component.literal(
                (areaDraft.opacity != null ? areaDraft.opacity : primary.opacity()) + "%" + (areaDraft.opacity != null ? " *" : "")));
        String draftGroup = !areaDraft.groupSet ? selectedGroupName() : findGroup(areaDraft.groupId) == null
                ? tr("group_none").getString() : findGroup(areaDraft.groupId).name();
        groupAssign.setMessage(Component.literal(tr("area_group_button", draftGroup).getString() + (areaDraft.groupSet ? " *" : "")));
        areaColor.setMessage(Component.literal(tr("area_color").getString() + (areaDraft.color != null ? " *" : "")));
        areaMapVisibility.setMessage(tr(areaStore.state().showOnMap() ? "area_map_on" : "area_map_off"));
        areaMinimapVisibility.setMessage(tr(areaStore.state().showOnMinimap() ? "area_minimap_on" : "area_minimap_off"));
        areaMapVisibility.active = areaMinimapVisibility.active = !loadFailed;
        areaDelete.setMessage(tr("area_delete_count", selectedCount));
        areaSelectAll.active = !loadFailed && selectedCount < areaStore.state().areas().size();
        areaClearSelection.active = selectedCount > 0;
        areaSave.setTooltip(Tooltip.create(tr(loadFailed ? "area_load_failed" : "area_save_tooltip")));
    }

    private Tooltip historyTooltip(String key, String label) {
        return Tooltip.create(label.isEmpty() ? tr(key + "_plain") : tr(key, label));
    }

    private String selectedGroupName() {
        List<SavedAreaStore.Area> areas = selectedAreas();
        if (areas.isEmpty()) return tr("group_none").getString();
        String id = areas.get(0).groupId();
        if (areas.stream().anyMatch(area -> !Objects.equals(id, area.groupId()))) return tr("group_mixed").getString();
        SavedAreaStore.Group group = findGroup(id);
        return group == null ? tr("group_none").getString() : group.name();
    }

    private void show(boolean visible, AbstractWidget... targets) {
        for (AbstractWidget widget : targets) widget.visible = visible;
    }

    private List<WaypointAccess.Point> filteredPoints() {
        if (pending == null) return List.of();
        String query = dialogFields[7].getValue().trim().toLowerCase(java.util.Locale.ROOT);
        return pending.points().stream().filter(WaypointAccess.Point::editable)
                .filter(p -> query.isEmpty() || p.name().toLowerCase(java.util.Locale.ROOT).contains(query))
                .filter(p -> visibilityFilter == 0 || (visibilityFilter == 1 && !p.disabled()) || (visibilityFilter == 2 && p.disabled()))
                .filter(p -> colorFilter < 0 || p.color() == colorFilter)
                .toList();
    }

    private List<WaypointAccess.Point> filteredChosenPoints() {
        return filteredPoints().stream().filter(p -> chosenPoints.contains(p.identity())).toList();
    }

    private void renderDialog(GuiGraphics g) {
        layoutDialog();
        int[] r = dialogRect(); int x = r[0], y = r[1], w = r[2], h = r[3];
        g.nextStratum(); g.fill(0, 0, screen.width, screen.height, 0xC0080D12);
        g.fill(x, y, x + w, y + h, 0xFF141E29); g.renderOutline(x, y, w, h, 0xFF42636D);
        String title = switch (dialog) {
            case SIZE -> "dialog_size"; case MOVE -> "dialog_move"; case CENTER -> "dialog_center";
            case AREAS -> "areas_window"; case GROUPS -> "groups_window";
            case ASSIGN_GROUP -> "group_picker_title"; case AREA_NAME -> "area_new_title";
            case GROUP_NAME -> renamingGroup ? "group_rename_title" : "group_new_title";
            case WAYPOINTS -> "dialog_waypoints"; case COLOR -> "color_title"; default -> "title";
        };
        if (dialog == Dialog.AREAS || dialog == Dialog.GROUPS || dialog == Dialog.ASSIGN_GROUP)
            g.drawString(mc.font, tr(title), x + 12, y + 12, ACCENT);
        else g.drawCenteredString(mc.font, tr(title), x + w / 2, y + 10, ACCENT);
        switch (dialog) {
            case SIZE -> {
                g.drawString(mc.font, tr("width_field"), x + 18, y + 38, -1);
                g.drawString(mc.font, tr("length_field"), x + 36 + (w - 54) / 2, y + 38, -1);
                SelectionBounds b = state.bounds();
                if (b != null) g.drawString(mc.font, tr("current_anchor", formatCenter(sizeFromCenter ? b.centerX() : b.minX()),
                        formatCenter(sizeFromCenter ? b.centerZ() : b.maxZ())), x + 18, y + 108, 0xFF9BAAB7);
                g.drawString(mc.font, tr("lower_left_axes"), x + 18, y + 124, 0xFF9BAAB7);
            }
            case MOVE -> {
                g.drawString(mc.font, tr("offset_x"), x + 18, y + 38, -1);
                g.drawString(mc.font, tr("offset_z"), x + 36 + (w - 54) / 2, y + 38, -1);
                g.drawString(mc.font, tr("move_note"), x + 18, y + 108, 0xFF9BAAB7);
            }
            case CENTER -> {
                SelectionBounds b = state.bounds();
                g.drawString(mc.font, b == null ? tr("no_selection") : tr("center_value", formatCenter(b.centerX()), formatCenter(b.centerZ())), x + 18, y + 34, -1);
                g.drawString(mc.font, tr("center_waypoint_y"), x + 18, y + 82, 0xFF9BAAB7);
            }
            case AREAS -> renderAreasDialog(g, x, y, w, h);
            case GROUPS -> renderGroupsDialog(g, x, y, w, h);
            case ASSIGN_GROUP -> renderGroupChooser(g, x, y, w, h);
            case AREA_NAME, GROUP_NAME -> {
                g.drawString(mc.font, tr(dialog == Dialog.AREA_NAME ? "area_name" : "group_name"), x + 16, y + 32, -1);
                if (dialog == Dialog.AREA_NAME) renderSwatches(g, x, y, w, AreaPalette.COLORS, newAreaColor);
                if (!message.isEmpty()) g.drawString(mc.font, mc.font.plainSubstrByWidth(message, w - 32), x + 16, y + (dialog == Dialog.AREA_NAME ? 166 : 80), 0xFFFFCF62);
            }
            case WAYPOINTS -> renderWaypointsDialog(g, x, y, w, h);
            case COLOR -> renderColorDialog(g, x, y, w, h);
            default -> {}
        }
    }

    private void renderAreasDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (areaDeleteConfirmation) {
            g.drawCenteredString(mc.font, tr("area_delete_confirm", selectedAreas().size()), x + w / 2, y + h / 2 - 12, -1);
            g.drawCenteredString(mc.font, tr("area_delete_undoable"), x + w / 2, y + h / 2 + 6, 0xFF9BAAB7);
            return;
        }
        int col = (w - 36) / 2, right = x + w - col - 12;
        List<SavedAreaStore.Area> areas = areaStore.state().areas();
        int rows = visibleAreaDialogRows(h);
        areaScroll = Math.min(areaScroll, Math.max(0, areas.size() - rows));
        g.drawString(mc.font, tr("area_list_count", selectedAreaIds.size()), x + 12, y + 34, 0xFF9BAAB7);
        g.fill(x + 10, y + 46, x + 14 + col, y + h - 78, 0xFF0C141D);
        if (areas.isEmpty()) g.drawString(mc.font, tr("areas_empty"), x + 16, y + 54, 0xFF9BAAB7);
        for (int i = 0; i < rows && i + areaScroll < areas.size(); i++) {
            SavedAreaStore.Area area = areas.get(i + areaScroll);
            int rowY = y + 48 + i * 20;
            boolean checked = selectedAreaIds.contains(area.id());
            if (checked) g.fill(x + 10, rowY, x + 14 + col, rowY + 20, 0x8842636D);
            g.renderOutline(x + 14, rowY + 4, 9, 9, checked ? ACCENT : 0xFF64717A);
            if (checked) g.drawString(mc.font, "x", x + 16, rowY + 4, ACCENT);
            g.fill(x + 28, rowY + 4, x + 34, rowY + 10, 0xFF000000 | area.color());
            g.drawString(mc.font, mc.font.plainSubstrByWidth(area.name(), col - 30), x + 39, rowY + 1, -1);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(areaVisibilityDescription(area), col - 30), x + 39, rowY + 11, 0xFF9BAAB7);
        }
        if (autosave.enabled()) {
            String indicator = "● " + tr("autosave_indicator").getString();
            g.drawString(mc.font, indicator, x + w - 138 - mc.font.width(indicator), y + h - 20, ACCENT);
        }
        if (autosaveOpen) {
            g.drawString(mc.font, tr("autosave_title"), right, y + 34, ACCENT);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(tr("autosave_hint").getString(), col),
                    right, y + 68 + 20 * (autosaveFields.size() + 1) + 4, 0xFF9BAAB7);
            renderManagementStatus(g, x, y, w, h, "areas_selection_hint");
            return;
        }
        g.drawString(mc.font, tr("area_name"), right, y + 34, 0xFF9BAAB7);
        dialogSection(g, tr("selected_header"), right, y + 148, col);
        boolean copies = areaCopiesFit(h);
        if (copies) dialogSection(g, tr("copy_header"), right, y + 182, col);
        if (selectedAreaIds.size() == 1 && selectedArea() != null) {
            SavedAreaStore.Area area = selectedArea();
            List<Component> info = new ArrayList<>();
            info.add(Component.literal(areaVisibilityDescription(area)));
            if (area.geometry() instanceof RectangleGeometry) {
                SelectionBounds b = area.bounds();
                info.add(tr("dimensions", b.width(), b.length()));
            }
            info.add(tr("area", formatMetric(area.geometry().area())));
            info.add(tr("perimeter", formatLength(area.geometry().perimeter())));
            int lineY = y + (copies ? 218 : 184);
            for (Component line : info) {
                // Stop above the export button and the autosave row at the bottom.
                if (lineY + 9 > y + h - 46) break;
                g.drawString(mc.font, mc.font.plainSubstrByWidth(line.getString(), col), right, lineY, 0xFF9BAAB7);
                lineY += 12;
            }
        }
        renderManagementStatus(g, x, y, w, h, "areas_selection_hint");
    }

    /** Room for the copy row below the property buttons; small windows leave it out. */
    private static boolean areaCopiesFit(int height) { return height >= 300; }

    /** A titled divider inside a window column, matching the panel's Copy header. */
    private void dialogSection(GuiGraphics g, Component text, int x, int y, int width) {
        g.drawString(mc.font, text, x, y, 0xFF9BAAB7);
        int start = x + 4 + mc.font.width(text);
        g.fill(start, y + 4, x + width, y + 5, 0xFF42636D);
    }

    private String areaVisibilityDescription(SavedAreaStore.Area area) {
        SavedAreaStore.Group group = findGroup(area.groupId());
        if (!area.visible()) return tr("area_state_hidden").getString();
        if (group != null && !group.visible()) return tr("area_hidden_by_group", group.name()).getString();
        return tr("area_state_shown").getString() + " · " + (group == null ? tr("group_none").getString() : group.name());
    }

    private void renderGroupsDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (groupDeleteConfirmation) {
            SavedAreaStore.Group group = selectedGroup();
            int count = group == null ? 0 : (int) areaStore.state().areas().stream()
                    .filter(area -> Objects.equals(area.groupId(), group.id())).count();
            String name = group == null ? "" : mc.font.plainSubstrByWidth(group.name(), w / 3);
            g.drawCenteredString(mc.font, tr("group_delete_confirm", name, count), x + w / 2, y + h / 2 - 12, -1);
            g.drawCenteredString(mc.font, tr("group_delete_ungroup_note"), x + w / 2, y + h / 2 + 6, 0xFF9BAAB7);
            return;
        }
        int col = (w - 36) / 2, right = x + w - col - 12;
        List<SavedAreaStore.Group> groups = areaStore.state().groups();
        int rows = visibleGroupDialogRows(h);
        groupScroll = Math.min(groupScroll, Math.max(0, groups.size() - rows));
        g.drawString(mc.font, tr("groups_list_title"), x + 12, y + 34, 0xFF9BAAB7);
        g.fill(x + 10, y + 46, x + 14 + col, y + h - 52, 0xFF0C141D);
        if (groups.isEmpty()) g.drawString(mc.font, tr("groups_empty"), x + 16, y + 54, 0xFF9BAAB7);
        for (int i = 0; i < rows && i + groupScroll < groups.size(); i++) {
            SavedAreaStore.Group group = groups.get(i + groupScroll);
            int rowY = y + 48 + i * 20;
            if (Objects.equals(group.id(), groupId)) g.fill(x + 10, rowY, x + 14 + col, rowY + 20, 0x8842636D);
            g.fill(x + 14, rowY + 5, x + 22, rowY + 13, group.visible() ? ACCENT : 0xFF64717A);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(group.name(), col - 22), x + 28, rowY + 1, -1);
            long count = areaStore.state().areas().stream().filter(area -> Objects.equals(area.groupId(), group.id())).count();
            String details = tr(group.visible() ? "group_state_shown" : "group_state_hidden").getString()
                    + " · " + tr("group_member_count", count).getString();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(details, col - 22), x + 28, rowY + 11, 0xFF9BAAB7);
        }
        SavedAreaStore.Group group = selectedGroup();
        g.drawString(mc.font, tr("group_selected"), right, y + 34, 0xFF9BAAB7);
        g.drawString(mc.font, mc.font.plainSubstrByWidth(group == null ? tr("group_not_selected").getString() : group.name(), col), right, y + 48, -1);
        renderManagementStatus(g, x, y, w, h, "groups_window_hint");
    }

    private void renderGroupChooser(GuiGraphics g, int x, int y, int w, int h) {
        g.drawString(mc.font, tr("group_picker_count", assignmentTargets.size()), x + 12, y + 32, 0xFF9BAAB7);
        int rows = visibleAssignmentRows(h), count = areaStore.state().groups().size() + 1;
        assignmentScroll = Math.min(assignmentScroll, Math.max(0, count - rows));
        List<SavedAreaStore.Area> targets = areaStore.state().areas().stream().filter(a -> assignmentTargets.contains(a.id())).toList();
        for (int i = 0; i < rows && i + assignmentScroll < count; i++) {
            int index = i + assignmentScroll;
            SavedAreaStore.Group group = index == 0 ? null : areaStore.state().groups().get(index - 1);
            String id = group == null ? null : group.id();
            int rowY = y + 48 + i * 20;
            if (areaDraft.groupSet ? Objects.equals(areaDraft.groupId, id)
                    : !targets.isEmpty() && targets.stream().allMatch(a -> Objects.equals(a.groupId(), id)))
                g.fill(x + 10, rowY, x + w - 10, rowY + 20, 0x8842636D);
            String name = group == null ? tr("group_none").getString() : group.name();
            if (group != null && !group.visible()) name += " · " + tr("group_state_hidden").getString();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(name, w - 32), x + 16, rowY + 5, -1);
        }
        if (!message.isEmpty()) renderManagementStatus(g, x, y, w, h, "group_picker_tooltip");
    }

    private void renderManagementStatus(GuiGraphics g, int x, int y, int w, int h, String hint) {
        String text = message.isEmpty() ? tr(loadFailed ? "area_load_failed" : hint).getString() : message;
        // In Areas the right half of the bottom row holds the autosave button.
        int textWidth = dialog == Dialog.AREAS ? (w - 36) / 2 : w - 24;
        g.drawString(mc.font, mc.font.plainSubstrByWidth(text, textWidth), x + 12, y + h - 12,
                message.isEmpty() && !loadFailed ? 0xFF9BAAB7 : 0xFFFFCF62);
    }

    private void renderWaypointsDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (pending == null) return;
        List<WaypointAccess.Point> points = filteredPoints();
        int rows = waypointRows(h);
        modalScroll = Math.min(modalScroll, Math.max(0, points.size() - rows));
        int chosen = filteredChosenPoints().size();
        g.drawString(mc.font, tr("waypoint_filter_count", chosen, points.size()), x + 12, y + 29, 0xFF9BAAB7);
        boolean scrollable = points.size() > rows;
        int rowRight = x + w - (scrollable ? 18 : 9);
        for (int i = 0; i < rows && i + modalScroll < points.size(); i++) {
            WaypointAccess.Point p = points.get(i + modalScroll); int rowY = y + 83 + i * 14;
            boolean checked = chosenPoints.contains(p.identity());
            if (checked) g.fill(x + 9, rowY - 2, rowRight, rowY + 12, 0x3342636D);
            String text = (checked ? "[x] " : "[ ] ") + p.name() + "  " + p.x() + ", " + p.y() + ", " + p.z();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(text, rowRight - x - 17), x + 13, rowY, p.disabled() ? 0xFF9BAAB7 : -1);
        }
        if (scrollable) {
            int[] thumb = waypointThumb(y, rows, points.size());
            g.fill(x + w - 14, y + 81, x + w - 10, y + 81 + rows * 14, 0xFF0C141D);
            g.fill(x + w - 14, thumb[0], x + w - 10, thumb[0] + thumb[1], scrollbarDragging ? -1 : 0xFF9BAAB7);
        }
        String hint = message.isEmpty() ? tr("waypoint_modal_hint").getString() : message;
        g.drawString(mc.font, mc.font.plainSubstrByWidth(hint, w - 24), x + 12, y + h - 70, message.isEmpty() ? 0xFF9BAAB7 : 0xFFFFCF62);
        confirm.setMessage(tr("delete_count", chosen));
        confirm.active = chosen > 0;
    }

    private int areaScroll, groupScroll;
    /** The waypoint list scrollbar follows the pointer while the left button stays down. */
    private boolean scrollbarDragging;

    private boolean overPanel(double x, double y) {
        return enabled() && x >= PANEL_X && x < PANEL_X + panelWidth
                && y >= PANEL_Y && y < PANEL_Y + panelHeight;
    }

    private boolean overNativeWidget(double x, double y) {
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && !widgets.contains(widget) && widget.visible
                    && widget.isMouseOver(x, y)) return true;
        }
        return false;
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        shiftDown = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        checkedFrame();
        if (dialog != Dialog.NONE) {
            layoutDialog();
            for (AbstractWidget widget : widgets) {
                if (widget.visible && widget.mouseClicked(event, twice)) {
                    if (widget.visible) focus(widget);
                    return true;
                }
            }
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                int[] r = dialogRect(); int x = r[0], y = r[1], w = r[2], h = r[3];
                int col = (w - 36) / 2;
                if (dialog == Dialog.COLOR) {
                    int[] colors = paletteColors();
                    int i = swatchAt(x, y, w, colors.length, event.x(), event.y());
                    if (i >= 0) applyColor(colorReturn == Dialog.WAYPOINTS ? i : colors[i]);
                    return true;
                }
                if (dialog == Dialog.AREA_NAME) {
                    int i = swatchAt(x, y, w, AreaPalette.COLORS.length, event.x(), event.y());
                    if (i >= 0) { newAreaColor = AreaPalette.COLORS[i]; focus(null); updateVisibility(); }
                    return true;
                }
                if (dialog == Dialog.AREAS && !areaDeleteConfirmation) {
                    int rows = visibleAreaDialogRows(h);
                    if (event.x() >= x + 10 && event.x() < x + 14 + col
                            && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
                        int index = areaScroll + (int) ((event.y() - y - 48) / 20);
                        List<SavedAreaStore.Area> areas = areaStore.state().areas();
                        if (index < areas.size()) {
                            SavedAreaStore.Area area = areas.get(index);
                            flushDraft();
                            boolean multiple = event.x() < x + 27
                                    || (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
                            if (!multiple) { selectedAreaIds.clear(); selectedAreaIds.add(area.id()); }
                            else if (!selectedAreaIds.remove(area.id())) selectedAreaIds.add(area.id());
                            areaId = selectedAreaIds.contains(area.id()) ? area.id()
                                    : selectedAreaIds.stream().reduce((first, last) -> last).orElse(null);
                            setDialogValue(4, selectedAreaIds.size() == 1 && selectedArea() != null ? selectedArea().name() : "");
                            focus(null); updateVisibility();
                        }
                        return true;
                    }
                } else if (dialog == Dialog.GROUPS && !groupDeleteConfirmation) {
                    int rows = visibleGroupDialogRows(h);
                    if (event.x() >= x + 10 && event.x() < x + 14 + col
                            && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
                        int index = groupScroll + (int) ((event.y() - y - 48) / 20);
                        if (index < areaStore.state().groups().size()) {
                            groupId = areaStore.state().groups().get(index).id();
                            if (event.x() < x + 26) toggleGroup();
                            focus(null); updateVisibility();
                        }
                        return true;
                    }
                } else if (dialog == Dialog.ASSIGN_GROUP) {
                    int rows = visibleAssignmentRows(h);
                    if (event.x() >= x + 10 && event.x() < x + w - 10
                            && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
                        int index = assignmentScroll + (int) ((event.y() - y - 48) / 20);
                        if (index <= areaStore.state().groups().size())
                            assignGroup(index == 0 ? null : areaStore.state().groups().get(index - 1).id());
                        return true;
                    }
                }
            }
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && dialog == Dialog.WAYPOINTS && pending != null) {
                int[] r = dialogRect(); int x = r[0], y = r[1], w = r[2], h = r[3];
                int rows = waypointRows(h);
                List<WaypointAccess.Point> points = filteredPoints();
                boolean scrollable = points.size() > rows;
                if (scrollable && event.x() >= x + w - 17 && event.x() < x + w - 7
                        && event.y() >= y + 81 && event.y() < y + 81 + rows * 14) {
                    scrollbarDragging = true;
                    dragWaypointScrollbar(event.y());
                    return true;
                }
                // Row i is drawn over [y + 81 + 14i, y + 95 + 14i); a taller hit area would toggle a hidden row.
                if (event.x() >= x + 8 && event.x() < x + w - (scrollable ? 18 : 8) && event.y() >= y + 81 && event.y() < y + 81 + rows * 14) {
                    int index = modalScroll + (int) ((event.y() - (y + 81)) / 14);
                    if (index >= 0 && index < points.size()) {
                        Object identity = points.get(index).identity();
                        if (!chosenPoints.add(identity)) chosenPoints.remove(identity);
                    }
                    updateVisibility(); return true;
                }
            }
            return true;
        }
        if (toggle.mouseClicked(event, twice)) return true;
        if (!enabled()) return false;
        if (overPanel(event.x(), event.y())) {
            focus(null);
            for (AbstractWidget widget : widgets) {
                if (widget != toggle && widget.visible && widget.mouseClicked(event, twice)) {
                    if (enabled() && pending == null && widget.visible) focus(widget);
                    break;
                }
            }
            return true;
        }
        focus(null);
        if (overNativeWidget(event.x(), event.y())) return false;
        MapBridge.Frame frame = checkedFrame();
        if (!enabled() || frame == null) return true;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            dragging = true;
            lastMouseX = event.x(); lastMouseY = event.y();
        } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (dirty || holding()) return true;
            if ((event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0
                    && (polygonMode || state.geometry() instanceof PolygonGeometry
                            || state.geometry() instanceof RectangleGeometry && state.phase() == SelectionState.Phase.SELECTED)) {
                if (twice) deleteVertex(frame, event.x(), event.y());
                else beginVertexDrag(frame, event.x(), event.y());
                if (holding() || !(state.geometry() instanceof RectangleGeometry) || twice) return true;
            }
            if (state.source() != null && state.geometry() instanceof RectangleGeometry rectangle
                    && state.phase() == SelectionState.Phase.SELECTED && polygonDraft.isEmpty() && !polygonMode) {
                int edge = hitPolygonEdge(frame, rectangle.vertices(), event.x(), event.y());
                if (edge >= 0 && (twice || hitHandle(frame, event.x(), event.y()) == null)) {
                    if (twice) insertPolygonPoint(frame, new PolygonGeometry(rectangle.vertices()), edge, event.x(), event.y());
                    else message = tr("rectangle_edit_controls").getString();
                    return true;
                }
            }
            if (polygonDraft.isEmpty() && state.geometry() instanceof PolygonGeometry polygon) {
                int edge = hitPolygonEdge(frame, polygon.vertices(), event.x(), event.y());
                if (edge >= 0) {
                    if (twice) insertPolygonPoint(frame, polygon, edge, event.x(), event.y());
                    return true;
                }
                if (hitVertex(frame, polygon.vertices(), event.x(), event.y()) >= 0
                        || !polygonMode && !shiftDown) {
                    message = tr("polygon_edit_controls").getString();
                    return true;
                }
            }
            if (polygonMode && (!polygonDraft.isEmpty()
                    || (event.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0 || !insideBounds(frame, event.x(), event.y()))) {
                addPolygonPoint(frame, event.x(), event.y());
                return true;
            }
            try {
                MapGesture.Handle handle = hitHandle(frame, event.x(), event.y());
                int worldX = SelectionBounds.block(frame.worldX(event.x()));
                int worldZ = SelectionBounds.block(frame.worldZ(event.y()));
                boolean moveWhole = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 && insideBounds(frame, event.x(), event.y());
                if (handle != null || moveWhole) {
                    gesture = MapGesture.Drag.start(state.snapshot(), moveWhole ? MapGesture.Handle.MOVE : handle,
                            frame, event.x(), event.y());
                    return true;
                }
                if (state.geometry() instanceof PolygonGeometry && !polygonMode) return true;
                boolean finishing = state.phase() == SelectionState.Phase.PREVIEW;
                SelectionState.Snapshot before = historyBase();
                if (finishing && chunks) {
                    int originX = chunkOriginX(), originZ = chunkOriginZ();
                    int anchorX = ChunkSelection.anchor(originX, worldX);
                    int anchorZ = ChunkSelection.anchor(originZ, worldZ);
                    worldX = ChunkSelection.endpoint(worldX, originX);
                    worldZ = ChunkSelection.endpoint(worldZ, originZ);
                    state.hover(anchorX, anchorZ, worldX, worldZ);
                } else if (!finishing) {
                    int originX = worldX, originZ = worldZ;
                    if (chunks) {
                        worldX = ChunkSelection.anchor(originX, originX);
                        worldZ = ChunkSelection.anchor(originZ, originZ);
                    }
                    gesture = new MapGesture.Corners(before, originX, originZ);
                }
                state.click(worldX, worldZ);
                if (finishing && !(gesture instanceof MapGesture.Corners corners && corners.finish(gestureHost)))
                    commitSelection(before, state.snapshot(), "select");
                message = "";
                syncFields();
                updateSelection();
            } catch (IllegalArgumentException e) { message = tr("invalid_coordinates").getString(); }
        }
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) dragging = false;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && scrollbarDragging) {
            dragWaypointScrollbar(event.y());
            scrollbarDragging = false;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && gesture instanceof MapGesture.Held held) {
            // A lost or changed map context disables the tool, which drops the gesture; update and finish then do nothing.
            MapBridge.Frame frame = checkedFrame();
            if (frame != null) held.update(gestureHost, frame, event.x(), event.y());
            held.finish(gestureHost);
            return true;
        }
        // Native release clears held toolbar buttons, even when released outside them.
        // Its map press flags stay false, so it cannot create a native map click.
        return false;
    }

    public boolean mouseScrolled(double x, double y, double vertical) {
        if (dialog == Dialog.WAYPOINTS && pending != null) {
            modalScroll = Math.max(0, Math.min(Math.max(0, filteredPoints().size() - waypointRows(dialogRect()[3])),
                    modalScroll - (vertical > 0 ? 1 : vertical < 0 ? -1 : 0)));
            return true;
        }
        int step = vertical > 0 ? 1 : vertical < 0 ? -1 : 0;
        if (dialog == Dialog.AREAS || dialog == Dialog.GROUPS || dialog == Dialog.ASSIGN_GROUP) {
            if (areaDeleteConfirmation || groupDeleteConfirmation) return true;
            int[] r = dialogRect();
            if (dialog == Dialog.AREAS) {
                areaScroll = Math.max(0, Math.min(Math.max(0, areaStore.state().areas().size() - visibleAreaDialogRows(r[3])), areaScroll - step));
            } else if (dialog == Dialog.GROUPS) {
                groupScroll = Math.max(0, Math.min(Math.max(0, areaStore.state().groups().size() - visibleGroupDialogRows(r[3])), groupScroll - step));
            } else {
                assignmentScroll = Math.max(0, Math.min(Math.max(0, areaStore.state().groups().size() + 1 - visibleAssignmentRows(r[3])), assignmentScroll - step));
            }
            return true;
        }
        if (dialog != Dialog.NONE) return true;
        if (enabled() && overPanel(x, y)) {
            if (!compact && step != 0) {
                panelScroll = Math.max(0, Math.min(maxPanelScroll(), panelScroll - step * 20));
                layoutMainPanel();
                updateVisibility();
                if (screen.getFocused() instanceof AbstractWidget widget && widgets.contains(widget) && !widget.visible)
                    focus(null);
            }
            return true;
        }
        return false;
    }

    private void focus(AbstractWidget target) {
        for (AbstractWidget widget : widgets) widget.setFocused(widget == target);
        screen.setFocused(target);
    }

    public void keyReleased(KeyEvent event) {
        shiftDown = event.key() != GLFW.GLFW_KEY_LEFT_SHIFT && event.key() != GLFW.GLFW_KEY_RIGHT_SHIFT
                && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    public boolean keyPressed(KeyEvent event) {
        shiftDown = event.key() == GLFW.GLFW_KEY_LEFT_SHIFT || event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT
                || (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (!enabled()) return false;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (gesture instanceof MapGesture.VertexDrag) { cancelGesture(); message = tr("draft_cancelled").getString(); }
            else if (gesture instanceof MapGesture.Drag) cancelGesture();
            else if (dialog != Dialog.NONE) cancelDialog();
            else if (polygonMode) {
                polygonDraft.clear(); polygonCursor = null; polygonBefore = null; polygonMode = false;
                polygonDraftUndo.clear(); polygonDraftRedo.clear();
                message = tr("polygon_cancelled").getString(); updateSelection(); updateVisibility();
            }
            else if (dirty) { dirty = false; syncFields(); focus(null); }
            else if (gesture instanceof MapGesture.Corners && state.phase() == SelectionState.Phase.PREVIEW) cancelGesture();
            else if (state.bounds() != null) reset();
            else disable();
            return true;
        }
        boolean editing = screen.getFocused() instanceof EditBox field && widgets.contains(field);
        if (polygonMode && dialog == Dialog.NONE && !editing && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            removeLastPolygonPoint();
            return true;
        }
        if (polygonMode && !holding() && dialog == Dialog.NONE && !editing && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            finishPolygon();
            return true;
        }
        if (!editing && (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            if (event.key() == GLFW.GLFW_KEY_Z) {
                if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) redo(); else undo();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_Y) { redo(); return true; }
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            List<AbstractWidget> available = widgets.stream().filter(w -> w.visible && w.active).toList();
            int index = available.indexOf(screen.getFocused());
            int step = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            if (!available.isEmpty()) focus(available.get(index < 0
                    ? step > 0 ? 0 : available.size() - 1
                    : Math.floorMod(index + step, available.size())));
            return true;
        }
        if (screen.getFocused() instanceof EditBox field && widgets.contains(field)) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                if ((dialog == Dialog.SIZE || dialog == Dialog.MOVE || dialog == Dialog.AREA_NAME || dialog == Dialog.GROUP_NAME)
                        && modalApply.active) applyDialog();
                else if (dialog == Dialog.AREAS && field == dialogFields[4])
                    commitDraft(autosave.covers(AutosaveSettings.Field.NAME) ? f -> f == AutosaveSettings.Field.NAME : f -> true);
                else if (dialog == Dialog.CENTER) createCenterWaypoint();
                else if (dialog == Dialog.COLOR && field == dialogFields[HEX_FIELD]) applyHexColor();
                else if (dialog == Dialog.WAYPOINTS && field == dialogFields[RENAME_FIELD] && pointRename.active) renamePoints();
                else if (dialog == Dialog.NONE) applyFields();
            }
            else field.keyPressed(event);
        } else if (screen.getFocused() instanceof AbstractWidget widget && widgets.contains(widget)) {
            widget.keyPressed(event);
        }
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!enabled()) return false;
        if (screen.getFocused() instanceof EditBox field && widgets.contains(field)) field.charTyped(event);
        return true;
    }

    private void syncFields() {
        if (fields[0] == null) return;
        SelectionBounds b = state.bounds();
        int[] values = b == null ? null : new int[]{b.x1(), b.z1(), b.x2(), b.z2()};
        syncing = true;
        for (int i = 0; i < 4; i++) fields[i].setValue(values == null ? "" : Integer.toString(values[i]));
        syncing = false;
        dirty = false;
    }

    private void applyFields() {
        if (state.geometry() instanceof PolygonGeometry || !polygonDraft.isEmpty() || holding()) return;
        try {
            SelectionState.Snapshot before = historyBase();
            int[] c = new int[4];
            for (int i = 0; i < 4; i++) c[i] = SelectionBounds.block(Integer.parseInt(fields[i].getValue().trim()));
            state.set(new SelectionBounds(c[0], c[1], c[2], c[3]));
            gesture = null;
            commitSelection(before, state.snapshot(), "coordinates");
            focus(null);
            message = "";
        } catch (IllegalArgumentException e) { message = tr("invalid_coordinates").getString(); }
    }

    private static int waypointRows(int height) { return Math.max(1, (height - 150) / 14); }

    /** Scrollbar thumb {top, height} for a list of {@code total} rows showing {@code rows} from {@code modalScroll}. */
    private int[] waypointThumb(int y, int rows, int total) {
        int track = rows * 14, size = Math.max(10, track * rows / total);
        int range = total - rows, top = y + 81 + (range <= 0 ? 0 : (track - size) * modalScroll / range);
        return new int[]{top, size};
    }

    /** Scrolls so the thumb centre follows the pointer along the track. */
    private void dragWaypointScrollbar(double mouseY) {
        int[] r = dialogRect(); int rows = waypointRows(r[3]), total = filteredPoints().size();
        if (total <= rows) return;
        int size = waypointThumb(r[1], rows, total)[1], track = rows * 14;
        double ratio = (mouseY - (r[1] + 81) - size / 2.0) / Math.max(1, track - size);
        modalScroll = (int) Math.round(Math.max(0, Math.min(1, ratio)) * (total - rows));
    }

    private WaypointAccess.Snapshot scopeSnapshot() {
        return waypoints == null ? null : pointsInSelection ? waypoints.select(context, state.geometry()) : waypoints.all(context);
    }

    private void openWaypoints() {
        if (waypoints == null) { message = tr("minimap_missing").getString(); return; }
        if (dirty || holding() || !polygonDraft.isEmpty() || checkedFrame() == null || !enabled()) return;
        pointsInSelection = state.phase() == SelectionState.Phase.SELECTED;
        pending = waypoints.captureSet(scopeSnapshot());
        if (pending == null) { message = tr("waypoints_unavailable").getString(); return; }
        modalScroll = 0; chosenPoints.clear();
        if (pointsInSelection) for (WaypointAccess.Point p : pending.points()) if (p.editable()) chosenPoints.add(p.identity());
        setDialogValue(7, ""); setDialogValue(RENAME_FIELD, "");
        visibilityFilter = 0; colorFilter = -1;
        pointVisibility.setMessage(tr("filter_all")); pointColor.setMessage(tr("filter_color_all"));
        dragging = false; message = ""; dialog = Dialog.WAYPOINTS;
        updateVisibility(); focus(cancel);
    }

    private void togglePointScope() {
        if (state.phase() != SelectionState.Phase.SELECTED) return;
        pointsInSelection = !pointsInSelection;
        refreshPending(pointsInSelection);
        updateVisibility();
    }

    private void refreshPending(boolean selectAll) {
        pending = waypoints.captureSet(scopeSnapshot());
        Set<Object> existing = new HashSet<>();
        if (pending != null) for (WaypointAccess.Point p : pending.points()) if (p.editable()) existing.add(p.identity());
        chosenPoints.retainAll(existing);
        if (selectAll) chosenPoints.addAll(existing);
        modalScroll = 0;
    }

    private boolean validatePointSnapshot() {
        if (checkedFrame() == null || !enabled() || waypoints == null || pending == null) return false;
        WaypointAccess.Snapshot current = waypoints.captureSet(scopeSnapshot());
        if (pending.equals(current)) return true;
        pending = current;
        Set<Object> existing = new HashSet<>();
        if (pending != null) for (WaypointAccess.Point p : pending.points()) if (p.editable()) existing.add(p.identity());
        chosenPoints.retainAll(existing);
        modalScroll = 0; message = tr("changed").getString();
        if (dialog == Dialog.COLOR) dialog = Dialog.WAYPOINTS;
        focus(cancel); updateVisibility();
        return false;
    }

    private void renamePoints() {
        String name = dialogFields[RENAME_FIELD].getValue().trim();
        if (!name.isEmpty()) editPoints(name, null);
    }

    private void editPoints(String name, Integer color) {
        if (!validatePointSnapshot()) return;
        List<WaypointAccess.Point> from = filteredChosenPoints();
        if (from.isEmpty()) return;
        List<WaypointAccess.Point> to = from.stream().map(p -> p.with(name == null ? p.name() : name, color == null ? p.color() : color)).toList();
        WaypointAccess.Snapshot target = pending;
        try {
            waypoints.edit(target, from, to);
            history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { waypoints.edit(target, to, from); }
                @Override public void redo() throws Exception { waypoints.edit(target, from, to); }
                @Override public String label() { return tr("history_waypoint_edit", from.size()).getString(); }
            });
            message = tr("waypoints_edited", from.size()).getString();
            dialog = Dialog.WAYPOINTS; refreshPending(false);
        } catch (Exception e) {
            message = tr("save_failed").getString();
            LoggerFactory.getLogger("xaero-exact-selection").error("Could not edit waypoints", e);
        }
        focus(null); updateSelection(); updateVisibility();
    }

    private void confirmDelete() {
        if (dialog == Dialog.GROUPS && groupDeleteConfirmation) { confirmGroupDelete(); return; }
        if (dialog == Dialog.AREAS && areaDeleteConfirmation) { confirmAreaDelete(); return; }
        if (dialog != Dialog.WAYPOINTS || !validatePointSnapshot()) return;
        List<WaypointAccess.Point> targets = filteredChosenPoints();
        if (targets.isEmpty()) return;
        WaypointAccess.Snapshot deletion = new WaypointAccess.Snapshot(pending.world(), pending.set(), pending.setName(), targets, pending.setPoints());
        int count = targets.size();
        try {
            waypoints.delete(deletion);
            history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { waypoints.restore(deletion); }
                @Override public void redo() throws Exception { waypoints.delete(deletion); }
                @Override public String label() { return tr("history_delete_waypoints", count).getString(); }
            });
            message = tr("deleted", count).getString();
            refreshPending(false);
        } catch (Exception e) {
            LoggerFactory.getLogger("xaero-exact-selection").error("Could not save waypoint deletion; restored in-memory set", e);
            message = tr("save_failed").getString();
        }
        focus(null); updateSelection(); updateVisibility();
    }

}
