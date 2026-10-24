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
    private static final int ACCENT = 0xFF59E3CE;
    private record MapLabel(Component text, int x, int y, int color) {}
    private record ScreenPoint(double x, double y) {}
    private enum Dialog { NONE, SIZE, MOVE, CENTER, AREAS, GROUPS, ASSIGN_GROUP, AREA_NAME, GROUP_NAME, WAYPOINTS }
    private enum Drag { NONE, VERTEX, MOVE, WEST, EAST, NORTH, SOUTH, NORTH_WEST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST }
    private final GuiMap screen;
    private final MapBridge bridge;
    private final Minecraft mc;
    private final SelectionState state = new SelectionState();
    private final SelectionHistory history = new SelectionHistory();
    private final WaypointAccess waypoints;
    private final SavedAreaStore areaStore;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final EditBox[] fields = new EditBox[4];
    private final EditBox[] dialogFields = new EditBox[9];
    private Button toggle, apply, clear, delete, confirm, cancel;
    private Button copyCoordinates, copyArea, copyLength, copyWidth;
    private Button undo, redo, chunkMode, sizeButton, moveButton, centerButton, areasButton, groupsButton;
    private Button polygonModeButton, panelModeButton, selectionExportButton;
    private boolean compact = true;
    private int panelHeight, panelScroll;
    private Button modalApply, modalCancel, anchorButton, moveUnitsButton;
    private Button centerCopy, centerCreate, areaSave, areaLoad, areaUpdate, areaDelete;
    private Button areaMerge, areaSelectAll, areaClearSelection;
    private Button groupCreate, groupRename, groupAssign, groupVisibility, areaVisibility, areaColor, areaOpacity;
    private Button areaRename, groupDelete, manageGroups, managementClose, managementUndo, managementRedo;
    private Button areaExportButton, openExportButton;
    private Path lastExport;
    private String exportNotice = "";
    private Button areaMapVisibility, areaMinimapVisibility;
    private Button pointVisibility, pointColor;
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
    private Drag drag = Drag.NONE;
    private SelectionBounds dragStartBounds;
    private AreaGeometry dragStartGeometry;
    private SelectionState.Snapshot dragBefore;
    private SelectionState.Snapshot gestureBefore;
    private int dragStartX, dragStartZ;
    private double dragOffsetX, dragOffsetZ;
    private int chunkOriginX, chunkOriginZ;
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
    private int vertexIndex = -1;
    private long vertexPointerX, vertexPointerZ;
    private List<GridPoint> vertexDraftBefore;
    private boolean vertexDragInvalid;

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
        copyLength = copyButton("copy_length_short", "copy_length_tooltip", this::copyLength);
        copyWidth = copyButton("copy_width_short", "copy_width_tooltip", this::copyWidth);
        undo = button("undo", 0, 0, 100, this::undo);
        redo = button("redo", 0, 0, 100, this::redo);
        chunkMode = button("units_blocks", 0, 0, 100, this::toggleChunks);
        sizeButton = button("size", 0, 0, 100, () -> openDialog(Dialog.SIZE));
        moveButton = button("move", 0, 0, 100, () -> openDialog(Dialog.MOVE));
        centerButton = button("center", 0, 0, 100, () -> openDialog(Dialog.CENTER));
        polygonModeButton = button("polygon_mode", 0, 0, 100, this::togglePolygonMode);
        areasButton = button("areas_window", 0, 0, 100, () -> openDialog(Dialog.AREAS));
        groupsButton = button("groups_window", 0, 0, 100, () -> openGroupsWindow(Dialog.NONE));
        clear = button("clear", 0, 0, 100, this::reset);
        delete = button("delete", 0, 0, 100, this::askDelete);
        confirm = button("confirm", 0, 0, 110, this::confirmDelete);
        cancel = button("cancel", 0, 0, 110, this::cancelDialog);
        for (int i = 0; i < dialogFields.length; i++) {
            EditBox field = new EditBox(mc.font, 0, 0, 100, 18, Component.empty());
            field.setMaxLength(i < 4 ? 10 : 128);
            field.setResponder(value -> { if (!syncing) updateVisibility(); });
            dialogFields[i] = field;
            widgets.add(field);
        }
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
        areaSave = button("area_save_selection", 0, 0, 110, this::openAreaNameDialog);
        areaLoad = button("area_load", 0, 0, 110, this::loadArea);
        areaUpdate = button("area_update", 0, 0, 110, this::updateArea);
        areaDelete = button("area_delete", 0, 0, 110, this::deleteArea);
        areaMerge = button("area_merge", 0, 0, 110, this::mergeAreas);
        areaSelectAll = button("area_select_all", 0, 0, 110, this::selectAllAreas);
        areaClearSelection = button("area_clear_selection", 0, 0, 110, this::clearAreaSelection);
        groupCreate = button("group_create", 0, 0, 110, () -> openGroupNameDialog(false));
        groupRename = button("group_rename_action", 0, 0, 110, () -> openGroupNameDialog(true));
        groupAssign = button("group_choose", 0, 0, 120, this::openGroupChooser);
        groupVisibility = button("group_toggle", 0, 0, 120, this::toggleGroup);
        areaVisibility = button("area_toggle", 0, 0, 120, this::toggleArea);
        areaColor = button("area_color", 0, 0, 100, this::cycleAreaColor);
        areaOpacity = button("area_fill", 0, 0, 100, this::cycleAreaOpacity);
        areaMapVisibility = button("area_map_on", 0, 0, 120, this::toggleAreaMapVisibility);
        areaMinimapVisibility = button("area_minimap_on", 0, 0, 120, this::toggleAreaMinimapVisibility);
        manageGroups = button("groups_manage", 0, 0, 120,
                () -> openGroupsWindow(dialog == Dialog.ASSIGN_GROUP ? Dialog.ASSIGN_GROUP : Dialog.AREAS));
        managementClose = button("done", 0, 0, 80, this::cancelDialog);
        managementUndo = button("undo", 0, 0, 80, this::undo);
        managementRedo = button("redo", 0, 0, 80, this::redo);
        areaRename = button("area_rename", 0, 0, 120, this::renameArea);
        groupDelete = button("group_delete", 0, 0, 120, this::askDeleteGroup);
        areaExportButton = button("area_export", 0, 0, 120, this::exportSelectedArea);
        pointVisibility = button("filter_all", 0, 0, 120, this::cycleVisibilityFilter);
        pointColor = button("filter_color_all", 0, 0, 120, this::cycleColorFilter);
        delete.setTooltip(Tooltip.create(tr(waypoints == null ? "minimap_missing" : "scope")));
        areaVisibility.setTooltip(Tooltip.create(tr("area_visibility_rules")));
        groupVisibility.setTooltip(Tooltip.create(tr("group_toggle_tooltip")));
        groupAssign.setTooltip(Tooltip.create(tr("group_picker_tooltip")));
        areaLoad.setTooltip(Tooltip.create(tr("area_load_tooltip")));
        areaUpdate.setTooltip(Tooltip.create(tr("area_update_tooltip")));
        areaColor.setTooltip(Tooltip.create(tr("area_color_tooltip")));
        areaOpacity.setTooltip(Tooltip.create(tr("area_opacity_tooltip")));
        areaMapVisibility.setTooltip(Tooltip.create(tr("area_map_tooltip")));
        areaMinimapVisibility.setTooltip(Tooltip.create(tr("area_minimap_tooltip")));
        areaDelete.setTooltip(Tooltip.create(tr("area_delete_tooltip")));
        areaMerge.setTooltip(Tooltip.create(tr("area_merge_tooltip")));
        areaSelectAll.setTooltip(Tooltip.create(tr("area_select_all_tooltip")));
        areaClearSelection.setTooltip(Tooltip.create(tr("area_clear_selection_tooltip")));
        areaRename.setTooltip(Tooltip.create(tr("area_rename_tooltip")));
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
        panelHeight = Math.min(compact ? 170 : 314, screen.height - PANEL_Y - 8);
        panelScroll = Math.max(0, Math.min(panelScroll, maxPanelScroll()));
        int x = PANEL_X + 8, y = PANEL_Y - (compact ? 0 : panelScroll);
        place(panelModeButton, PANEL_X + panelWidth - 80, PANEL_Y + 4, 72, 16);
        if (compact) {
            Button[] copies = {copyCoordinates, copyArea, copyLength, copyWidth};
            mainRow(copies, x, y + 87, panelWidth - 16, 16);
            mainRow(new Button[]{undo, redo, clear}, x, y + 108, panelWidth - 16, 18);
            mainRow(new Button[]{areasButton, groupsButton, polygonModeButton}, x, y + 129, panelWidth - 16, 18);
        } else {
            fieldWidth = (panelWidth - 16 - coordinateLabelWidth - zLabelWidth - fieldGap * 3) / 2;
            int xField = x + coordinateLabelWidth + fieldGap;
            int zField = xField + fieldWidth + fieldGap * 2 + zLabelWidth;
            for (int i = 0; i < fields.length; i++)
                place(fields[i], i % 2 == 0 ? xField : zField, y + 34 + i / 2 * 24, fieldWidth, 18);
            place(apply, x, y + 82, panelWidth - 16, 18);
            mainRow(new Button[]{copyCoordinates, copyArea, copyLength, copyWidth}, x, y + 129, panelWidth - 16, 16);
            mainRow(new Button[]{undo, redo, chunkMode}, x, y + 151, panelWidth - 16, 18);
            mainRow(new Button[]{sizeButton, moveButton, centerButton, polygonModeButton}, x, y + 174, panelWidth - 16, 18);
            mainRow(new Button[]{areasButton, groupsButton, clear}, x, y + 197, panelWidth - 16, 18);
            place(delete, x, y + 220, panelWidth - 16, 18);
            place(selectionExportButton, x, y + 243, panelWidth - 16, 18);
        }
    }

    private void mainRow(Button[] buttons, int x, int y, int width, int height) {
        int step = (width + 3) / buttons.length;
        for (int i = 0; i < buttons.length; i++)
            place(buttons[i], x + step * i, y, i == buttons.length - 1 ? width - step * i : step - 3, height);
    }

    private int maxPanelScroll() {
        return compact ? 0 : Math.max(0, 314 - panelHeight + (panelHeight < 314 ? 10 : 0));
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
        if (dirty || !polygonDraft.isEmpty() || drag != Drag.NONE || (pending != null && next != Dialog.WAYPOINTS)) return;
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
            if (areaId == null && !areaStore.state().areas().isEmpty()) areaId = areaStore.state().areas().get(0).id();
            selectedAreaIds.clear();
            if (areaId != null) selectedAreaIds.add(areaId);
            areaDeleteConfirmation = groupDeleteConfirmation = false;
            SavedAreaStore.Area selectedArea = selectedArea();
            setDialogValue(4, selectedArea == null ? "" : selectedArea.name());
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
        if (dirty || pending != null || !polygonDraft.isEmpty() || drag != Drag.NONE) return;
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
        dialog = switch (dialog) {
            case AREA_NAME, ASSIGN_GROUP -> Dialog.AREAS;
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
            case ASSIGN_GROUP, AREA_NAME, GROUP_NAME -> 420;
            default -> 560;
        }, screen.width - 20);
        int height = switch (dialog) {
            case SIZE, MOVE -> Math.min(180, screen.height - 16);
            case CENTER -> Math.min(150, screen.height - 16);
            case AREAS, GROUPS -> Math.min(460, screen.height - 16);
            case ASSIGN_GROUP -> Math.min(330, screen.height - 16);
            case AREA_NAME, GROUP_NAME -> Math.min(140, screen.height - 16);
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
        int fitting = Math.max(1, (height - 150) / 20);
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
                place(manageGroups, x + w - 180, y + 7, 92, 18);
                place(managementClose, x + w - 82, y + 7, 70, 18);
                place(dialogFields[4], right, y + 46, col, 18);
                place(areaRename, right, y + 66, col, 18);
                place(groupAssign, right, y + 86, col, 18);
                place(areaVisibility, right, y + 106, col, 18);
                place(areaColor, right, y + 126, half, 18);
                place(areaOpacity, right + half + 3, y + 126, col - half - 3, 18);
                place(areaLoad, right, y + 146, half, 18);
                place(areaUpdate, right + half + 3, y + 146, col - half - 3, 18);
                place(areaExportButton, right, y + 166, col, 18);
                place(openExportButton, right, y + h - 20, col, 16);
                place(areaDelete, right, y + 186, half, 18);
                place(areaMerge, right + half + 3, y + 186, col - half - 3, 18);
                place(areaSelectAll, x + 12, y + h - 92, half, 18);
                place(areaClearSelection, x + 15 + half, y + h - 92, col - half - 3, 18);
                place(areaSave, x + 12, y + h - 72, col, 18);
                place(areaMapVisibility, x + 12, y + h - 52, half, 18);
                place(areaMinimapVisibility, x + 15 + half, y + h - 52, col - half - 3, 18);
                place(managementUndo, x + 12, y + h - 32, half, 18);
                place(managementRedo, x + 15 + half, y + h - 32, col - half - 3, 18);
            }
            case GROUPS -> {
                if (groupDeleteConfirmation) {
                    place(confirm, x + w / 2 - 116, buttonY, 110, 18);
                    place(cancel, x + w / 2 + 6, buttonY, 110, 18);
                    break;
                }
                int col = (w - 36) / 2, right = x + w - col - 12, half = (col - 3) / 2;
                place(managementClose, x + w - 102, y + 7, 90, 18);
                place(groupCreate, right, y + 78, col, 18);
                place(groupRename, right, y + 100, col, 18);
                place(groupVisibility, right, y + 122, col, 18);
                place(groupDelete, right, y + 144, col, 18);
                place(managementUndo, right, y + h - 32, half, 18);
                place(managementRedo, right + half + 3, y + h - 32, col - half - 3, 18);
            }
            case ASSIGN_GROUP -> {
                place(managementClose, x + w - 82, y + 7, 70, 18);
                place(manageGroups, x + 12, y + h - 36, w - 24, 18);
            }
            case AREA_NAME, GROUP_NAME -> {
                place(dialogFields[dialog == Dialog.AREA_NAME ? 8 : 5], x + 16, y + 48, w - 32, 18);
                place(modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case WAYPOINTS -> {
                place(dialogFields[7], x + 12, y + 38, w - 24, 18);
                place(pointVisibility, x + 12, y + 60, (w - 27) / 2, 18);
                place(pointColor, x + 15 + (w - 27) / 2, y + 60, (w - 27) / 2, 18);
                place(confirm, x + w / 2 - 116, buttonY, 110, 18);
                place(cancel, x + w / 2 + 6, buttonY, 110, 18);
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
        setDialogValue(4, selectedAreaIds.size() == 1 && selectedArea() != null ? selectedArea().name() : "");
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
        SavedAreaStore.Area area = SavedAreaStore.create(name, geometry, 0x59E3CE, 25, null);
        List<SavedAreaStore.Area> areas = new ArrayList<>(areaStore.state().areas()); areas.add(area);
        if (!changeAreas(areaState(areas, areaStore.state().groups()), "area_save")) return;
        selectedAreaIds.clear(); selectedAreaIds.add(area.id()); areaId = area.id();
        setDialogValue(4, area.name());
        dialog = Dialog.AREAS;
        areaScroll = Math.max(0, areas.size() - visibleAreaDialogRows(dialogRect()[3]));
        message = tr("area_created").getString();
        focus(null); updateVisibility();
    }

    private void loadArea() {
        SavedAreaStore.Area area = selectedArea();
        if (area == null) return;
        SelectionState.Snapshot before = state.snapshot();
        state.setGeometry(area.geometry());
        polygonMode = false;
        commitSelection(before, state.snapshot(), "area_load");
        dialog = Dialog.NONE;
        message = tr(state.geometry() instanceof PolygonGeometry ? "polygon_loaded" : "area_loaded").getString();
        focus(null);
        updateVisibility();
    }

    private void updateArea() {
        SavedAreaStore.Area a = selectedArea(); AreaGeometry geometry = state.geometry();
        if (a == null || selectedAreas().size() != 1 || geometry == null || dirty || !polygonDraft.isEmpty()
                || drag != Drag.NONE || loadFailed || state.phase() != SelectionState.Phase.SELECTED) return;
        if (a.geometry().equals(geometry)) {
            message = tr("area_geometry_unchanged").getString();
            return;
        }
        List<SavedAreaStore.Area> areas = new ArrayList<>(areaStore.state().areas());
        areas.replaceAll(v -> v.id().equals(a.id()) ? v.withGeometry(geometry) : v);
        if (changeAreas(areaState(areas, areaStore.state().groups()), "area_update")) {
            dialog = Dialog.NONE;
            message = tr("area_updated").getString();
            focus(null);
            updateVisibility();
        }
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
        selectedAreaIds.clear();
        areaId = null;
        setDialogValue(4, "");
        updateVisibility();
    }

    private void mergeAreas() {
        if (checkedFrame() == null || !enabled()) return;
        List<SavedAreaStore.Area> selectedAreas = selectedAreas();
        if (selectedAreas.size() < 2 || loadFailed) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean allSameGroup = true, anyVisible = false;
        String mergedGroup = selectedAreas.get(0).groupId();
        SavedAreaStore.Area style = selectedArea();
        if (style == null || !selectedAreaIds.contains(style.id())) style = selectedAreas.get(0);
        for (SavedAreaStore.Area area : selectedAreas) {
            minX = Math.min(minX, area.bounds().minX());
            minZ = Math.min(minZ, area.bounds().minZ());
            maxX = Math.max(maxX, area.bounds().maxX());
            maxZ = Math.max(maxZ, area.bounds().maxZ());
            allSameGroup &= Objects.equals(mergedGroup, area.groupId());
            anyVisible |= area.visible();
        }
        String name = dialogFields[4].getValue().trim();
        if (name.isEmpty()) name = tr("area_merged_default").getString();
        SelectionBounds bounds = new SelectionBounds(minX, minZ, maxX, maxZ);
        String targetGroup = allSameGroup ? mergedGroup : null;
        SavedAreaStore.Area created = SavedAreaStore.create(name, bounds, style.color(), style.opacity(), targetGroup);
        SavedAreaStore.Area merged = new SavedAreaStore.Area(created.id(), created.name(), created.x1(), created.z1(),
                created.x2(), created.z2(), created.color(), created.opacity(), created.groupId(), anyVisible);
        List<SavedAreaStore.Area> current = areaStore.state().areas();
        int firstIndex = current.size();
        for (int i = 0; i < current.size(); i++) {
            if (selectedAreaIds.contains(current.get(i).id())) { firstIndex = i; break; }
        }
        List<SavedAreaStore.Area> next = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            SavedAreaStore.Area area = current.get(i);
            if (i == firstIndex) next.add(merged);
            if (!selectedAreaIds.contains(area.id())) next.add(area);
        }
        if (changeAreas(areaState(next, areaStore.state().groups()), "area_merge")) {
            selectedAreaIds.clear();
            selectedAreaIds.add(merged.id());
            areaId = merged.id();
            setDialogValue(4, merged.name());
            message = tr("area_merged").getString();
            updateVisibility();
        }
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

    private void cycleAreaColor() {
        SavedAreaStore.Area a = selectedArea(); if (a == null) return;
        int[] colors = {0x59E3CE, 0xFFCF62, 0x63A8FF, 0xFF6B6B, 0xB78BFF, 0x7CE38B, 0xFFFFFF};
        int index = -1; for (int i = 0; i < colors.length; i++) if (colors[i] == a.color()) index = i;
        int next = colors[(index + 1) % colors.length];
        updateArea(a, new SavedAreaStore.Area(a.id(), a.name(), a.x1(), a.z1(), a.x2(), a.z2(), next, a.opacity(), a.groupId(), a.visible(), a.vertices()), "area_update");
    }

    private void cycleAreaOpacity() {
        SavedAreaStore.Area a = selectedArea(); if (a == null) return;
        int[] values = {0, 25, 50, 75, 100};
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - a.opacity()) < Math.abs(values[index] - a.opacity())) index = i;
        }
        int nextOpacity = values[(index + 1) % values.length];
        updateArea(a, new SavedAreaStore.Area(a.id(), a.name(), a.x1(), a.z1(), a.x2(), a.z2(), a.color(), nextOpacity, a.groupId(), a.visible(), a.vertices()), "area_update");
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
        Set<String> targets = Set.copyOf(assignmentTargets);
        List<SavedAreaStore.Area> areas = new ArrayList<>(areaStore.state().areas());
        int count = (int) areas.stream().filter(a -> targets.contains(a.id())).count();
        areas.replaceAll(a -> targets.contains(a.id())
                ? new SavedAreaStore.Area(a.id(), a.name(), a.x1(), a.z1(), a.x2(), a.z2(),
                        a.color(), a.opacity(), nextGroupId, a.visible(), a.vertices()) : a);
        SavedAreaStore.State next = areaState(areas, areaStore.state().groups());
        if (!areaStore.state().equals(next) && !changeAreas(next, "area_update")) return;
        dialog = Dialog.AREAS;
        assignmentTargets.clear();
        message = tr("group_assigned", count, group == null ? tr("group_none").getString() : group.name()).getString();
        focus(null); updateVisibility();
    }

    private void toggleGroup() {
        SavedAreaStore.Group g = selectedGroup(); if (g == null || loadFailed) return;
        List<SavedAreaStore.Group> groups = new ArrayList<>(areaStore.state().groups());
        groups.replaceAll(v -> v.id().equals(g.id()) ? new SavedAreaStore.Group(v.id(), v.name(), !v.visible()) : v);
        if (changeAreas(areaState(areaStore.state().areas(), groups), "group_toggle"))
            message = tr(g.visible() ? "group_hidden" : "group_shown", g.name()).getString();
    }

    private void renameArea() {
        SavedAreaStore.Area area = selectedArea();
        String name = dialogFields[4].getValue().trim();
        if (area == null || name.isEmpty() || loadFailed) return;
        SavedAreaStore.Area renamed = new SavedAreaStore.Area(area.id(), name, area.x1(), area.z1(), area.x2(),
                area.z2(), area.color(), area.opacity(), area.groupId(), area.visible(), area.vertices());
        updateArea(area, renamed, "area_rename");
        if (Objects.equals(selectedArea() == null ? null : selectedArea().name(), name))
            message = tr("area_renamed").getString();
    }

    private void exportSelectedArea() {
        MapBridge.Frame frame = checkedFrame();
        SavedAreaStore.Area area = selectedArea();
        if (area == null || frame == null || loadFailed) return;
        exportGeometry(frame, area.geometry());
    }

    private boolean canExportSelection() {
        return enabled() && dialog == Dialog.NONE && state.phase() == SelectionState.Phase.SELECTED
                && state.geometry() != null && !dirty && !dragging && drag == Drag.NONE
                && gestureBefore == null && polygonDraft.isEmpty() && !loadFailed;
    }

    private void exportCurrentSelection() {
        MapBridge.Frame frame = checkedFrame();
        if (frame == null || !canExportSelection()) return;
        exportGeometry(frame, state.geometry());
    }

    private void exportGeometry(MapBridge.Frame frame, AreaGeometry geometry) {
        try {
            AreaExportService.Result result = AreaExportService.export(bridge.exactselection$processor(),
                    frame.context(), geometry, areaStore.state().areas(), areaStore.state().groups());
            exportCompleted(result);
        } catch (AreaExportService.TooLargeException e) {
            message = tr("area_export_too_large", AreaExportService.MAX_PIXELS).getString();
        } catch (Exception e) {
            message = tr("area_export_failed").getString();
            LoggerFactory.getLogger("xaero-exact-selection").error("Could not export selected area", e);
        }
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

    private void copyCoordinates() {
        SelectionBounds b = copyableBounds();
        if (b != null && state.geometry() instanceof PolygonGeometry polygon) {
            copyToClipboard(polygon.vertices().stream().map(p -> p.x() + "," + p.z())
                    .collect(java.util.stream.Collectors.joining("; ")));
        } else if (b != null) copyToClipboard(b.x1() + ", " + b.z1() + "; " + b.x2() + ", " + b.z2());
    }

    private void copyArea() {
        SelectionBounds b = copyableBounds();
        if (b != null) copyToClipboard(formatMetric(state.geometry().area()));
    }

    private void copyLength() {
        SelectionBounds b = copyableBounds();
        if (b != null) copyToClipboard(Long.toString(b.length()));
    }

    private void copyWidth() {
        SelectionBounds b = copyableBounds();
        if (b != null) copyToClipboard(Long.toString(b.width()));
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
        if (!enabled() || drag != Drag.NONE) return;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        if (polygonMode) {
            polygonMode = false;
            polygonDraft.clear();
            polygonCursor = null;
            polygonBefore = null;
            message = tr("polygon_cancelled").getString();
        } else {
            if (state.phase() == SelectionState.Phase.PREVIEW) {
                if (gestureBefore != null) state.restore(gestureBefore); else state.reset();
                gestureBefore = null;
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
            GridPoint point = polygonPoint(frame, x, y, points.get(edge));
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
            gestureBefore = null;
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
        if (polygonDraft.isEmpty() || drag != Drag.NONE) return;
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

    private void beginVertexDrag(MapBridge.Frame frame, double x, double y) {
        List<GridPoint> points = polygonMode && !polygonDraft.isEmpty() ? polygonDraft
                : state.geometry() instanceof PolygonGeometry polygon ? polygon.vertices() : List.of();
        int index = hitVertex(frame, points, x, y);
        if (index < 0) return;
        vertexIndex = index;
        vertexDraftBefore = polygonMode && !polygonDraft.isEmpty() ? List.copyOf(points) : null;
        vertexPointerX = Math.round(frame.worldX(x)); vertexPointerZ = Math.round(frame.worldZ(y));
        dragBefore = state.snapshot();
        dragStartGeometry = state.geometry();
        drag = Drag.VERTEX;
        vertexDragInvalid = false;
        polygonCursor = null;
        message = tr("polygon_vertex_drag").getString();
        updateVisibility();
    }

    private void updateVertexDrag(MapBridge.Frame frame, double x, double y) {
        List<GridPoint> original = vertexDraftBefore != null ? vertexDraftBefore : dragStartGeometry.vertices();
        try {
            GridPoint start = original.get(vertexIndex);
            GridPoint point = new GridPoint(start.x() + Math.round(frame.worldX(x)) - vertexPointerX,
                    start.z() + Math.round(frame.worldZ(y)) - vertexPointerZ);
            List<GridPoint> moved = new ArrayList<>(original);
            moved.set(vertexIndex, point);
            if (new HashSet<>(moved).size() != moved.size()) throw new IllegalArgumentException();
            if (vertexDraftBefore != null) {
                polygonDraft.clear(); polygonDraft.addAll(moved);
            } else {
                state.setGeometry(new PolygonGeometry(moved));
                syncFields(); updateSelection();
            }
            vertexDragInvalid = false;
            message = tr("polygon_vertex_drag").getString();
        } catch (IllegalArgumentException invalid) {
            vertexDragInvalid = true;
            message = tr("polygon_vertex_invalid").getString();
        }
        updateVisibility();
    }

    private void clearVertexGesture() {
        drag = Drag.NONE; vertexIndex = -1; vertexDraftBefore = null; vertexDragInvalid = false;
        dragBefore = null; dragStartGeometry = null; dragStartBounds = null;
    }

    private void cancelVertexDrag() {
        if (vertexDraftBefore != null) {
            polygonDraft.clear(); polygonDraft.addAll(vertexDraftBefore);
        } else if (dragBefore != null) state.restore(dragBefore);
        clearVertexGesture();
        refreshSelectionState();
    }

    private void finishVertexDrag() {
        if (vertexDragInvalid) {
            cancelVertexDrag();
            message = tr("polygon_vertex_rejected").getString();
        } else if (vertexDraftBefore != null) {
            if (!vertexDraftBefore.equals(polygonDraft)) recordDraftChange(vertexDraftBefore);
            clearVertexGesture();
            message = tr("polygon_vertex_moved").getString();
        } else {
            SelectionState.Snapshot before = dragBefore;
            clearVertexGesture();
            commitSelection(before, state.snapshot(), "vertex");
            message = tr("polygon_vertex_moved").getString();
        }
        updateVisibility();
    }

    public void disable() {
        if (enabled()) bridge.exactselection$clearNativeInput();
        if (state.bounds() != null) {
            var before = state.phase() == SelectionState.Phase.PREVIEW && gestureBefore != null ? gestureBefore : state.snapshot();
            state.disable();
            history.recordApplied(selectionAction(before, state.snapshot(), "disable"));
        } else state.disable();
        selected = pending = null;
        context = null;
        dragging = dirty = false;
        clearVertexGesture();
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        polygonMode = false; polygonDraft.clear(); polygonCursor = null; polygonBefore = null;
        gestureBefore = dragBefore = null;
        dialog = Dialog.NONE;
        message = "";
        focus(null);
        updateVisibility();
    }

    private void reset() {
        var before = state.phase() == SelectionState.Phase.PREVIEW && gestureBefore != null ? gestureBefore : state.snapshot();
        state.reset();
        if (!before.equals(state.snapshot())) history.recordApplied(selectionAction(before, state.snapshot(), "clear"));
        selected = pending = null;
        polygonDraft.clear(); polygonCursor = null; polygonBefore = null;
        polygonDraftUndo.clear(); polygonDraftRedo.clear();
        clearVertexGesture();
        dirty = dragging = false;
        gestureBefore = null;
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
        clearVertexGesture();
        dragging = false;
        syncFields();
        updateSelection();
        updateVisibility();
    }

    private void commitSelection(SelectionState.Snapshot before, SelectionState.Snapshot after, String key) {
        if (!before.equals(after)) history.recordApplied(selectionAction(before, after, key));
        refreshSelectionState();
    }

    private void undo() {
        if (dialog == Dialog.NONE && drag == Drag.VERTEX) {
            cancelVertexDrag(); message = tr("draft_cancelled").getString(); return;
        }
        if (dialog == Dialog.NONE && polygonMode && !polygonDraftUndo.isEmpty()) {
            polygonDraftRedo.push(List.copyOf(polygonDraft));
            polygonDraft.clear(); polygonDraft.addAll(polygonDraftUndo.pop());
            message = tr("history_undone").getString();
            updateSelection(); updateVisibility();
            return;
        }
        if (dialog == Dialog.NONE && polygonMode && polygonDraft.isEmpty() && !polygonDraftRedo.isEmpty()) return;
        if (dialog == Dialog.NONE && drag != Drag.NONE && dragBefore != null) {
            state.restore(dragBefore); drag = Drag.NONE; dragBefore = null; refreshSelectionState();
            message = tr("draft_cancelled").getString(); return;
        }
        if (dialog == Dialog.NONE && dirty) { dirty = false; syncFields(); focus(null); message = tr("draft_cancelled").getString(); return; }
        if (dialog == Dialog.NONE && gestureBefore != null && state.phase() == SelectionState.Phase.PREVIEW) {
            state.restore(gestureBefore); gestureBefore = null; refreshSelectionState();
            message = tr("draft_cancelled").getString(); return;
        }
        if (!history.canUndo()) return;
        try { history.undo(); message = tr("history_undone").getString(); }
        catch (Exception e) { message = tr("history_failed").getString(); }
        refreshSelectionState();
    }

    private void redo() {
        if (dialog == Dialog.NONE && polygonMode && !polygonDraftRedo.isEmpty() && drag == Drag.NONE && !dirty) {
            polygonDraftUndo.push(List.copyOf(polygonDraft));
            polygonDraft.clear(); polygonDraft.addAll(polygonDraftRedo.pop());
            message = tr("history_redone").getString();
            updateSelection(); updateVisibility();
            return;
        }
        if (!history.canRedo() || dialog == Dialog.NONE
                && (dirty || !polygonDraft.isEmpty() || drag != Drag.NONE || gestureBefore != null)) return;
        try { history.redo(); message = tr("history_redone").getString(); }
        catch (Exception e) { message = tr("history_failed").getString(); }
        refreshSelectionState();
    }

    private void toggleChunks() {
        chunks = !chunks;
        chunkMode.setMessage(tr(chunks ? "units_chunks" : "units_blocks"));
        if (chunks && state.phase() == SelectionState.Phase.PREVIEW && gestureBefore != null) {
            state.restore(gestureBefore);
            gestureBefore = null;
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
        var before = state.phase() == SelectionState.Phase.PREVIEW && gestureBefore != null ? gestureBefore : state.snapshot();
        state.set(bounds);
        gestureBefore = null;
        commitSelection(before, state.snapshot(), key);
    }

    private Drag hitHandle(MapBridge.Frame frame, double x, double y) {
        SelectionBounds b = state.bounds();
        if (b == null || state.phase() != SelectionState.Phase.SELECTED
                || state.geometry() instanceof PolygonGeometry) return Drag.NONE;
        double left = frame.screenX(b.minX()), right = frame.screenX((long) b.maxX() + 1);
        double top = frame.screenY(b.minZ()), bottom = frame.screenY((long) b.maxZ() + 1);
        double midX = (left + right) / 2, midY = (top + bottom) / 2;
        double[][] pts = {{left,top},{midX,top},{right,top},{left,midY},{right,midY},{left,bottom},{midX,bottom},{right,bottom}};
        Drag[] kinds = {Drag.NORTH_WEST,Drag.NORTH,Drag.NORTH_EAST,Drag.WEST,Drag.EAST,Drag.SOUTH_WEST,Drag.SOUTH,Drag.SOUTH_EAST};
        for (int i=0;i<pts.length;i++) if (Math.abs(x-pts[i][0]) <= 7 && Math.abs(y-pts[i][1]) <= 7) return kinds[i];
        return Drag.NONE;
    }

    private boolean insideBounds(MapBridge.Frame frame, double x, double y) {
        AreaGeometry geometry = state.geometry();
        if (geometry == null || state.phase() != SelectionState.Phase.SELECTED) return false;
        return geometry.containsPoint(frame.worldX(x), frame.worldZ(y));
    }

    private void updateDrag(MapBridge.Frame frame, double mouseX, double mouseY) {
        if (drag == Drag.VERTEX) { updateVertexDrag(frame, mouseX, mouseY); return; }
        try {
            double pointerX = frame.worldX(mouseX) - dragOffsetX;
            double pointerZ = frame.worldZ(mouseY) - dragOffsetZ;
            int x = SelectionBounds.block(frame.worldX(mouseX)), z = SelectionBounds.block(frame.worldZ(mouseY));
            int dx = x - dragStartX, dz = z - dragStartZ;
            SelectionBounds b = dragStartBounds;
            if (chunks && drag == Drag.MOVE && !(dragStartGeometry instanceof PolygonGeometry)) {
                dx = (int) (Math.round(dx / 16.0) * 16); dz = (int) (Math.round(dz / 16.0) * 16);
            }
            if (drag == Drag.MOVE && dragStartGeometry instanceof PolygonGeometry) {
                state.setGeometry(dragStartGeometry.translated(dx, dz));
                syncFields(); updateSelection();
                return;
            }
            long minX=b.minX(), maxX=b.maxX(), minZ=b.minZ(), maxZ=b.maxZ();
            switch (drag) {
                case MOVE -> { minX+=dx; maxX+=dx; minZ+=dz; maxZ+=dz; }
                case WEST, NORTH_WEST, SOUTH_WEST -> {
                    int edge = SelectionBounds.block(pointerX);
                    minX = chunks ? Math.floorDiv(edge, 16) * 16L : edge;
                }
                case EAST, NORTH_EAST, SOUTH_EAST -> {
                    int edge = blockBeforeBoundary(pointerX);
                    maxX = chunks ? Math.floorDiv(edge, 16) * 16L + 15 : edge;
                }
                default -> {}
            }
            switch (drag) {
                case NORTH, NORTH_WEST, NORTH_EAST -> {
                    int edge = SelectionBounds.block(pointerZ);
                    minZ = chunks ? Math.floorDiv(edge, 16) * 16L : edge;
                }
                case SOUTH, SOUTH_WEST, SOUTH_EAST -> {
                    int edge = blockBeforeBoundary(pointerZ);
                    maxZ = chunks ? Math.floorDiv(edge, 16) * 16L + 15 : edge;
                }
                default -> {}
            }
            if (minX > maxX) { long v=minX; minX=maxX; maxX=v; }
            if (minZ > maxZ) { long v=minZ; minZ=maxZ; maxZ=v; }
            if (minX < -30_000_000L || minZ < -30_000_000L || maxX >= 30_000_000L || maxZ >= 30_000_000L) return;
            state.set(new SelectionBounds((int) minX, (int) minZ, (int) maxX, (int) maxZ));
            syncFields(); updateSelection();
        } catch (IllegalArgumentException ignored) { }
    }

    private static int blockBeforeBoundary(double coordinate) {
        if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Invalid block coordinate");
        double nearest = Math.rint(coordinate);
        if (Math.abs(coordinate - nearest) < 1.0E-6) coordinate = nearest;
        long block = (long) Math.ceil(coordinate) - 1;
        return SelectionBounds.block(block);
    }

    public void beforeRender(int renderMouseX, int renderMouseY) {
        if (!enabled()) return;
        double mouseX = pointerX(renderMouseX), mouseY = pointerY(renderMouseY);
        MapBridge.Frame frame = checkedFrame();
        if (frame != null && enabled() && dragging && pending == null && dialog == Dialog.NONE)
            bridge.exactselection$pan(mouseX - lastMouseX, mouseY - lastMouseY);
        if (frame != null && drag != Drag.NONE && dialog == Dialog.NONE) updateDrag(frame, mouseX, mouseY);
        if (frame != null && polygonMode && drag != Drag.VERTEX && !overPanel(mouseX, mouseY)) {
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
                        int ax = ChunkSelection.anchor(chunkOriginX, wx), az = ChunkSelection.anchor(chunkOriginZ, wz);
                        int ex = ChunkSelection.endpoint(wx, chunkOriginX), ez = ChunkSelection.endpoint(wz, chunkOriginZ);
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
                    boolean closing = i == 0 && polygonDraft.size() >= 3 && drag != Drag.VERTEX
                            && Math.hypot(px - mouseX, py - mouseY) <= 8;
                    int color = drag == Drag.VERTEX && vertexIndex == i
                            ? vertexDragInvalid ? 0xFFFF5573 : -1 : i == 0 ? 0xFF80FF95 : ACCENT;
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
            if (area.vertices().size() > 0) {
                GeometryScreenRenderer.draw(g, frame, geometry, screen.width, screen.height,
                        ((area.opacity() * 255 / 100) << 24) | (area.color() & 0xFFFFFF),
                        0xFF000000 | (area.color() & 0xFFFFFF), 1.5);
                ScreenPoint anchor = polygonLabelAnchor(frame, (PolygonGeometry) geometry, geometryBounds);
                if (anchor != null) mapLabels.add(new MapLabel(Component.literal(area.name()),
                        clamp(anchor.x(), 0, screen.width - 1), clamp(anchor.y(), 0, screen.height - 1),
                        0xFF000000 | (area.color() & 0xFFFFFF)));
                continue;
            }
            SelectionBounds b = area.bounds();
            double left = frame.screenX(b.minX()), top = frame.screenY(b.minZ());
            double right = frame.screenX((long) b.maxX() + 1), bottom = frame.screenY((long) b.maxZ() + 1);
            if (left >= screen.width || right <= 0 || top >= screen.height || bottom <= 0) continue;
            int x1 = clamp(left, 0, screen.width), y1 = clamp(top, 0, screen.height);
            int x2 = clamp(right, 0, screen.width), y2 = clamp(bottom, 0, screen.height);
            GeometryScreenRenderer.draw(g, frame, geometry, screen.width, screen.height,
                    ((area.opacity() * 255 / 100) << 24) | (area.color() & 0xFFFFFF),
                    0xFF000000 | (area.color() & 0xFFFFFF), 1.0);
            if ((x2 - x1) > 50 && (y2 - y1) > 14)
                mapLabels.add(new MapLabel(Component.literal(area.name()), (x1 + x2) / 2, y1 + 3,
                        0xFF000000 | (area.color() & 0xFFFFFF)));
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
                g.renderOutline(x - 3, y - 3, 7, 7, drag == Drag.VERTEX && vertexIndex == i
                        ? vertexDragInvalid ? 0xFFFF5573 : -1 : ACCENT);
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
                double px = frame.screenX(point.x() + 0.5), py = frame.screenY(point.z() + 0.5);
                if (px < -6 || px > screen.width + 6 || py < -6 || py > screen.height + 6) continue;
                int x = (int) Math.floor(px), y = (int) Math.floor(py);
                g.renderOutline(x - 5, y - 5, 11, 11, 0xFFFFCF62);
            }
            g.disableScissor();
        }
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
        g.drawString(mc.font, mc.font.plainSubstrByWidth(tr(polygonMode ? compact ? "polygon_controls_short" : "polygon_controls" :
                state.geometry() instanceof PolygonGeometry ? "polygon_edit_controls" :
                state.phase() == SelectionState.Phase.PREVIEW ? "second_corner" :
                state.phase() == SelectionState.Phase.SELECTED ? "new_selection" : "first_corner").getString(), panelWidth - 16),
                PANEL_X + 8, PANEL_Y + 20, 0xFFD0DBE2);
        g.enableScissor(PANEL_X + 1, PANEL_Y + 32, PANEL_X + panelWidth - 1,
                PANEL_Y + panelHeight - (maxPanelScroll() > 0 ? 16 : 3));
        SelectionBounds b = state.bounds();
        if (compact) {
            line(g, Component.literal(b == null ? "A: —" : "A: X " + b.x1() + "  Z " + b.z1()), 34, -1);
            line(g, Component.literal(b == null ? "B: —" : "B: X " + b.x2() + "  Z " + b.z2()), 47, -1);
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
                : tr("dimensions", b.width(), b.length()), compact ? 60 : 106, -1);
        line(g, b == null ? tr("area_empty") : tr("area", formatMetric(state.geometry().area())), compact ? 73 : 119, ACCENT);
        if (!compact) {
            int n = selected == null ? 0 : selected.points().size();
            Component count = waypoints == null ? tr("minimap_missing") : selected == null
                    ? tr("waypoints_unavailable") : tr("count", n, setNameDisplay(selected.setName()));
            line(g, count, 267, 0xFFFFCF62);
            line(g, tr(loadFailed ? "area_load_failed" : "scope"), 280, 0xFF9BAAB7);
        }
        if (!hasExportNotice())
            line(g, message.isEmpty() ? tr("controls") : Component.literal(message), compact ? 153 : 295,
                    message.isEmpty() ? 0xFF9BAAB7 : 0xFFFFCF62);
        g.disableScissor();
        if (maxPanelScroll() > 0)
            g.drawString(mc.font, tr("panel_scroll"), PANEL_X + 8, PANEL_Y + panelHeight - 12, 0xFF9BAAB7);
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
        for (AbstractWidget widget : widgets) widget.visible = false;
        boolean main = enabled() && dialog == Dialog.NONE;
        show(main, panelModeButton, copyCoordinates, copyArea, copyLength, copyWidth,
                undo, redo, polygonModeButton, areasButton, groupsButton, clear);
        show(main && !compact, fields[0], fields[1], fields[2], fields[3], apply, chunkMode,
                sizeButton, moveButton, centerButton, delete, selectionExportButton);
        if (main && hasExportNotice()) {
            place(openExportButton, PANEL_X + 8, PANEL_Y + (compact ? 151 : 290 - panelScroll),
                    panelWidth - 16, 16);
            show(true, openExportButton);
        }
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
        for (EditBox field : fields) field.active = !polygonSelection && !contourDraft && drag == Drag.NONE;
        boolean draftHistory = !polygonDraftUndo.isEmpty() || !polygonDraftRedo.isEmpty();
        undo.active = (draftHistory ? !polygonDraftUndo.isEmpty() : history.canUndo())
                || dirty || drag != Drag.NONE || gestureBefore != null;
        redo.active = (!polygonDraftRedo.isEmpty() || !contourDraft && history.canRedo())
                && !dirty && drag == Drag.NONE && gestureBefore == null;
        // Name the stored action only when the button would apply it rather than discard a draft or gesture.
        boolean historyStep = !draftHistory && !dirty && drag == Drag.NONE && gestureBefore == null;
        undo.setTooltip(historyTooltip("undo_tooltip", historyStep ? history.undoLabel() : ""));
        redo.setTooltip(historyTooltip("redo_tooltip", historyStep && redo.active ? history.redoLabel() : ""));
        managementUndo.setTooltip(historyTooltip("undo_tooltip", history.undoLabel()));
        managementRedo.setTooltip(historyTooltip("redo_tooltip", history.redoLabel()));
        chunkMode.setMessage(tr(chunks ? "units_chunks" : "units_blocks"));
        selectionExportButton.active = canExportSelection();
        toggle.visible = dialog == Dialog.NONE;
        toggle.setMessage(Component.literal(enabled() ? "■" : "□"));
        polygonModeButton.setMessage(tr(polygonMode ? "polygon_mode_on" : "polygon_mode"));
        polygonModeButton.active = enabled() && !dirty && drag == Drag.NONE && dialog == Dialog.NONE;
        polygonModeButton.setTooltip(Tooltip.create(tr(polygonMode ? "polygon_controls" : "polygon_mode_tooltip")));
        sizeButton.active = !(state.geometry() instanceof PolygonGeometry) && !dirty && !contourDraft && drag == Drag.NONE;
        chunkMode.active = !(state.geometry() instanceof PolygonGeometry) && !contourDraft && drag == Drag.NONE;
        moveButton.active = centerButton.active = !dirty && !contourDraft && drag == Drag.NONE;
        areasButton.active = groupsButton.active = !dirty && !contourDraft && drag == Drag.NONE;
        if (dialog == Dialog.SIZE) show(true, dialogFields[0], dialogFields[1], anchorButton, modalApply, modalCancel);
        if (dialog == Dialog.MOVE) show(true, dialogFields[2], dialogFields[3], moveUnitsButton, modalApply, modalCancel);
        if (dialog == Dialog.CENTER) show(true, dialogFields[6], centerCopy, centerCreate);
        if (dialog == Dialog.AREA_NAME) show(true, dialogFields[8], modalApply, modalCancel);
        if (dialog == Dialog.GROUP_NAME) show(true, dialogFields[5], modalApply, modalCancel);
        if (dialog == Dialog.ASSIGN_GROUP) show(true, manageGroups, managementClose);
        if (dialog == Dialog.AREAS) {
            if (areaDeleteConfirmation) show(true, confirm, cancel);
            else show(true, managementClose, manageGroups, managementUndo, managementRedo,
                    dialogFields[4], areaSave, areaRename, groupAssign, areaLoad, areaUpdate, areaExportButton,
                    areaDelete, areaMerge, areaSelectAll, areaClearSelection, areaVisibility, areaColor, areaOpacity,
                    areaMapVisibility, areaMinimapVisibility);
            if (!areaDeleteConfirmation && hasExportNotice()) show(true, openExportButton);
        }
        if (dialog == Dialog.GROUPS) {
            if (groupDeleteConfirmation) show(true, confirm, cancel);
            else show(true, managementClose, managementUndo, managementRedo, groupCreate, groupRename, groupVisibility, groupDelete);
        }
        if (dialog == Dialog.WAYPOINTS) show(true, dialogFields[7], pointVisibility, pointColor, confirm, cancel);
        apply.active = enabled() && !polygonSelection && !contourDraft && drag == Drag.NONE && (dirty || state.bounds() != null);
        clear.active = state.bounds() != null || dirty || contourDraft;
        int pointCount = selected == null ? 0 : selected.points().size();
        delete.setMessage(tr("delete_count", pointCount));
        delete.active = enabled() && !dirty && dialog == Dialog.NONE && pointCount > 0 && !contourDraft && drag == Drag.NONE
                && state.phase() == SelectionState.Phase.SELECTED;
        boolean canCopy = enabled() && !dirty && !contourDraft && drag == Drag.NONE && dialog == Dialog.NONE && state.bounds() != null;
        copyCoordinates.active = copyArea.active = copyLength.active = copyWidth.active = canCopy;
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
        areaLoad.active = singleArea;
        areaExportButton.active = singleArea && !loadFailed;
        areaDelete.active = selectedCount > 0 && !loadFailed;
        areaColor.active = areaOpacity.active = singleArea && !loadFailed;
        areaVisibility.active = selectedCount > 0 && !loadFailed;
        areaUpdate.active = singleArea && state.phase() == SelectionState.Phase.SELECTED
                && !dirty && !contourDraft && drag == Drag.NONE && !loadFailed;
        groupAssign.active = selectedCount > 0 && !loadFailed;
        areaSave.active = state.phase() == SelectionState.Phase.SELECTED && !dirty && !loadFailed;
        areaRename.active = singleArea && !dialogFields[4].getValue().trim().isEmpty() && !loadFailed;
        dialogFields[4].active = singleArea && !loadFailed;
        groupCreate.active = !loadFailed;
        groupRename.active = groupVisibility.active = groupDelete.active = group != null && !loadFailed;
        groupVisibility.setMessage(tr(group != null && group.visible() ? "group_hide" : "group_show"));
        boolean hideAreas = selectedAreas().stream().anyMatch(SavedAreaStore.Area::visible);
        areaVisibility.setMessage(tr(hideAreas ? "area_hide_selected" : "area_show_selected", selectedCount));
        areaOpacity.setMessage(area == null ? tr("area_fill") : Component.literal(area.opacity() + "%"));
        groupAssign.setMessage(tr("area_group_button", selectedGroupName()));
        areaMapVisibility.setMessage(tr(areaStore.state().showOnMap() ? "area_map_on" : "area_map_off"));
        areaMinimapVisibility.setMessage(tr(areaStore.state().showOnMinimap() ? "area_minimap_on" : "area_minimap_off"));
        areaMapVisibility.active = areaMinimapVisibility.active = !loadFailed;
        areaDelete.setMessage(tr("area_delete_count", selectedCount));
        areaMerge.active = selectedCount > 1 && !loadFailed;
        areaMerge.setMessage(tr("area_merge_count", selectedCount));
        areaSelectAll.active = !loadFailed && selectedCount < areaStore.state().areas().size();
        areaClearSelection.active = selectedCount > 0;
        areaSave.setTooltip(Tooltip.create(tr(loadFailed ? "area_load_failed" : "area_save_tooltip")));
    }

    private Tooltip historyTooltip(String key, String label) {
        return label.isEmpty() ? null : Tooltip.create(tr(key, label));
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
            case WAYPOINTS -> "dialog_waypoints"; default -> "title";
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
                if (!message.isEmpty()) g.drawString(mc.font, mc.font.plainSubstrByWidth(message, w - 32), x + 16, y + 80, 0xFFFFCF62);
            }
            case WAYPOINTS -> renderWaypointsDialog(g, x, y, w, h);
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
        g.fill(x + 10, y + 46, x + 14 + col, y + h - 100, 0xFF0C141D);
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
        g.drawString(mc.font, tr("area_name"), right, y + 34, 0xFF9BAAB7);
        if (h >= 270 && selectedAreaIds.size() == 1 && selectedArea() != null) {
            SavedAreaStore.Area area = selectedArea();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(areaVisibilityDescription(area), col), right, y + 218, 0xFF9BAAB7);
            GeometryBounds bounds = area.geometry().bounds();
            String dimensions = tr("area_geometry_metrics", bounds.width(), bounds.length(), formatMetric(area.geometry().area())).getString();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(dimensions, col), right, y + 232, 0xFF9BAAB7);
        }
        renderManagementStatus(g, x, y, w, h, "areas_selection_hint");
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
            if (!targets.isEmpty() && targets.stream().allMatch(a -> Objects.equals(a.groupId(), id)))
                g.fill(x + 10, rowY, x + w - 10, rowY + 20, 0x8842636D);
            String name = group == null ? tr("group_none").getString() : group.name();
            if (group != null && !group.visible()) name += " · " + tr("group_state_hidden").getString();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(name, w - 32), x + 16, rowY + 5, -1);
        }
        if (!message.isEmpty()) renderManagementStatus(g, x, y, w, h, "group_picker_tooltip");
    }

    private void renderManagementStatus(GuiGraphics g, int x, int y, int w, int h, String hint) {
        String text = message.isEmpty() ? tr(loadFailed ? "area_load_failed" : hint).getString() : message;
        int textWidth = dialog == Dialog.AREAS && hasExportNotice() ? (w - 36) / 2 : w - 24;
        g.drawString(mc.font, mc.font.plainSubstrByWidth(text, textWidth), x + 12, y + h - 12,
                message.isEmpty() && !loadFailed ? 0xFF9BAAB7 : 0xFFFFCF62);
    }

    private void renderWaypointsDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (pending == null) return;
        List<WaypointAccess.Point> points = filteredPoints();
        int rows = Math.max(1, (h - 116) / 14);
        modalScroll = Math.min(modalScroll, Math.max(0, points.size() - rows));
        int chosen = filteredChosenPoints().size();
        g.drawString(mc.font, tr("waypoint_filter_count", chosen, points.size()), x + 12, y + 29, 0xFF9BAAB7);
        for (int i = 0; i < rows && i + modalScroll < points.size(); i++) {
            WaypointAccess.Point p = points.get(i + modalScroll); int rowY = y + 83 + i * 14;
            boolean checked = chosenPoints.contains(p.identity());
            if (checked) g.fill(x + 9, rowY - 2, x + w - 9, rowY + 12, 0x3342636D);
            String text = (checked ? "[x] " : "[ ] ") + p.name() + "  " + p.x() + ", " + p.y() + ", " + p.z();
            g.drawString(mc.font, mc.font.plainSubstrByWidth(text, w - 26), x + 13, rowY, p.disabled() ? 0xFF9BAAB7 : -1);
        }
        String hint = message.isEmpty() ? tr("waypoint_modal_hint").getString() : message;
        g.drawString(mc.font, mc.font.plainSubstrByWidth(hint, w - 24), x + 12, y + h - 42, message.isEmpty() ? 0xFF9BAAB7 : 0xFFFFCF62);
        confirm.setMessage(tr("delete_count", chosen));
        confirm.active = chosen > 0;
    }

    private int areaScroll, groupScroll;

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
                if (dialog == Dialog.AREAS && !areaDeleteConfirmation) {
                    int rows = visibleAreaDialogRows(h);
                    if (event.x() >= x + 10 && event.x() < x + 14 + col
                            && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
                        int index = areaScroll + (int) ((event.y() - y - 48) / 20);
                        List<SavedAreaStore.Area> areas = areaStore.state().areas();
                        if (index < areas.size()) {
                            SavedAreaStore.Area area = areas.get(index);
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
                int rows = Math.max(1, (h - 116) / 14);
                List<WaypointAccess.Point> points = filteredPoints();
                // Row i is drawn over [y + 81 + 14i, y + 95 + 14i); a taller hit area would toggle a hidden row.
                if (event.x() >= x + 8 && event.x() < x + w - 8 && event.y() >= y + 81 && event.y() < y + 81 + rows * 14) {
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
            if (dirty || drag != Drag.NONE) return true;
            if ((event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0
                    && (polygonMode || state.geometry() instanceof PolygonGeometry)) {
                beginVertexDrag(frame, event.x(), event.y());
                return true;
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
                Drag handle = hitHandle(frame, event.x(), event.y());
                int worldX = SelectionBounds.block(frame.worldX(event.x()));
                int worldZ = SelectionBounds.block(frame.worldZ(event.y()));
                boolean moveWhole = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 && insideBounds(frame, event.x(), event.y());
                if (handle != Drag.NONE || moveWhole) {
                    dragBefore = state.snapshot(); dragStartBounds = state.bounds(); dragStartGeometry = state.geometry();
                    dragStartX = worldX; dragStartZ = worldZ;
                    drag = moveWhole ? Drag.MOVE : handle;
                    dragOffsetX = 0;
                    dragOffsetZ = 0;
                    if (drag != Drag.MOVE) {
                        if (drag == Drag.WEST || drag == Drag.NORTH_WEST || drag == Drag.SOUTH_WEST)
                            dragOffsetX = frame.worldX(event.x()) - state.bounds().minX();
                        else if (drag == Drag.EAST || drag == Drag.NORTH_EAST || drag == Drag.SOUTH_EAST)
                            dragOffsetX = frame.worldX(event.x()) - ((long) state.bounds().maxX() + 1);
                        if (drag == Drag.NORTH || drag == Drag.NORTH_WEST || drag == Drag.NORTH_EAST)
                            dragOffsetZ = frame.worldZ(event.y()) - state.bounds().minZ();
                        else if (drag == Drag.SOUTH || drag == Drag.SOUTH_WEST || drag == Drag.SOUTH_EAST)
                            dragOffsetZ = frame.worldZ(event.y()) - ((long) state.bounds().maxZ() + 1);
                    }
                    return true;
                }
                if (state.geometry() instanceof PolygonGeometry && !polygonMode) return true;
                SelectionState.Snapshot before = state.phase() == SelectionState.Phase.PREVIEW && gestureBefore != null
                        ? gestureBefore : state.snapshot();
                if (chunks) {
                    if (state.phase() == SelectionState.Phase.PREVIEW) {
                        int anchorX = ChunkSelection.anchor(chunkOriginX, worldX);
                        int anchorZ = ChunkSelection.anchor(chunkOriginZ, worldZ);
                        worldX = ChunkSelection.endpoint(worldX, chunkOriginX);
                        worldZ = ChunkSelection.endpoint(worldZ, chunkOriginZ);
                        state.hover(anchorX, anchorZ, worldX, worldZ);
                    } else {
                        chunkOriginX = worldX;
                        chunkOriginZ = worldZ;
                        worldX = ChunkSelection.anchor(chunkOriginX, chunkOriginX);
                        worldZ = ChunkSelection.anchor(chunkOriginZ, chunkOriginZ);
                        gestureBefore = before;
                    }
                } else if (state.phase() != SelectionState.Phase.PREVIEW) gestureBefore = before;
                boolean finishing = state.phase() == SelectionState.Phase.PREVIEW;
                state.click(worldX, worldZ);
                if (finishing) {
                    commitSelection(before, state.snapshot(), "select");
                    gestureBefore = null;
                }
                message = "";
                syncFields();
                updateSelection();
            } catch (IllegalArgumentException e) { message = tr("invalid_coordinates").getString(); }
        }
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) dragging = false;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && drag == Drag.VERTEX) {
            MapBridge.Frame frame = checkedFrame();
            if (frame != null && drag == Drag.VERTEX) {
                updateVertexDrag(frame, event.x(), event.y());
                finishVertexDrag();
            }
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && drag != Drag.NONE) {
            MapBridge.Frame frame = checkedFrame();
            if (frame != null && drag != Drag.NONE) updateDrag(frame, event.x(), event.y());
            commitSelection(dragBefore, state.snapshot(), "drag");
            drag = Drag.NONE; dragBefore = null; dragStartBounds = null; dragStartGeometry = null;
            dragOffsetX = 0; dragOffsetZ = 0;
            return true;
        }
        // Native release clears held toolbar buttons, even when released outside them.
        // Its map press flags stay false, so it cannot create a native map click.
        return false;
    }

    public boolean mouseScrolled(double x, double y, double vertical) {
        if (dialog == Dialog.WAYPOINTS && pending != null) {
            modalScroll = Math.max(0, Math.min(Math.max(0, filteredPoints().size() - Math.max(1, (dialogRect()[3] - 116) / 14)),
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
            if (drag == Drag.VERTEX) { cancelVertexDrag(); message = tr("draft_cancelled").getString(); }
            else if (drag != Drag.NONE && dragBefore != null) { state.restore(dragBefore); drag = Drag.NONE; dragBefore = null; refreshSelectionState(); }
            else if (dialog != Dialog.NONE) cancelDialog();
            else if (polygonMode) {
                polygonDraft.clear(); polygonCursor = null; polygonBefore = null; polygonMode = false;
                polygonDraftUndo.clear(); polygonDraftRedo.clear();
                message = tr("polygon_cancelled").getString(); updateSelection(); updateVisibility();
            }
            else if (dirty) { dirty = false; syncFields(); focus(null); }
            else if (gestureBefore != null && state.phase() == SelectionState.Phase.PREVIEW) {
                state.restore(gestureBefore); gestureBefore = null; refreshSelectionState();
            }
            else if (state.bounds() != null) reset();
            else disable();
            return true;
        }
        boolean editing = screen.getFocused() instanceof EditBox field && widgets.contains(field);
        if (polygonMode && dialog == Dialog.NONE && !editing && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            removeLastPolygonPoint();
            return true;
        }
        if (polygonMode && drag == Drag.NONE && dialog == Dialog.NONE && !editing && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
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
                else if (dialog == Dialog.AREAS && field == dialogFields[4] && areaRename.active) renameArea();
                else if (dialog == Dialog.CENTER) createCenterWaypoint();
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
        if (state.geometry() instanceof PolygonGeometry || !polygonDraft.isEmpty() || drag != Drag.NONE) return;
        try {
            SelectionState.Snapshot before = state.phase() == SelectionState.Phase.PREVIEW && gestureBefore != null ? gestureBefore : state.snapshot();
            int[] c = new int[4];
            for (int i = 0; i < 4; i++) c[i] = SelectionBounds.block(Integer.parseInt(fields[i].getValue().trim()));
            state.set(new SelectionBounds(c[0], c[1], c[2], c[3]));
            gestureBefore = null;
            commitSelection(before, state.snapshot(), "coordinates");
            focus(null);
            message = "";
        } catch (IllegalArgumentException e) { message = tr("invalid_coordinates").getString(); }
    }

    private void askDelete() {
        if (dirty || state.phase() != SelectionState.Phase.SELECTED || checkedFrame() == null || !enabled()) return;
        updateSelection();
        if (selected == null || selected.points().isEmpty()) return;
        pending = waypoints.captureSet(selected);
        if (pending == null) {
            message = tr("changed").getString();
            return;
        }
        modalScroll = 0;
        chosenPoints.clear();
        for (WaypointAccess.Point point : pending.points()) if (point.editable()) chosenPoints.add(point.identity());
        dragging = false;
        message = "";
        dialog = Dialog.WAYPOINTS;
        updateVisibility();
        focus(cancel);
    }

    private void confirmDelete() {
        if (dialog == Dialog.GROUPS && groupDeleteConfirmation) {
            confirmGroupDelete();
            return;
        }
        if (dialog == Dialog.AREAS && areaDeleteConfirmation) {
            confirmAreaDelete();
            return;
        }
        if (checkedFrame() == null || !enabled() || pending == null || dialog != Dialog.WAYPOINTS) return;
        WaypointAccess.Snapshot current = waypoints.captureSet(waypoints.select(context, state.geometry()));
        if (!pending.equals(current)) {
            if (current == null) {
                pending = null; dialog = Dialog.NONE;
                message = tr("changed").getString();
                focus(null);
                updateSelection();
                return;
            }
            pending = current;
            chosenPoints.clear(); for (WaypointAccess.Point point : pending.points()) if (point.editable()) chosenPoints.add(point.identity());
            modalScroll = 0;
            message = tr("changed").getString();
            focus(cancel);
            return;
        }
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
        } catch (Exception e) {
            LoggerFactory.getLogger("xaero-exact-selection").error("Could not save waypoint deletion; restored in-memory set", e);
            message = tr("save_failed").getString();
        }
        pending = null; dialog = Dialog.NONE;
        focus(null);
        updateSelection();
    }

}
