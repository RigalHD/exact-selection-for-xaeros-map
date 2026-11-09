package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.SavedAreaStore.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import static dev.exactselection.Theme.ACCENT;
import static dev.exactselection.ExactSelectionController.place;

/** The panel beside the map: its buttons and layout, the metric lines with their copy buttons, status lines and the toast. */
final class SelectionPanel extends UiPart {
    private static final int PANEL_X = 34, PANEL_Y = 26;
    private static final int COPY_SIZE = 10;
    private static final int COMPACT_HEIGHT = 111;
    private static final int EXPANDED_HEIGHT = 238;
    final EditBox[] fields = new EditBox[4];
    Button toggle, apply, clear, pointsButton, copyCoordinates, copyCornerB, copyArea, copyPerimeter, copyLength;
    Button copyWidth, undo, redo, chunkMode, sizeButton, moveButton, centerButton, areasButton, polygonModeButton;
    Button panelModeButton, rulerButton, copyRulerDistance, quickSave, zonesButton;
    /** -12 while the perimeter shares the area line: everything below moves up one line and the panel gets shorter. */
    int metricsShift;
    boolean compact = true;
    private int panelHeight;
    int panelScroll;
    /** The message shown at the bottom of the screen and when it appeared; it fades out after TOAST_MILLIS. */
    private static final long TOAST_MILLIS = 6000;
    private String toastText = "";
    private long toastShownAt;
    private int panelWidth;
    int coordinateLabelWidth;
    int zLabelWidth;
    private int fieldWidth;
    int fieldGap;

    /** Values joined by " · ", each followed by the copy button at the same index (if any). */
    record MetricLine(List<String> parts, List<Button> copies) {}

    private static final String METRIC_SEPARATOR = " · ";

    SelectionPanel(ExactSelectionController ui) { super(ui); }

    void togglePanelMode() {
        // The ruler keeps the panel compact; expanding it leaves the ruler.
        if (ui.rulerTool.mode) { ui.rulerTool.setRulerMode(false); compact = false; ui.message = ""; }
        else compact = !compact;
        panelScroll = 0;
        ui.focus(null);
        layoutMainPanel();
    }

    void layoutMainPanel() {
        panelWidth = Math.min(compact ? 256 : 320, Math.max(224, ui.screen.width - 68));
        metricsShift = perimeterInline() ? -12 : 0;
        panelHeight = Math.min(contentHeight(), ui.screen.height - PANEL_Y - 8);
        panelScroll = Math.max(0, Math.min(panelScroll, maxPanelScroll()));
        int x = PANEL_X + 8, top = PANEL_Y - (compact ? 0 : panelScroll), y = top + metricsShift, w = panelWidth - 16;
        place(panelModeButton, PANEL_X + panelWidth - 30, PANEL_Y + 3, 22, 18);
        place(rulerButton, PANEL_X + panelWidth - 53, PANEL_Y + 3, 20, 18);
        if (compact) {
            // Groups open from the Areas window; Esc or the right-click menu clears the selection.
            arrowRow(new Button[]{polygonModeButton, areasButton}, x, y + 74, w);
        } else {
            // Narrow coordinate fields; Save and, below it, Apply stand beside them (Save is placed in updatePanelWidgets).
            // Size, Move and Center live in the right-click menu, Groups in the Areas window.
            int applyWidth = ui.mc.font.width(apply.getMessage());
            for (String key : List.of("quick_save", "quick_apply", "quick_autosaved")) applyWidth = Math.max(applyWidth, ui.mc.font.width(ui.tr(key)));
            // A long translation scrolls inside its button instead of squeezing the fields.
            applyWidth = Math.min(w / 4, applyWidth + 12);
            fieldWidth = (w - coordinateLabelWidth - zLabelWidth - fieldGap * 3 - COPY_SIZE - 3 - 6 - applyWidth) / 2;
            int xField = x + coordinateLabelWidth + fieldGap;
            int zField = xField + fieldWidth + fieldGap * 2 + zLabelWidth;
            for (int i = 0; i < fields.length; i++)
                place(fields[i], i % 2 == 0 ? xField : zField, top + 34 + i / 2 * 24, fieldWidth, 18);
            place(copyCoordinates, zField + fieldWidth + 3, top + 38, COPY_SIZE, COPY_SIZE);
            place(copyCornerB, zField + fieldWidth + 3, top + 62, COPY_SIZE, COPY_SIZE);
            place(apply, x + w - applyWidth, top + 58, applyWidth, 18);
            arrowRow(new Button[]{chunkMode, polygonModeButton, clear}, x, y + 126, w);
            mainRow(new Button[]{areasButton, zonesButton, pointsButton}, x, y + 148, w, 18);
            place(ui.mapExport.selectionExportButton, x, y + 170, w, 18);
        }
    }

    private int contentHeight() { return (compact ? COMPACT_HEIGHT : EXPANDED_HEIGHT) + metricsShift; }

    private Component areaText() {
        return ui.state.bounds() == null ? ui.tr("area_empty") : ui.tr("area", SelectionDialogs.formatNumber(ui.state.geometry().area()));
    }

    private Component perimeterText() {
        return ui.tr("perimeter", ui.state.bounds() == null ? "—" : formatLength(ui.state.geometry().perimeter()));
    }

    /** The perimeter goes on the area line when both fit in the panel width. */
    boolean perimeterInline() {
        if (ui.rulerTool.mode) return false;
        return metricWidth(new MetricLine(List.of(areaText().getString(), perimeterText().getString()),
                List.of(copyArea, copyPerimeter))) <= panelWidth - 16;
    }

    private int metricWidth(MetricLine line) {
        int width = 0;
        for (int i = 0; i < line.parts().size(); i++) {
            if (i > 0) width += ui.mc.font.width(METRIC_SEPARATOR);
            width += ui.mc.font.width(line.parts().get(i)) + (i < line.copies().size() ? 3 + COPY_SIZE : 0);
        }
        return width;
    }

    private MetricLine fitMetricLine(MetricLine line, int width) {
        if (metricWidth(line) <= width) return line;
        int textWidth = line.parts().stream().mapToInt(ui.mc.font::width).sum();
        int available = Math.max(0, width - (line.parts().size() - 1) * ui.mc.font.width(METRIC_SEPARATOR)
                - Math.min(line.parts().size(), line.copies().size()) * (3 + COPY_SIZE));
        List<String> fitted = new ArrayList<>();
        int remaining = available;
        for (int i = 0; i < line.parts().size(); i++) {
            String part = line.parts().get(i);
            int budget = i == line.parts().size() - 1 ? remaining
                    : textWidth == 0 ? 0 : (int) ((long) available * ui.mc.font.width(part) / textWidth);
            String text = ui.mc.font.width(part) <= budget ? part : budget < ui.mc.font.width("…") ? ""
                    : ui.mc.font.plainSubstrByWidth(part, budget - ui.mc.font.width("…")).stripTrailing() + "…";
            fitted.add(text); remaining -= ui.mc.font.width(text);
        }
        return new MetricLine(fitted, line.copies());
    }

    /** Puts each copy button right after its value; buttons without a value on this line are hidden. */
    void placeMetricLine(MetricLine line, int x, int y, int width) {
        line = fitMetricLine(line, width);
        for (int i = 0; i < line.copies().size(); i++) if (i >= line.parts().size()) line.copies().get(i).visible = false;
        for (int i = 0; i < line.parts().size(); i++) {
            if (i > 0) x += ui.mc.font.width(METRIC_SEPARATOR);
            x += ui.mc.font.width(line.parts().get(i));
            if (i < line.copies().size()) {
                place(line.copies().get(i), x + 3, y - 1, COPY_SIZE, COPY_SIZE);
                x += 3 + COPY_SIZE;
            }
        }
    }

    void drawMetricLine(GuiGraphics g, MetricLine line, int x, int y, int width, int color) {
        line = fitMetricLine(line, width);
        for (int i = 0; i < line.parts().size(); i++) {
            if (i > 0) {
                g.drawString(ui.mc.font, METRIC_SEPARATOR, x, y, color);
                x += ui.mc.font.width(METRIC_SEPARATOR);
            }
            g.drawString(ui.mc.font, line.parts().get(i), x, y, color);
            x += ui.mc.font.width(line.parts().get(i));
            if (i < line.copies().size()) x += 3 + COPY_SIZE;
        }
    }

    List<String> dimensionParts(SelectionBounds bounds, boolean includeChunks) {
        List<String> parts = new ArrayList<>();
        parts.add(ui.tr("width", bounds == null ? "—" : bounds.width()).getString());
        parts.add(ui.tr("length", bounds == null ? "—" : bounds.length()).getString());
        if (includeChunks && bounds != null) parts.add(ui.tr("chunk_count", bounds.chunkCount()).getString());
        return parts;
    }

    private List<String> dimensionText() { return dimensionParts(ui.state.bounds(), ui.chunks); }

    /** The panel's metric lines; their y is relative to the panel top, as for line(). */
    private List<Map.Entry<Integer, MetricLine>> panelMetricLines() {
        List<Map.Entry<Integer, MetricLine>> lines = new ArrayList<>();
        if (ui.rulerTool.mode) {
            lines.add(Map.entry(compact ? 33 : 82, new MetricLine(List.of(ui.rulerTool.rulerDistanceText()), List.of(copyRulerDistance))));
            lines.add(Map.entry(compact ? 45 : 94, new MetricLine(ui.rulerTool.rulerDeltaParts(), List.of())));
            String other = ui.rulerTool.rulerOtherDimensionText();
            if (other != null) lines.add(Map.entry(compact ? 57 : 106, new MetricLine(List.of(other), List.of())));
            return lines;
        }
        lines.add(Map.entry(compact ? 33 : 82, new MetricLine(dimensionText(), List.of(copyWidth, copyLength))));
        if (metricsShift != 0) lines.add(Map.entry(compact ? 45 : 94, new MetricLine(
                List.of(areaText().getString(), perimeterText().getString()), List.of(copyArea, copyPerimeter))));
        else {
            lines.add(Map.entry(compact ? 45 : 94, new MetricLine(List.of(areaText().getString()), List.of(copyArea))));
            lines.add(Map.entry(compact ? 57 : 106, new MetricLine(List.of(perimeterText().getString()), List.of(copyPerimeter))));
        }
        return lines;
    }

    private void placePanelCopies() {
        int scroll = compact ? 0 : panelScroll;
        for (Map.Entry<Integer, MetricLine> line : panelMetricLines())
            placeMetricLine(line.getValue(), PANEL_X + 8, PANEL_Y + line.getKey() - scroll, panelWidth - 16);
    }

    private void arrowRow(Button[] buttons, int x, int y, int width) {
        place(undo, x, y, 20, 18); place(redo, x + 23, y, 20, 18);
        mainRow(buttons, x + 46, y, width - 46, 18);
    }

    private void mainRow(Button[] buttons, int x, int y, int width, int height) {
        int step = (width + 3) / buttons.length;
        for (int i = 0; i < buttons.length; i++)
            place(buttons[i], x + step * i, y, i == buttons.length - 1 ? width - step * i : step - 3, height);
    }

    int maxPanelScroll() {
        return compact ? 0 : Math.max(0, contentHeight() - panelHeight + (panelHeight < contentHeight() ? 12 : 0));
    }

    /** The latest message, centred near the bottom of the screen for a few seconds instead of cut short in the panel. */
    void renderToast(GuiGraphics g) {
        long now = Util.getMillis();
        if (!ui.message.equals(toastText)) { toastText = ui.message; toastShownAt = now; }
        if (ui.message.isEmpty()) return;
        if (now - toastShownAt > TOAST_MILLIS) {
            // The export notice also keeps the panel's "Show file" button; only the toast hides.
            if (!ui.mapExport.hasExportNotice()) { ui.message = ""; toastText = ""; }
            return;
        }
        List<FormattedCharSequence> lines = ui.mc.font.split(Component.literal(ui.message), Math.min(360, ui.screen.width - 40));
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int w = 0;
        for (var line : lines) w = Math.max(w, ui.mc.font.width(line));
        int boxW = w + 16, boxH = lines.size() * 10 + 10, x = (ui.screen.width - boxW) / 2, y = ui.screen.height - 52 - boxH;
        g.nextStratum();
        g.fill(x, y, x + boxW, y + boxH, Theme.PANEL_BACKGROUND);
        g.renderOutline(x, y, boxW, boxH, Theme.BORDER);
        for (int i = 0; i < lines.size(); i++)
            g.drawCenteredString(ui.mc.font, lines.get(i), ui.screen.width / 2, y + 6 + i * 10, Theme.WARNING);
    }

    void renderPanel(GuiGraphics g) {
        g.nextStratum();
        g.fill(PANEL_X, PANEL_Y, PANEL_X + panelWidth, PANEL_Y + panelHeight, Theme.PANEL_BACKGROUND);
        g.renderOutline(PANEL_X, PANEL_Y, panelWidth, panelHeight, Theme.BORDER);
        int titleEnd = (compact && quickSave.visible ? quickSave : rulerButton).getX() - 4;
        g.drawString(ui.mc.font, fitHint(ui.tr("title").getString(), titleEnd - PANEL_X - 8),
                PANEL_X + 8, PANEL_Y + 7, ACCENT);
        String hint = ui.rulerTool.mode ? "ruler_controls" : ui.polygonTool.mode ? compact ? "polygon_controls_short" : "polygon_controls"
                : ui.state.geometry() instanceof PolygonGeometry ? "polygon_edit_controls"
                : ui.state.source() != null && ui.state.phase() == SelectionState.Phase.SELECTED ? "rectangle_edit_controls"
                : ui.state.phase() == SelectionState.Phase.PREVIEW ? "second_corner"
                : ui.state.phase() == SelectionState.Phase.SELECTED ? "new_selection" : "first_corner";
        // Two pixels below the mode button so the line never touches it.
        g.drawString(ui.mc.font, fitHint(ui.tr(hint).getString(), panelWidth - 16), PANEL_X + 8, PANEL_Y + 22, 0xFFD0DBE2);
        g.enableScissor(PANEL_X + 1, PANEL_Y + 32, PANEL_X + panelWidth - 1,
                PANEL_Y + panelHeight - (maxPanelScroll() > 0 ? 16 : 3));
        // The compact panel leaves the corners to the expanded one.
        if (!compact) {
            int labelX = fields[0].getX() - fieldGap - coordinateLabelWidth;
            int zX = fields[1].getX() - fieldGap - zLabelWidth;
            g.drawString(ui.mc.font, "A: X", labelX, PANEL_Y + 39 - panelScroll, -1);
            g.drawString(ui.mc.font, "Z", zX, PANEL_Y + 39 - panelScroll, -1);
            g.drawString(ui.mc.font, "B: X", labelX, PANEL_Y + 63 - panelScroll, -1);
            g.drawString(ui.mc.font, "Z", zX, PANEL_Y + 63 - panelScroll, -1);
        }
        int sh = metricsShift;
        boolean first = true;
        for (Map.Entry<Integer, MetricLine> metric : panelMetricLines()) {
            drawMetricLine(g, metric.getValue(), PANEL_X + 8, PANEL_Y + metric.getKey() - (compact ? 0 : panelScroll), panelWidth - 16,
                    first ? -1 : ACCENT);
            first = false;
        }
        int lineY = PANEL_Y + sh + (compact ? 70 : 120 - panelScroll);
        g.fill(PANEL_X + 8, lineY, PANEL_X + panelWidth - 8, lineY + 1, Theme.BORDER);
        if (!compact) {
            int n = ui.selected == null ? 0 : ui.selected.points().size();
            Component count = ui.waypoints == null ? ui.tr("minimap_missing") : ui.selected == null
                    ? ui.tr("waypoints_unavailable") : ui.tr("count", n, setNameDisplay(ui.selected.setName()));
            line(g, count, 194 + sh, Theme.WARNING);
            line(g, ui.tr(ui.loadFailed ? ui.loadFailedKey() : "scope"), 206 + sh, Theme.TEXT_MUTED);
        }
        Area editing = ui.areasWindow.editedArea();
        // Messages go to the bottom of the screen (renderToast); the panel keeps the controls or the edited area.
        // The compact panel has no scope line, so it keeps the load failure in the status line.
        boolean loadWarning = compact && ui.loadFailed;
        Component status = loadWarning ? ui.tr(ui.loadFailedKey())
                : compact && ui.dirty ? ui.tr("coordinates_pending")
                : editing == null ? ui.tr("controls") : ui.tr("editing_area", editing.name());
        if (!ui.mapExport.hasExportNotice()) line(g, status, (compact ? 96 : 221) + sh, loadWarning ? Theme.WARNING : Theme.TEXT_MUTED);
        g.disableScissor();
        if (maxPanelScroll() > 0)
            g.drawString(ui.mc.font, ui.tr("panel_scroll"), PANEL_X + 8, PANEL_Y + panelHeight - 12, Theme.TEXT_MUTED);
    }

    /**
     * Keeps whole " · "-separated hints that fit, so a narrow panel drops trailing hints instead of cutting a word;
     * a single hint that is still too wide ends with an ellipsis.
     */
    private String fitHint(String text, int width) {
        if (ui.mc.font.width(text) <= width) return text;
        String[] parts = text.split(" · ");
        String fitted = parts[0];
        for (int i = 1; i < parts.length && ui.mc.font.width(fitted + " · " + parts[i]) <= width; i++) fitted += " · " + parts[i];
        if (ui.mc.font.width(fitted) <= width) return fitted;
        return ui.mc.font.plainSubstrByWidth(fitted, width - ui.mc.font.width("…")).stripTrailing() + "…";
    }

    private void line(GuiGraphics g, Component text, int y, int color) {
        g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(text.getString(), panelWidth - 16), PANEL_X + 8,
                PANEL_Y + y - (compact ? 0 : panelScroll), color);
    }

    private static Component setNameDisplay(String name) {
        return "gui.xaero_default".equals(name) ? Component.translatable(name) : Component.literal(name);
    }

    boolean overPanel(double x, double y) {
        return ui.enabled() && x >= PANEL_X && x < PANEL_X + panelWidth
                && y >= PANEL_Y && y < PANEL_Y + panelHeight;
    }

    void updatePanelWidgets() {
        boolean main = ui.enabled() && ui.dialog == Dialog.NONE;
        ui.show(main, panelModeButton, undo, redo, polygonModeButton, rulerButton);
        ui.show(main && !ui.rulerTool.mode, copyArea, copyPerimeter, copyLength, copyWidth);
        ui.show(main && ui.rulerTool.mode, copyRulerDistance);
        ui.show(main, areasButton);
        boolean route = ui.rulerTool.mode;
        ui.show(main && (route ? ui.rulerTool.routeReady() : !compact || ui.state.phase() == SelectionState.Phase.SELECTED), quickSave);
        ui.show(main && !compact, fields[0], fields[1], fields[2], fields[3], apply, chunkMode, copyCoordinates, copyCornerB, clear,
                zonesButton, pointsButton, ui.mapExport.selectionExportButton);
        if (main && ui.mapExport.hasExportNotice()) {
            place(ui.mapExport.openExportButton, PANEL_X + 8, PANEL_Y + metricsShift + (compact ? 93 : 218 - panelScroll),
                    panelWidth - 16, 16);
            ui.show(true, ui.mapExport.openExportButton);
        }
        Area editing = route ? null : ui.areasWindow.editedArea();
        boolean autoGeometry = editing != null && ui.autosave.covers(AutosaveSettings.Field.GEOMETRY);
        quickSave.setMessage(ui.tr(editing == null ? "quick_save" : autoGeometry ? "quick_autosaved" : "quick_apply"));
        ui.tip(quickSave, route ? ui.tr("route_save_tooltip") : editing == null ? ui.tr("quick_save_tooltip")
                : autoGeometry ? ui.tr("quick_autosaved_tooltip", editing.name()) : ui.tr("quick_apply_tooltip", editing.name()));
        quickSave.active = route ? !ui.loadFailed : ui.state.phase() == SelectionState.Phase.SELECTED && !ui.dirty && ui.polygonTool.draft.isEmpty()
                && !ui.holding() && !ui.loadFailed && (editing == null || !editing.geometry().equals(ui.state.geometry()));
        // Compact: in the header beside the mode icon; expanded: under Apply, beside the second coordinate row.
        int saveWidth = Math.max(40, ui.mc.font.width(quickSave.getMessage()) + 10);
        if (compact) place(quickSave, rulerButton.getX() - 3 - saveWidth, PANEL_Y + 3, saveWidth, 18);
        else place(quickSave, apply.getX(), apply.getY() - 24, apply.getWidth(), 18);
        panelModeButton.setMessage(Component.empty());
        ui.tip(panelModeButton, ui.tr(compact ? "panel_expand_tooltip" : "panel_compact_tooltip"));
        if (main) placePanelCopies();
        if (main && !compact) {
            for (AbstractWidget widget : ui.widgets) {
                if (widget.visible && widget != panelModeButton && widget != rulerButton)
                    widget.visible = widget.getY() >= PANEL_Y + 32
                            && widget.getY() + widget.getHeight() <= PANEL_Y + panelHeight - (maxPanelScroll() > 0 ? 16 : 3);
            }
        }
        ui.rightClickMenu.placeQuickMenu(main);
        boolean contourDraft = !ui.polygonTool.draft.isEmpty();
        boolean polygonSelection = ui.state.geometry() instanceof PolygonGeometry;
        for (EditBox field : fields) field.active = !polygonSelection && !contourDraft && !ui.holding();
        boolean draftHistory = !ui.polygonTool.draftUndo.isEmpty() || !ui.polygonTool.draftRedo.isEmpty();
        undo.active = (draftHistory ? !ui.polygonTool.draftUndo.isEmpty() : ui.history.canUndo())
                || ui.dirty || ui.gesture != null;
        redo.active = (!ui.polygonTool.draftRedo.isEmpty() || !contourDraft && ui.history.canRedo())
                && !ui.dirty && ui.gesture == null;
        // Name the stored action only when the button would apply it rather than discard a draft or gesture.
        boolean historyStep = !draftHistory && !ui.dirty && ui.gesture == null;
        ui.tip(undo, historyTooltip("undo_tooltip", historyStep ? ui.history.undoLabel() : ""));
        ui.tip(redo, historyTooltip("redo_tooltip", historyStep && redo.active ? ui.history.redoLabel() : ""));
        ui.tip(ui.managementUndo, historyTooltip("undo_tooltip", ui.history.undoLabel()));
        ui.tip(ui.managementRedo, historyTooltip("redo_tooltip", ui.history.redoLabel()));
        chunkMode.setMessage(ui.tr(ui.chunks ? "units_chunks" : "units_blocks"));
        ui.mapExport.selectionExportButton.active = ui.mapExport.canExportSelection();
        toggle.visible = ui.dialog == Dialog.NONE;
        toggle.setMessage(Component.literal(ui.enabled() ? "■" : "□"));
        polygonModeButton.setMessage(ui.tr(ui.polygonTool.mode ? "polygon_mode_on" : "polygon_mode"));
        polygonModeButton.active = ui.enabled() && !ui.dirty && !ui.holding() && ui.dialog == Dialog.NONE;
        ui.tip(polygonModeButton, ui.tr(ui.polygonTool.mode ? "polygon_controls" : "polygon_mode_tooltip"));
        rulerButton.active = ui.enabled() && !ui.dirty && !ui.holding() && ui.dialog == Dialog.NONE && ui.polygonTool.draft.isEmpty();
        ui.tip(rulerButton, ui.tr(ui.rulerTool.mode ? "ruler_mode_on_tooltip" : "ruler_mode_tooltip"));
        copyRulerDistance.active = ui.rulerTool.pointA != null && ui.rulerTool.pointB != null;
        boolean finished = ui.state.phase() == SelectionState.Phase.SELECTED && ui.state.bounds() != null;
        sizeButton.active = finished && !(ui.state.geometry() instanceof PolygonGeometry) && !ui.dirty && !contourDraft && !ui.holding();
        chunkMode.active = !(ui.state.geometry() instanceof PolygonGeometry) && !contourDraft && !ui.holding();
        moveButton.active = centerButton.active = finished && !ui.dirty && !contourDraft && !ui.holding();
        areasButton.active = !ui.dirty && !contourDraft && !ui.holding();
        int framed = compact ? 0 : ui.areasWindow.areasInSelection().size();
        zonesButton.setMessage(ui.tr("zones_in_selection", framed));
        zonesButton.active = framed > 0 && !ui.dirty && !contourDraft && !ui.holding();
    }

    void updatePanelActions() {
        boolean contourDraft = !ui.polygonTool.draft.isEmpty();
        boolean polygonSelection = ui.state.geometry() instanceof PolygonGeometry;
        apply.active = ui.enabled() && !polygonSelection && !contourDraft && !ui.holding() && (ui.dirty || ui.state.bounds() != null);
        clear.active = ui.state.bounds() != null || ui.dirty || contourDraft;
        int pointCount = ui.selected == null ? 0 : ui.selected.points().size();
        pointsButton.setMessage(ui.state.phase() == SelectionState.Phase.SELECTED ? ui.tr("waypoints_button", pointCount) : ui.tr("waypoints_all"));
        pointsButton.active = ui.waypoints != null && !ui.dirty && !contourDraft && !ui.holding();
        boolean canCopy = ui.enabled() && !ui.dirty && !contourDraft && !ui.holding() && ui.dialog == Dialog.NONE && ui.state.bounds() != null;
        copyCoordinates.active = copyCornerB.active = copyArea.active = copyPerimeter.active = copyLength.active = copyWidth.active = canCopy;
    }

    void createPanelWidgets() {
        toggle = ui.button("toggle_icon", "title", ui::toggle);
        place(toggle, 6, 28, 24, 18);
        coordinateLabelWidth = Math.max(ui.mc.font.width("A: X"), ui.mc.font.width("B: X"));
        zLabelWidth = ui.mc.font.width("Z");
        fieldGap = 6;
        for (int i = 0; i < 4; i++) {
            int col = i % 2, row = i / 2;
            EditBox field = new EditBox(ui.mc.font, 0, 0, 100, 18,
                    ui.tr("coordinate", row == 0 ? "A" : "B", col == 0 ? "X" : "Z"));
            field.setMaxLength(10);
            field.setResponder(value -> { if (!ui.syncing) { ui.dirty = true; ui.message = ""; } });
            fields[i] = field;
            ui.widgets.add(field);
        }
        panelModeButton = ui.button("panel_expand", this::togglePanelMode);
        apply = ui.button("apply_short", "apply", ui::applyFields);
        copyCoordinates = ui.iconButton("copy_corner_a_tooltip", this::copyCoordinates);
        copyCornerB = ui.iconButton("copy_corner_b_tooltip", () -> copySelection(5));
        copyArea = ui.iconButton("copy_area_tooltip", this::copyArea);
        copyPerimeter = ui.iconButton("copy_perimeter_tooltip", this::copyPerimeter);
        copyLength = ui.iconButton("copy_length_tooltip", this::copyLength);
        copyWidth = ui.iconButton("copy_width_tooltip", this::copyWidth);
        undo = ui.arrowButton("↶", ui::undo);
        redo = ui.arrowButton("↷", ui::redo);
        chunkMode = ui.button("units_blocks", ui::toggleChunks);
        sizeButton = ui.button("size", () -> ui.windows.openDialog(Dialog.SIZE));
        moveButton = ui.button("move", () -> ui.windows.openDialog(Dialog.MOVE));
        centerButton = ui.button("center", () -> ui.windows.openDialog(Dialog.CENTER));
        polygonModeButton = ui.button("polygon_mode", ui.polygonTool::togglePolygonMode);
        rulerButton = ui.arrowButton("", ui.rulerTool::toggleRulerMode);
        copyRulerDistance = ui.iconButton("ruler_copy_tooltip", ui.rulerTool::copyRulerDistance);
        areasButton = ui.button("areas_window", () -> ui.windows.openDialog(Dialog.AREAS));
        clear = ui.button("clear", ui::reset);
        quickSave = ui.button("quick_save", () -> { if (ui.rulerTool.mode) ui.rulerTool.saveRoute(); else quickSave(); });
        zonesButton = ui.button("zones_in_selection", "zones_in_selection_tooltip", ui.areasWindow::openAreasInSelection);
        pointsButton = ui.button("waypoints_all", ui.waypointsWindow::openWaypoints);
        pointsButton.setTooltip(Tooltip.create(ui.tr(ui.waypoints == null ? "minimap_missing" : "waypoints_tooltip")));
    }

    void quickSave() {
        if (ui.areasWindow.editedArea() != null) ui.areasWindow.applyEditedArea();
        else { ui.newAreaWindow.areaNameReturn = Dialog.NONE; ui.newAreaWindow.openAreaNameDialog(); }
    }

    private SelectionBounds copyableBounds() {
        return ui.enabled() && !ui.dirty && ui.waypointsWindow.pending == null ? ui.state.bounds() : null;
    }

    private void copyCoordinates() {
        SelectionBounds bounds = copyableBounds();
        if (bounds != null) copyToClipboard(cornerCoordinates(bounds, true));
    }

    private void copyArea() { copySelection(1); }

    private void copyPerimeter() { copySelection(2); }

    private void copyLength() { copySelection(3); }

    private void copyWidth() { copySelection(4); }

    private void copySelection(int kind) {
        SelectionBounds b = copyableBounds();
        if (b != null) copyMetric(kind, ui.state.geometry(), b);
    }

    private static String cornerCoordinates(SelectionBounds bounds, boolean first) {
        return first ? bounds.x1() + " " + bounds.z1() : bounds.x2() + " " + bounds.z2();
    }

    /** Copies one value: 0 corner A or polygon vertices, 1 area, 2 perimeter, 3 length, 4 width, 5 corner B. */
    void copyMetric(int kind, AreaGeometry geometry, SelectionBounds b) {
        copyToClipboard(switch (kind) {
            case 0 -> geometry instanceof RectangleGeometry ? cornerCoordinates(b, true)
                    : geometry.vertices().stream().map(p -> p.x() + " " + p.z()).collect(Collectors.joining("; "));
            case 1 -> ui.autosave.rounding() == AutosaveSettings.Rounding.EXACT ? SelectionDialogs.formatNumber(geometry.area()) : rounded(geometry.area());
            case 2 -> ui.autosave.rounding() == AutosaveSettings.Rounding.EXACT ? formatLength(geometry.perimeter()) : rounded(geometry.perimeter());
            case 3 -> Long.toString(b.length());
            case 5 -> cornerCoordinates(b, false);
            default -> Long.toString(b.width());
        });
    }

    /** Whole blocks for pasting; the small margin keeps a value computed a hair off an integer from gaining or losing a block. */
    String rounded(double value) {
        return Long.toString(ui.autosave.rounding() == AutosaveSettings.Rounding.UP
                ? (long) Math.ceil(value - 1e-9) : (long) Math.floor(value + 1e-9));
    }

    static String formatLength(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    void copyToClipboard(String value) {
        ui.mc.keyboardHandler.setClipboard(value);
        ui.say("copied");
    }

    Component historyTooltip(String key, String label) {
        return label.isEmpty() ? ui.tr(key + "_plain") : ui.tr(key, label);
    }
}
