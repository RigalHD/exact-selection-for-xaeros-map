package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import static dev.exactselection.ExactSelectionController.POINT_SEARCH_FIELD;
import static dev.exactselection.ExactSelectionController.RENAME_FIELD;
import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.ModLog.LOG;

/** The waypoint window: filters, checked waypoints, and renaming, recoloring and deleting them. */
final class WaypointsWindow extends UiPart {
    private Button pointVisibility, pointColor, pointScope;
    Button pointRename;
    private Button pointRecolor;
    private boolean pointsInSelection;
    WaypointAccess.Snapshot pending;
    int modalScroll;
    final Set<Object> chosenPoints = new HashSet<>();
    int visibilityFilter;
    int colorFilter = -1;
    /** The waypoint list scrollbar follows the pointer while the left button stays down. */
    boolean scrollbarDragging;

    WaypointsWindow(ExactSelectionController ui) { super(ui); }

    private void cycleVisibilityFilter() {
        visibilityFilter = (visibilityFilter + 1) % 3;
    }

    private void cycleColorFilter() {
        colorFilter = colorFilter < 0 ? 0 : colorFilter >= WaypointAccess.COLOR_COUNT - 1 ? -1 : colorFilter + 1;
    }

    List<WaypointAccess.Point> filteredPoints() {
        if (pending == null) return List.of();
        String query = ui.dialogFields[POINT_SEARCH_FIELD].getValue().trim().toLowerCase(Locale.ROOT);
        return pending.points().stream().filter(WaypointAccess.Point::editable)
                .filter(p -> query.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(query))
                .filter(p -> visibilityFilter == 0 || (visibilityFilter == 1 && !p.disabled()) || (visibilityFilter == 2 && p.disabled()))
                .filter(p -> colorFilter < 0 || p.color() == colorFilter)
                .toList();
    }

    List<WaypointAccess.Point> filteredChosenPoints() {
        return filteredPoints().stream().filter(p -> chosenPoints.contains(p.identity())).toList();
    }

    void renderWaypointsDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (pending == null) return;
        List<WaypointAccess.Point> points = filteredPoints();
        int rows = waypointRows(h);
        modalScroll = Math.min(modalScroll, Math.max(0, points.size() - rows));
        int chosen = filteredChosenPoints().size();
        g.drawString(ui.mc.font, ui.tr("waypoint_filter_count", chosen, points.size()), x + 12, y + 29, 0xFF9BAAB7);
        boolean scrollable = points.size() > rows;
        int rowRight = x + w - (scrollable ? 18 : 9);
        for (int i = 0; i < rows && i + modalScroll < points.size(); i++) {
            WaypointAccess.Point p = points.get(i + modalScroll); int rowY = y + 83 + i * 14;
            boolean checked = chosenPoints.contains(p.identity());
            if (checked) g.fill(x + 9, rowY - 2, rowRight, rowY + 12, 0x3342636D);
            String text = (checked ? "[x] " : "[ ] ") + p.name() + "  " + p.x() + ", " + p.y() + ", " + p.z();
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(text, rowRight - x - 17), x + 13, rowY, p.disabled() ? 0xFF9BAAB7 : -1);
        }
        if (scrollable) {
            int[] thumb = waypointThumb(y, rows, points.size());
            g.fill(x + w - 14, y + 81, x + w - 10, y + 81 + rows * 14, 0xFF0C141D);
            g.fill(x + w - 14, thumb[0], x + w - 10, thumb[0] + thumb[1], scrollbarDragging ? -1 : 0xFF9BAAB7);
        }
        String hint = ui.message.isEmpty() ? ui.tr("waypoint_modal_hint").getString() : ui.message;
        g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(hint, w - 24), x + 12, y + h - 70, ui.message.isEmpty() ? 0xFF9BAAB7 : 0xFFFFCF62);
        ui.confirm.setMessage(ui.tr("delete_count", chosen));
        ui.confirm.active = chosen > 0;
    }

    static int waypointRows(int height) { return Math.max(1, (height - 150) / 14); }

    /** Scrollbar thumb {top, height} for a list of {@code total} rows showing {@code rows} from {@code modalScroll}. */
    private int[] waypointThumb(int y, int rows, int total) {
        int track = rows * 14, size = Math.max(10, track * rows / total);
        int range = total - rows, top = y + 81 + (range <= 0 ? 0 : (track - size) * modalScroll / range);
        return new int[]{top, size};
    }

    /** Scrolls so the thumb centre follows the pointer along the track. */
    void dragWaypointScrollbar(double mouseY) {
        int[] r = ui.windows.dialogRect(); int rows = waypointRows(r[3]), total = filteredPoints().size();
        if (total <= rows) return;
        int size = waypointThumb(r[1], rows, total)[1], track = rows * 14;
        double ratio = (mouseY - (r[1] + 81) - size / 2.0) / Math.max(1, track - size);
        modalScroll = (int) Math.round(Math.max(0, Math.min(1, ratio)) * (total - rows));
    }

    private WaypointAccess.Snapshot scopeSnapshot() {
        return ui.waypoints == null ? null : pointsInSelection ? ui.waypoints.select(ui.context, ui.state.geometry()) : ui.waypoints.all(ui.context);
    }

    void openWaypoints() {
        if (ui.waypoints == null) { ui.say("minimap_missing"); return; }
        if (ui.dirty || ui.holding() || !ui.polygonTool.draft.isEmpty() || ui.checkedFrame() == null || !ui.enabled()) return;
        pointsInSelection = ui.state.phase() == SelectionState.Phase.SELECTED;
        pending = ui.waypoints.captureSet(scopeSnapshot());
        if (pending == null) { ui.say("waypoints_unavailable"); return; }
        modalScroll = 0; chosenPoints.clear();
        if (pointsInSelection) for (WaypointAccess.Point p : pending.points()) if (p.editable()) chosenPoints.add(p.identity());
        ui.setDialogValue(POINT_SEARCH_FIELD, ""); ui.setDialogValue(RENAME_FIELD, "");
        visibilityFilter = 0; colorFilter = -1;
        ui.dragging = false; ui.message = ""; ui.dialog = Dialog.WAYPOINTS;
        ui.focus(ui.cancel);
    }

    private void togglePointScope() {
        if (ui.state.phase() != SelectionState.Phase.SELECTED) return;
        pointsInSelection = !pointsInSelection;
        refreshPending(pointsInSelection);
    }

    private void refreshPending(boolean selectAll) {
        pending = ui.waypoints.captureSet(scopeSnapshot());
        Set<Object> existing = new HashSet<>();
        if (pending != null) for (WaypointAccess.Point p : pending.points()) if (p.editable()) existing.add(p.identity());
        chosenPoints.retainAll(existing);
        if (selectAll) chosenPoints.addAll(existing);
        modalScroll = 0;
    }

    boolean validatePointSnapshot() {
        if (ui.checkedFrame() == null || !ui.enabled() || ui.waypoints == null || pending == null) return false;
        WaypointAccess.Snapshot current = ui.waypoints.captureSet(scopeSnapshot());
        if (pending.equals(current)) return true;
        pending = current;
        Set<Object> existing = new HashSet<>();
        if (pending != null) for (WaypointAccess.Point p : pending.points()) if (p.editable()) existing.add(p.identity());
        chosenPoints.retainAll(existing);
        modalScroll = 0; ui.say("changed");
        if (ui.dialog == Dialog.COLOR) ui.dialog = Dialog.WAYPOINTS;
        ui.focus(ui.cancel);
        return false;
    }

    void renamePoints() {
        String name = ui.dialogFields[RENAME_FIELD].getValue().trim();
        if (!name.isEmpty()) editPoints(name, null);
    }

    void editPoints(String name, Integer color) {
        if (!validatePointSnapshot()) return;
        List<WaypointAccess.Point> from = filteredChosenPoints();
        if (from.isEmpty()) return;
        List<WaypointAccess.Point> to = from.stream().map(p -> p.with(name == null ? p.name() : name, color == null ? p.color() : color)).toList();
        WaypointAccess.Snapshot target = pending;
        try {
            ui.waypoints.edit(target, from, to);
            ui.history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { ui.waypoints.edit(target, to, from); followHistory(); }
                @Override public void redo() throws Exception { ui.waypoints.edit(target, from, to); followHistory(); }
                @Override public String label() { return ui.tr("history_waypoint_edit", from.size()).getString(); }
            });
            ui.say("waypoints_edited", from.size());
            ui.dialog = Dialog.WAYPOINTS; refreshPending(false);
        } catch (Exception e) {
            ui.say("save_failed");
            LOG.error("Could not edit waypoints", e);
        }
        ui.focus(null); ui.updateSelection();
    }

    void confirmPointDelete() {
        if (!validatePointSnapshot()) return;
        List<WaypointAccess.Point> targets = filteredChosenPoints();
        if (targets.isEmpty()) return;
        WaypointAccess.Snapshot deletion = new WaypointAccess.Snapshot(pending.world(), pending.set(), pending.setName(), targets, pending.setPoints());
        int count = targets.size();
        try {
            ui.waypoints.delete(deletion);
            ui.history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { ui.waypoints.restore(deletion); followHistory(); }
                @Override public void redo() throws Exception { ui.waypoints.delete(deletion); followHistory(); }
                @Override public String label() { return ui.tr("history_delete_waypoints", count).getString(); }
            });
            ui.say("deleted", count);
            refreshPending(false);
        } catch (Exception e) {
            LOG.error("Could not save waypoint deletion; restored in-memory set", e);
            ui.say("save_failed");
        }
        ui.focus(null); ui.updateSelection();
    }

    /** Undo and redo also work with this window open, and its list must show what they changed. */
    private void followHistory() { if (pending != null) refreshPending(false); }

    void clickWaypointRow(MouseButtonEvent event, int x, int y, int w, int h) {
        if (pending == null) return;
        int rows = waypointRows(h);
        List<WaypointAccess.Point> points = filteredPoints();
        boolean scrollable = points.size() > rows;
        if (scrollable && event.x() >= x + w - 17 && event.x() < x + w - 7
                && event.y() >= y + 81 && event.y() < y + 81 + rows * 14) {
            scrollbarDragging = true;
            dragWaypointScrollbar(event.y());
            return;
        }
        // Row i is drawn over [y + 81 + 14i, y + 95 + 14i); a taller hit area would toggle a hidden row.
        if (event.x() >= x + 8 && event.x() < x + w - (scrollable ? 18 : 8) && event.y() >= y + 81 && event.y() < y + 81 + rows * 14) {
            int index = modalScroll + (int) ((event.y() - (y + 81)) / 14);
            if (index >= 0 && index < points.size()) {
                Object identity = points.get(index).identity();
                if (!chosenPoints.add(identity)) chosenPoints.remove(identity);
            }
        }
    }

    void layoutWaypoints(int x, int y, int w, int h, int buttonY) {
        place(ui.dialogFields[POINT_SEARCH_FIELD], x + 12, y + 38, w - 24, 18);
        int third = (w - 30) / 3;
        place(pointVisibility, x + 12, y + 60, third, 18);
        place(pointColor, x + 15 + third, y + 60, third, 18);
        place(pointScope, x + 18 + 2 * third, y + 60, w - 30 - 2 * third, 18);
        place(ui.dialogFields[RENAME_FIELD], x + 12, y + h - 52, w - 140, 18);
        place(pointRename, x + w - 122, y + h - 52, 110, 18);
        int actionWidth = Math.min(110, (w - 30) / 3);
        int start = x + (w - actionWidth * 3 - 6) / 2;
        place(ui.confirm, start, buttonY, actionWidth, 18);
        place(pointRecolor, start + actionWidth + 3, buttonY, actionWidth, 18);
        place(ui.cancel, start + (actionWidth + 3) * 2, buttonY, actionWidth, 18);
    }

    void updateWaypointWidgets() {
        if (ui.dialog == Dialog.WAYPOINTS) ui.show(true, ui.dialogFields[POINT_SEARCH_FIELD], ui.dialogFields[RENAME_FIELD],
                pointVisibility, pointColor, pointScope, pointRename, pointRecolor, ui.confirm, ui.cancel);
        pointVisibility.setMessage(ui.tr(new String[]{"filter_all", "filter_visible", "filter_hidden"}[visibilityFilter]));
        pointColor.setMessage(ui.tr(colorFilter < 0 ? "filter_color_all" : "filter_color", colorFilter));
        pointScope.setMessage(ui.tr(pointsInSelection ? "point_scope_selection" : "point_scope_all"));
        pointScope.active = ui.state.phase() == SelectionState.Phase.SELECTED;
        int chosenShown = filteredChosenPoints().size();
        pointRename.active = chosenShown > 0 && !ui.dialogFields[RENAME_FIELD].getValue().trim().isEmpty();
        pointRecolor.active = chosenShown > 0;
        if (ui.dialog == Dialog.WAYPOINTS) {
            ui.confirm.setMessage(ui.tr("delete_count", chosenShown));
            ui.cancel.setMessage(ui.tr("done"));
        }
        if (ui.dialog == Dialog.WAYPOINTS && pending != null) ui.confirm.active = chosenShown > 0;
    }

    void createWaypointWidgets() {
        pointVisibility = ui.button("filter_all", this::cycleVisibilityFilter);
        pointColor = ui.button("filter_color_all", this::cycleColorFilter);
        pointScope = ui.button("point_scope_all", "point_scope_tooltip", this::togglePointScope);
        pointRename = ui.button("point_rename", "point_rename_tooltip", this::renamePoints);
        pointRecolor = ui.button("area_color", "point_color_tooltip", () -> ui.colorWindows.openColorDialog(Dialog.WAYPOINTS));
    }
}
