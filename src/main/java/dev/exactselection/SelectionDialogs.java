package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import java.math.BigDecimal;
import net.minecraft.client.gui.components.Button;
import static dev.exactselection.ExactSelectionController.CENTER_NAME_FIELD;
import static dev.exactselection.ExactSelectionController.LENGTH_FIELD;
import static dev.exactselection.ExactSelectionController.OFFSET_X_FIELD;
import static dev.exactselection.ExactSelectionController.OFFSET_Z_FIELD;
import static dev.exactselection.ExactSelectionController.WIDTH_FIELD;
import static dev.exactselection.ModLog.LOG;

/** The Size, Move and Center windows of the current selection. */
final class SelectionDialogs extends UiPart {
    Button anchorButton, moveUnitsButton, centerCopy, centerCreate;
    boolean sizeFromCenter;
    boolean moveInChunks;

    SelectionDialogs(ExactSelectionController ui) { super(ui); }

    private static long positive(String value) {
        long n = Long.parseLong(value.trim());
        if (n < 1 || n > 60_000_000L) throw new IllegalArgumentException();
        return n;
    }

    private void copyCenter() {
        SelectionBounds b = ui.state.bounds();
        if (b == null) return;
        ui.selectionPanel.copyToClipboard(formatNumber(b.centerX()) + " " + formatNumber(b.centerZ()));
    }

    static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : BigDecimal.valueOf(value).toPlainString();
    }

    void createCenterWaypoint() {
        SelectionBounds b = ui.state.bounds();
        if (b == null || ui.state.phase() != SelectionState.Phase.SELECTED || ui.waypoints == null) { ui.say("minimap_missing"); return; }
        String name = ui.dialogFields[CENTER_NAME_FIELD].getValue().trim();
        if (name.isEmpty()) name = ui.tr("center_waypoint_default").getString();
        int x = (int) Math.floor(b.centerX()), z = (int) Math.floor(b.centerZ());
        int y = ui.mc.player == null ? 0 : ui.mc.player.blockPosition().getY();
        String finalName = name;
        try {
            WaypointAccess.Point[] point = {ui.waypoints.create(ui.context, x, y, z, finalName)};
            ui.history.recordApplied(new SelectionHistory.Action() {
                @Override public void undo() throws Exception { ui.waypoints.removeCreated(ui.context, point[0]); }
                @Override public void redo() throws Exception { point[0] = ui.waypoints.recreate(ui.context, point[0]); }
                @Override public String label() { return ui.tr("history_waypoint").getString(); }
            });
            ui.say("waypoint_created"); ui.dialog = Dialog.NONE; ui.updateSelection();
        } catch (Exception e) {
            ui.say("save_failed");
            LOG.error("Could not create the center waypoint", e);
        }
        ui.focus(null);
    }

    void applySizeOrMove() throws Exception {
        SelectionBounds current = ui.state.bounds();
        if (ui.dialog == Dialog.SIZE) {
            long width = positive(ui.dialogFields[WIDTH_FIELD].getValue());
            long length = positive(ui.dialogFields[LENGTH_FIELD].getValue());
            if (current == null) throw new IllegalArgumentException();
            SelectionBounds next = sizeFromCenter
                    ? SelectionBounds.aroundCenter(current.centerX(), current.centerZ(), width, length)
                    : SelectionBounds.fromLowerLeft(current.minX(), current.maxZ(), width, length);
            if (ui.chunks) next = next.expandedToChunks();
            ui.setSelection(next, "resize");
        } else if (ui.dialog == Dialog.MOVE) {
            if (current == null) throw new IllegalArgumentException();
            long dx = Long.parseLong(ui.dialogFields[OFFSET_X_FIELD].getValue().trim());
            long dz = Long.parseLong(ui.dialogFields[OFFSET_Z_FIELD].getValue().trim());
            if (moveInChunks) { dx = Math.multiplyExact(dx, 16); dz = Math.multiplyExact(dz, 16); }
            if (ui.state.geometry() instanceof PolygonGeometry) {
                SelectionState.Snapshot before = ui.state.snapshot();
                ui.state.setGeometry(ui.state.geometry().translated(dx, dz));
                ui.commitSelection(before, ui.state.snapshot(), "move");
            } else ui.setSelection(current.translated(dx, dz), "move");
        }
    }

    void createDialogWidgets() {
        anchorButton = ui.button("anchor_lower_left", () -> {
            sizeFromCenter = !sizeFromCenter;
            anchorButton.setMessage(ui.tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        });
        anchorButton.setMessage(ui.tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        moveUnitsButton = ui.button("units_blocks", () -> {
            moveInChunks = !moveInChunks;
            moveUnitsButton.setMessage(ui.tr(moveInChunks ? "units_chunks" : "units_blocks"));
        });
        moveUnitsButton.setMessage(ui.tr(moveInChunks ? "units_chunks" : "units_blocks"));
        centerCopy = ui.button("copy_center", this::copyCenter);
        centerCreate = ui.button("create_center_waypoint", this::createCenterWaypoint);
    }
}
