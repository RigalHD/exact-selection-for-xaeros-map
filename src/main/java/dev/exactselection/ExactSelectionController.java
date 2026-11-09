package dev.exactselection;

import static dev.exactselection.ModLog.LOG;

import dev.exactselection.MapOverlays.MapLabel;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
import net.minecraft.util.Util;
import xaero.map.gui.GuiMap;

/**
 * The map tool on one World Map screen: owns the selection, its history and the state the {@link UiPart jobs} share,
 * receives Xaero's input and render calls and passes each to the job it belongs to.
 */
public final class ExactSelectionController {
    final RulerTool rulerTool = new RulerTool(this);
    final PolygonTool polygonTool = new PolygonTool(this);
    final MapOverlays mapOverlays = new MapOverlays(this);
    final ColorWindows colorWindows = new ColorWindows(this);
    final WaypointsWindow waypointsWindow = new WaypointsWindow(this);
    final GroupsWindow groupsWindow = new GroupsWindow(this);
    final AreasWindow areasWindow = new AreasWindow(this);
    final TransferWindow transferWindow = new TransferWindow(this);
    final MapExport mapExport = new MapExport(this);
    final SelectionPanel selectionPanel = new SelectionPanel(this);
    final MapPointer mapPointer = new MapPointer(this);
    final SelectionDialogs selectionDialogs = new SelectionDialogs(this);
    final RightClickMenu rightClickMenu = new RightClickMenu(this);
    final ZoneSnap zoneSnap = new ZoneSnap(this);
    final AreaEdits areaEdits = new AreaEdits(this);
    final NewAreaWindow newAreaWindow = new NewAreaWindow(this);
    final Windows windows = new Windows(this);
    final InputRouter input = new InputRouter(this);

    /** The text fields of the windows; a field keeps what was typed when Xaero rebuilds the screen. */
    enum DialogField {
        WIDTH(10),
        LENGTH(10),
        OFFSET_X(10),
        OFFSET_Z(10),
        /** The name of the area checked in the Areas window. */
        AREA_NAME(128),
        GROUP_NAME(128),
        CENTER_NAME(128),
        POINT_SEARCH(128),
        /** The name of the area or route being saved. */
        NEW_AREA(128),
        /** The new name of the checked waypoints. */
        RENAME(128),
        /** A color as #RRGGBB, in the color window and in the window of a new area. */
        HEX(7);

        final int maxLength;

        DialogField(int maxLength) {
            this.maxLength = maxLength;
        }
    }

    enum Dialog {
        NONE,
        SIZE,
        MOVE,
        CENTER,
        AREAS,
        GROUPS,
        ASSIGN_GROUP,
        AREA_NAME,
        GROUP_NAME,
        WAYPOINTS,
        COLOR,
        IMPORT,
        EXPORT,
        PICKER
    }
    /**
     * Xaero builds a new screen for every map opening; the tool outlives them, so its selection and history survive.
     */
    private static ExactSelectionController current;

    GuiMap screen;
    MapBridge bridge;
    /** A new map screen has no frame until it first renders, which must not count as a lost map. */
    private boolean awaitingFrame;
    /** The stored areas must be read again on the next frame. */
    private boolean areasStale;

    final Minecraft mc;
    final SelectionState state = new SelectionState();
    final SelectionHistory history = new SelectionHistory();
    final WaypointAccess waypoints;
    final SavedAreaStore areaStore;
    final List<AbstractWidget> widgets = new ArrayList<>();
    /** One field for each {@link DialogField}, in its order. */
    final EditBox[] dialogFields = new EditBox[DialogField.values().length];

    Button confirm, cancel;
    Button modalApply, modalCancel;
    AutosaveSettings autosave;
    final ExportWindow exportWindow;
    Button managementClose, managementUndo, managementRedo;
    /** A press whose pointer moves less than this many GUI pixels is a click, not a move or resize. */
    static final double CLICK_SLOP = 3;

    MapBridge.Context context;
    WaypointAccess.Snapshot selected;
    boolean syncing, dirty, dragging, chunks, loadFailed;
    double lastMouseX, lastMouseY;
    Dialog dialog = Dialog.NONE;
    /**
     * The map gesture in progress, or null. It ends through its finish or cancel; disable, reset and changes that
     * replace the selection drop it outright, which turns a later finish or cancel of it into a no-op.
     */
    MapGesture gesture;

    final MapGesture.Host gestureHost = new MapGesture.Host() {
        @Override
        public MapGesture gesture() {
            return gesture;
        }

        @Override
        public void clearGesture() {
            gesture = null;
        }

        @Override
        public SelectionState state() {
            return state;
        }

        @Override
        public List<GridPoint> draft() {
            return polygonTool.draft;
        }

        @Override
        public boolean chunks() {
            return chunks;
        }

        @Override
        public void commit(SelectionState.Snapshot before, String key) {
            commitSelection(before, state.snapshot(), key);
        }

        @Override
        public void recordDraftChange(List<GridPoint> before) {
            polygonTool.recordDraftChange(before);
        }

        @Override
        public void selectionMoved() {
            syncFields();
            updateSelection();
        }

        @Override
        public void refresh() {
            refreshSelectionState();
        }

        @Override
        public void message(String key) {
            say(key);
        }

        @Override
        public GridPoint snap(MapBridge.Frame frame, double x, double y) {
            return zoneSnap.point(frame, x, y);
        }

        @Override
        public boolean shiftDown() {
            return shiftDown;
        }
    };
    private MapBridge.Context historyContext;
    String message = "";
    boolean shiftDown, altDown;

    public ExactSelectionController(GuiMap screen, MapBridge bridge) {
        this(
                screen,
                bridge,
                Minecraft.getInstance(),
                new SavedAreaStore(FabricLoader.getInstance().getConfigDir()),
                FabricLoader.getInstance().isModLoaded("xaerominimap")
                        ? SafeWaypointAccess.create(XaeroWaypointBridge::new)
                        : null);
    }

    ExactSelectionController(
            GuiMap screen, MapBridge bridge, Minecraft mc, SavedAreaStore areaStore, WaypointAccess waypoints) {
        this.screen = screen;
        this.bridge = bridge;
        this.mc = mc;
        this.areaStore = areaStore;
        this.waypoints = waypoints;
        this.autosave = AutosaveSettings.load(areaStore.root());
        this.exportWindow =
                new ExportWindow(mc, ExportPreferences.load(areaStore.root()), mapExport::defaultExportFolder);
    }

    public static ExactSelectionController forScreen(GuiMap screen, MapBridge bridge) {
        if (current == null) current = new ExactSelectionController(screen, bridge);
        else current.useScreen(screen, bridge);
        return current;
    }

    private void useScreen(GuiMap screen, MapBridge bridge) {
        this.screen = screen;
        this.bridge = bridge;
        awaitingFrame = true;
        // Mod Menu's settings and the area file may have changed while the map was closed.
        autosave = AutosaveSettings.load(areaStore.root());
        areaStore.reload();
        areasStale = true;
    }

    /** The map screen is going away: what is held or open ends, the selection and its history stay. */
    public void suspend() {
        if (holding()) cancelGesture();
        dragging = false;
        rightClickMenu.quickMenu = false;
        rulerTool.pressed = false;
        if (dialog == Dialog.AREAS) areaEdits.flushDraft();
        waypointsWindow.pending = null;
        dialog = Dialog.NONE;
    }

    Component tr(String key, Object... args) {
        return Component.translatable("exactselection." + key, args);
    }

    /** The message the window or toast shows next. */
    void say(String key, Object... args) {
        message = tr(key, args).getString();
    }

    List<Area> areas() {
        return areaStore.state().areas();
    }

    /** Why the saved areas are unavailable: a damaged file, or one a newer version of the mod wrote. */
    String loadFailedKey() {
        return areaStore.newerFile() ? "area_file_newer" : "area_load_failed";
    }

    /** After the stored areas changed: a selection whose saved area no longer exists stops editing it. */
    void detachMissingSource() {
        if (state.source() != null && areasWindow.area(state.source()) == null) state.detach();
    }

    List<Group> groups() {
        return areaStore.state().groups();
    }

    public void init() {
        // Xaero rebuilds its widgets on resize and when changing dimensions/settings.
        String[] draft = selectionPanel.fields[0] == null
                ? null
                : Arrays.stream(selectionPanel.fields).map(EditBox::getValue).toArray(String[]::new);
        String[] dialogDraft = dialogFields[0] == null
                ? null
                : Arrays.stream(dialogFields).map(EditBox::getValue).toArray(String[]::new);
        widgets.clear();
        tooltips.clear();
        selectionPanel.createPanelWidgets();
        windows.createWindowWidgets();
        areasWindow.createAreaWidgets();
        newAreaWindow.createNewAreaWidgets();
        groupsWindow.createGroupWidgets();
        transferWindow.createTransferWidgets();
        waypointsWindow.createWaypointWidgets();
        colorWindows.createColorWidgets();
        rightClickMenu.createMenuWidgets();
        mapExport.createExportWidgets();
        selectionDialogs.createDialogWidgets();
        selectionPanel.layoutMainPanel();
        for (AbstractWidget widget : widgets) screen.addWidget(widget);
        if (dirty && draft != null) {
            syncing = true;
            for (int i = 0; i < 4; i++) selectionPanel.fields[i].setValue(draft[i]);
            syncing = false;
        } else syncFields();
        if (dialogDraft != null) {
            for (DialogField field : DialogField.values()) setDialogValue(field, dialogDraft[field.ordinal()]);
        }
        updateVisibility();
    }

    /** The position and size of every button come from its window's layout. */
    private Button addButton(Component label, String tooltipKey, Runnable action) {
        Button result = Button.builder(label, button -> action.run())
                .bounds(0, 0, 100, 18)
                .build();
        if (tooltipKey != null) result.setTooltip(Tooltip.create(tr(tooltipKey)));
        widgets.add(result);
        return result;
    }

    /** The last tooltip text of each widget; cleared with the widgets. */
    private final java.util.Map<AbstractWidget, String> tooltips = new java.util.HashMap<>();

    /** Sets a tooltip that follows the state; called every frame, it makes a new one only when the text changed. */
    void tip(AbstractWidget widget, Component text) {
        String shown = text.getString();
        if (shown.equals(tooltips.put(widget, shown))) return;
        widget.setTooltip(Tooltip.create(text));
    }

    Button button(String key, Runnable action) {
        return addButton(tr(key), null, action);
    }

    Button button(String key, String tooltipKey, Runnable action) {
        return addButton(tr(key), tooltipKey, action);
    }

    Button iconButton(String tooltipKey, Runnable action) {
        return addButton(Component.empty(), tooltipKey, action);
    }

    Button arrowButton(String arrow, Runnable action) {
        return addButton(Component.literal(arrow), null, action);
    }

    EditBox field(DialogField field) {
        return dialogFields[field.ordinal()];
    }

    /** Fills a field from the program, which is not the user typing: its responder stays quiet. */
    void setDialogValue(DialogField field, String value) {
        syncing = true;
        field(field).setValue(value);
        syncing = false;
    }

    static void place(AbstractWidget widget, int x, int y, int width, int height) {
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(Math.max(1, width));
        widget.setHeight(height);
    }

    public boolean enabled() {
        return state.enabled();
    }

    public boolean confirmationOpen() {
        return dialog != Dialog.NONE;
    }

    public boolean coordinateFieldFocused() {
        return screen.getFocused() instanceof EditBox field && widgets.contains(field);
    }

    void toggle() {
        if (enabled()) {
            disable();
            return;
        }
        MapBridge.Frame frame = bridge.exactselection$frame();
        if (frame == null) return;
        bridge.exactselection$clearNativeInput();
        screen.clearFocus();
        state.enable();
        context = frame.context();
        message = "";
        syncFields();
    }

    /** Cancels the current gesture and resyncs the panel; false when there was none. */
    boolean cancelGesture() {
        MapGesture current = gesture;
        if (current == null || !current.cancel(gestureHost)) return false;
        refreshSelectionState();
        return true;
    }

    boolean holding() {
        return gesture instanceof MapGesture.Held;
    }

    /** A change made while a two-click selection is open is recorded from before its first click. */
    SelectionState.Snapshot historyBase() {
        return state.phase() == SelectionState.Phase.PREVIEW && gesture instanceof MapGesture.Corners corners
                ? corners.before
                : state.snapshot();
    }

    // A preview restored from history has no gesture; its first corner lies in the same chunk as the origin.
    int chunkOriginX() {
        return gesture instanceof MapGesture.Corners corners
                ? corners.originX
                : state.bounds().x1();
    }

    int chunkOriginZ() {
        return gesture instanceof MapGesture.Corners corners
                ? corners.originZ
                : state.bounds().z1();
    }

    public void disable() {
        if (enabled()) bridge.exactselection$clearNativeInput();
        if (state.bounds() != null) {
            var before = historyBase();
            state.disable();
            // Undo brings the selection back into the reopened tool; redo only clears it again, leaving the tool on.
            history.recordApplied(
                    selectionAction(before, new SelectionState.Snapshot(SelectionState.Phase.READY, null), "clear"));
        } else state.disable();
        selected = waypointsWindow.pending = null;
        context = null;
        dragging = dirty = false;
        gesture = null;
        polygonTool.discardDraft();
        polygonTool.mode = false;
        rulerTool.setRulerMode(false);
        dialog = Dialog.NONE;
        message = "";
        focus(null);
    }

    void reset() {
        var before = historyBase();
        state.reset();
        if (!before.equals(state.snapshot())) history.recordApplied(selectionAction(before, state.snapshot(), "clear"));
        selected = waypointsWindow.pending = null;
        polygonTool.discardDraft();
        gesture = null;
        dirty = dragging = false;
        message = "";
        focus(null);
        syncFields();
    }

    MapBridge.Frame checkedFrame() {
        MapBridge.Frame frame = bridge.exactselection$frame();
        if (frame != null) awaitingFrame = false;
        if (enabled() && !awaitingFrame && (frame == null || !Objects.equals(context, frame.context()))) disable();
        if (frame != null && !Objects.equals(historyContext, frame.context())) {
            history.clear();
            historyContext = frame.context();
            areasWindow.areaId = groupsWindow.groupId = null;
            areasWindow.areaScroll = groupsWindow.groupScroll = 0;
            areasWindow.selectedAreaIds.clear();
            areasStale = true;
        }
        if (frame != null && areasStale) {
            areasStale = false;
            try {
                areaStore.useContext(frame.context());
                loadFailed = areaStore.loadFailed();
            } catch (Exception e) {
                loadFailed = true;
                say(loadFailedKey());
                LOG.error("Could not load saved areas", e);
            }
        }
        return frame;
    }

    private SelectionHistory.Action selectionAction(
            SelectionState.Snapshot before, SelectionState.Snapshot after, String key) {
        return new SelectionHistory.Action() {
            @Override
            public void undo() {
                state.restore(before);
                refreshSelectionState();
            }

            @Override
            public void redo() {
                state.restore(after);
                refreshSelectionState();
            }

            @Override
            public String label() {
                return tr("history_" + key).getString();
            }
        };
    }

    private void refreshSelectionState() {
        dirty = false;
        // History may have replaced the preview a two-click selection was waiting on.
        if (holding() || state.phase() != SelectionState.Phase.PREVIEW) gesture = null;
        dragging = false;
        syncFields();
        updateSelection();
    }

    void commitSelection(SelectionState.Snapshot before, SelectionState.Snapshot after, String key) {
        if (!before.equals(after)) {
            SelectionHistory.Action action = selectionAction(before, after, key);
            Area edited = areasWindow.editedArea();
            if (autosave.covers(AutosaveSettings.Field.GEOMETRY)
                    && edited != null
                    && !loadFailed
                    && after.phase() == SelectionState.Phase.SELECTED
                    && after.geometry() != null
                    && Objects.equals(after.source(), edited.id())
                    && !edited.geometry().equals(after.geometry()))
                action = areaEdits.withGeometryAutosave(action, edited, after.geometry());
            history.recordApplied(action);
            // A new change ends redoing an undone contour, which would otherwise keep blocking undo.
            if (polygonTool.draft.isEmpty()) polygonTool.draftRedo.clear();
        }
        refreshSelectionState();
    }

    void undo() {
        if (dialog == Dialog.NONE && gesture instanceof MapGesture.VertexDrag) {
            cancelGesture();
            say("draft_cancelled");
            return;
        }
        if (dialog == Dialog.NONE && polygonTool.mode && polygonTool.undoDraft()) return;
        if (dialog == Dialog.NONE && gesture instanceof MapGesture.Drag) {
            cancelGesture();
            say("draft_cancelled");
            return;
        }
        if (dialog == Dialog.NONE && dirty) {
            dirty = false;
            syncFields();
            focus(null);
            say("draft_cancelled");
            return;
        }
        if (dialog == Dialog.NONE
                && gesture instanceof MapGesture.Corners
                && state.phase() == SelectionState.Phase.PREVIEW) {
            cancelGesture();
            say("draft_cancelled");
            return;
        }
        if (!history.canUndo()) return;
        try {
            history.undo();
            say("history_undone");
        } catch (Exception e) {
            say("history_failed");
            LOG.error("Could not undo a step", e);
        }
        refreshSelectionState();
    }

    void redo() {
        if (dialog == Dialog.NONE && polygonTool.mode && !holding() && !dirty && polygonTool.redoDraft()) return;
        if (!history.canRedo() || dialog == Dialog.NONE && (dirty || !polygonTool.draft.isEmpty() || gesture != null))
            return;
        try {
            history.redo();
            say("history_redone");
        } catch (Exception e) {
            say("history_failed");
            LOG.error("Could not redo a step", e);
        }
        refreshSelectionState();
    }

    void toggleChunks() {
        chunks = !chunks;
        selectionPanel.chunkMode.setMessage(tr(chunks ? "units_chunks" : "units_blocks"));
        if (chunks && state.phase() == SelectionState.Phase.PREVIEW && gesture instanceof MapGesture.Corners corners) {
            corners.cancel(gestureHost);
            syncFields();
        }
        // A saved area being edited keeps its shape until the user changes it; only new edits snap.
        if (state.phase() == SelectionState.Phase.SELECTED
                && state.bounds() != null
                && chunks
                && areasWindow.editedArea() == null) {
            try {
                SelectionBounds next = state.bounds().expandedToChunks();
                if (!next.equals(state.bounds())) setSelection(next, "snap_chunks");
            } catch (IllegalArgumentException e) {
                chunks = false;
                selectionPanel.chunkMode.setMessage(tr("units_blocks"));
                say("invalid_coordinates");
            }
        }
    }

    void setSelection(SelectionBounds bounds, String key) {
        var before = historyBase();
        state.set(bounds);
        gesture = null;
        commitSelection(before, state.snapshot(), key);
    }

    MapGesture.Handle hitHandle(MapBridge.Frame frame, double x, double y) {
        SelectionBounds b = state.bounds();
        if (b == null || state.phase() != SelectionState.Phase.SELECTED || state.geometry() instanceof PolygonGeometry)
            return null;
        return MapGesture.Drag.handleAt(frame, b, x, y);
    }

    boolean insideBounds(MapBridge.Frame frame, double x, double y) {
        AreaGeometry geometry = state.geometry();
        if (geometry == null || state.phase() != SelectionState.Phase.SELECTED) return false;
        return geometry.containsPoint(frame.worldX(x), frame.worldZ(y));
    }

    public void beforeRender(int renderMouseX, int renderMouseY) {
        if (!enabled()) return;
        double mouseX = pointerX(renderMouseX), mouseY = pointerY(renderMouseY);
        if (waypointsWindow.scrollbarDragging) {
            if (dialog == Dialog.WAYPOINTS) waypointsWindow.dragWaypointScrollbar(mouseY);
            else waypointsWindow.scrollbarDragging = false;
        }
        if (colorWindows.pickerDrag != ColorWindows.PickerDrag.NONE) {
            if (dialog == Dialog.PICKER) colorWindows.dragPicker(mouseX, mouseY);
            else colorWindows.pickerDrag = ColorWindows.PickerDrag.NONE;
        }
        MapBridge.Frame frame = checkedFrame();
        if (frame != null && enabled() && dragging && waypointsWindow.pending == null && dialog == Dialog.NONE)
            bridge.exactselection$pan(mouseX - lastMouseX, mouseY - lastMouseY);
        mapPointer.followPointer(frame, mouseX, mouseY);
        if (frame != null) {
            polygonTool.trackPolygonCursor(frame, mouseX, mouseY);
            rulerTool.trackRulerCursor(frame, mouseX, mouseY);
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

    /** The shape the waypoints were last counted for, and when. */
    private AreaGeometry countedFor;

    private long countedAt;

    /**
     * Every frame: the waypoints in the selection are counted again when its shape changed, or a few times a second.
     */
    private void followWaypoints() {
        if (state.geometry() != countedFor || Util.getMillis() - countedAt >= 250) updateSelection();
    }

    void updateSelection() {
        countedFor = state.geometry();
        countedAt = Util.getMillis();
        selected = waypoints == null || !polygonTool.draft.isEmpty() || state.phase() != SelectionState.Phase.SELECTED
                ? null
                : waypoints.select(context, state.geometry());
    }

    /** An error while drawing must not reach Xaero's own rendering, where it would close the game. */
    private boolean drawErrorLogged;

    private void drawFailed(RuntimeException error) {
        if (drawErrorLogged) return;
        drawErrorLogged = true;
        LOG.error("Could not draw the selection tool; this frame is skipped", error);
    }

    public void renderMapOverlays(GuiGraphics g, int renderMouseX, int renderMouseY) {
        try {
            drawMapOverlays(g, renderMouseX, renderMouseY);
        } catch (RuntimeException error) {
            drawFailed(error);
        }
    }

    private void drawMapOverlays(GuiGraphics g, int renderMouseX, int renderMouseY) {
        double mouseX = pointerX(renderMouseX), mouseY = pointerY(renderMouseY);
        MapBridge.Frame frame = checkedFrame();
        if (frame == null || !enabled() && (!areaStore.state().showOnMap() || areas().isEmpty())) return;
        List<MapLabel> mapLabels = new ArrayList<>();
        g.nextStratum();
        if (enabled()) {
            // Only the second corner of a selection follows the pointer.
            if (state.phase() == SelectionState.Phase.PREVIEW
                    && dialog == Dialog.NONE
                    && !dirty
                    && !rulerTool.mode
                    && !selectionPanel.overPanel(mouseX, mouseY)
                    && !overNativeWidget(mouseX, mouseY)) {
                try {
                    ZoneSnap.Block corner = zoneSnap.block(frame, mouseX, mouseY);
                    int wx = corner.x(), wz = corner.z();
                    if (chunks) {
                        int originX = chunkOriginX(), originZ = chunkOriginZ();
                        int ax = ChunkSelection.anchor(originX, wx), az = ChunkSelection.anchor(originZ, wz);
                        int ex = ChunkSelection.endpoint(wx, originX), ez = ChunkSelection.endpoint(wz, originZ);
                        state.hover(ax, az, ex, ez);
                    } else state.hover(wx, wz);
                    syncFields();
                } catch (IllegalArgumentException ignored) {
                    /* Cursor outside world bounds. */
                }
            }
            followWaypoints();
            // With the tool on, saved areas always show: the Map switch is for the overlay outside it.
            g.nextStratum();
            mapOverlays.renderSavedAreas(g, frame, mapLabels);
            if (polygonTool.mode && !polygonTool.draft.isEmpty()) polygonTool.renderDraft(g, frame, mouseX, mouseY);
            if (state.bounds() != null && polygonTool.draft.isEmpty()) mapOverlays.renderBounds(g, frame);
        }
        if (!enabled() && areaStore.state().showOnMap()) {
            g.nextStratum();
            mapOverlays.renderSavedAreas(g, frame, mapLabels);
        }
        g.nextStratum();
        for (MapLabel entry : mapLabels) mapOverlays.label(g, entry.text(), entry.x(), entry.y(), entry.color());
        g.nextStratum();
        // Above area names, so the measurement stays readable over saved areas.
        if (enabled() && rulerTool.mode) {
            rulerTool.renderRuler(g, frame);
            g.nextStratum();
        }
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        try {
            drawInterface(g, mouseX, mouseY, delta);
        } catch (RuntimeException error) {
            drawFailed(error);
        }
    }

    private void drawInterface(GuiGraphics g, int mouseX, int mouseY, float delta) {
        MapBridge.Frame frame = checkedFrame();
        updateVisibility();
        if (enabled() && frame != null && dialog == Dialog.NONE) selectionPanel.renderPanel(g);
        selectionPanel.toggle.active = enabled() || frame != null;
        if (dialog != Dialog.NONE) windows.renderDialog(g);
        List<Button> menu = rightClickMenu.quickMenu ? rightClickMenu.quickMenuButtons() : List.of();
        for (AbstractWidget widget : widgets) {
            if (widget.visible && !menu.contains(widget)) widget.render(g, mouseX, mouseY, delta);
        }
        g.nextStratum();
        if (selectionPanel.panelModeButton.visible)
            PixelIcons.renderIcon(
                    g,
                    selectionPanel.compact ? PixelIcons.EXPAND : PixelIcons.COLLAPSE,
                    selectionPanel.panelModeButton,
                    Theme.ICON);
        if (areasWindow.autosaveButton.visible)
            PixelIcons.renderIcon(
                    g,
                    PixelIcons.GEAR,
                    areasWindow.autosaveButton,
                    areasWindow.autosaveOpen ? Theme.ACCENT : Theme.ICON);
        if (transferWindow.transferFolderButton.visible)
            PixelIcons.renderIcon(g, PixelIcons.FOLDER, transferWindow.transferFolderButton, Theme.ICON);
        if (selectionPanel.rulerButton.visible)
            PixelIcons.renderIcon(
                    g,
                    PixelIcons.RULER,
                    selectionPanel.rulerButton,
                    !selectionPanel.rulerButton.active
                            ? Theme.ICON_DISABLED
                            : rulerTool.mode ? RulerTool.COLOR : Theme.ICON);
        if (areasWindow.areaShow.visible) PixelIcons.renderIcon(g, PixelIcons.TARGET, areasWindow.areaShow, Theme.ICON);
        for (Button copy : List.of(
                selectionPanel.copyCoordinates,
                selectionPanel.copyCornerB,
                selectionPanel.copyArea,
                selectionPanel.copyPerimeter,
                selectionPanel.copyLength,
                selectionPanel.copyWidth,
                selectionPanel.copyRulerDistance))
            if (copy.visible)
                PixelIcons.renderIcon(g, PixelIcons.COPY, copy, copy.active ? Theme.ICON : Theme.ICON_DISABLED);
        for (Button copy : areasWindow.copyIcons())
            if (copy.visible)
                PixelIcons.renderIcon(g, PixelIcons.COPY, copy, copy.active ? Theme.ICON : Theme.ICON_DISABLED);
        exportWindow.renderPlaceholder(g, screen.getFocused() == exportWindow.folder);
        if (rightClickMenu.quickMenu) rightClickMenu.renderQuickMenu(g, menu, mouseX, mouseY);
        if (enabled() && frame != null && dialog == Dialog.NONE) selectionPanel.renderToast(g);
    }

    /**
     * What every widget shows and allows, from the state. It runs after each input event and each frame, so the code
     * that changes the state does not call it.
     */
    void updateVisibility() {
        if (selectionPanel.toggle == null) return;
        if ((selectionPanel.perimeterInline() ? -12 : 0) != selectionPanel.metricsShift)
            selectionPanel.layoutMainPanel();
        for (AbstractWidget widget : widgets) widget.visible = false;
        selectionPanel.updatePanelWidgets();
        windows.showWindowWidgets();
        selectionPanel.updatePanelActions();
        waypointsWindow.updateWaypointWidgets();
        windows.updateWindowWidgets();
        if (dialog == Dialog.AREA_NAME) newAreaWindow.updateNewAreaWidgets();
        if (dialog == Dialog.AREAS) areasWindow.updateAreaWidgets();
        if (dialog == Dialog.GROUPS) groupsWindow.updateGroupWidgets();
        windows.layoutDialog();
        // A hidden field would keep taking the keys meant for the map, such as undo and the tool keys.
        if (screen.getFocused() instanceof AbstractWidget widget && widgets.contains(widget) && !widget.visible)
            focus(null);
    }

    void show(boolean visible, AbstractWidget... targets) {
        for (AbstractWidget widget : targets) widget.visible = visible;
    }

    boolean overNativeWidget(double x, double y) {
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget
                    && !widgets.contains(widget)
                    && widget.visible
                    && widget.isMouseOver(x, y)) return true;
        }
        return false;
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        return refreshed(input.mouseClicked(event, twice));
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return refreshed(input.mouseReleased(event));
    }

    public boolean mouseScrolled(double x, double y, double vertical) {
        return refreshed(input.mouseScrolled(x, y, vertical));
    }

    public void keyReleased(KeyEvent event) {
        input.keyReleased(event);
    }

    public boolean keyPressed(KeyEvent event) {
        return refreshed(input.keyPressed(event));
    }

    public boolean charTyped(CharacterEvent event) {
        return refreshed(input.charTyped(event));
    }

    /** At a low frame rate several events fall into one frame: each must find the widgets as the last one left them. */
    private boolean refreshed(boolean handled) {
        updateVisibility();
        return handled;
    }

    void focus(AbstractWidget target) {
        for (AbstractWidget widget : widgets) widget.setFocused(widget == target);
        screen.setFocused(target);
    }

    void syncFields() {
        if (selectionPanel.fields[0] == null) return;
        SelectionBounds b = state.bounds();
        int[] values = b == null ? null : new int[] {b.x1(), b.z1(), b.x2(), b.z2()};
        syncing = true;
        for (int i = 0; i < 4; i++)
            selectionPanel.fields[i].setValue(values == null ? "" : Integer.toString(values[i]));
        syncing = false;
        dirty = false;
    }

    void applyFields() {
        if (state.geometry() instanceof PolygonGeometry || !polygonTool.draft.isEmpty() || holding()) return;
        try {
            SelectionState.Snapshot before = historyBase();
            int[] c = new int[4];
            for (int i = 0; i < 4; i++)
                c[i] = SelectionBounds.block(
                        Integer.parseInt(selectionPanel.fields[i].getValue().trim()));
            state.set(new SelectionBounds(c[0], c[1], c[2], c[3]));
            gesture = null;
            commitSelection(before, state.snapshot(), "coordinates");
            focus(null);
            message = "";
        } catch (IllegalArgumentException e) {
            say("invalid_coordinates");
        }
    }
}
