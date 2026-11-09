package dev.exactselection;

import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.ModLog.LOG;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.ExactSelectionController.DialogField;
import java.math.BigDecimal;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

/** The Size, Move and Center windows of the current selection. */
final class SelectionDialogs extends UiPart {
    Button anchorButton, moveUnitsButton, centerCopy, centerCreate;
    boolean sizeFromCenter;

    final Window sizeWindow = new Window() {
        @Override
        int width() {
            return 560;
        }

        @Override
        int height() {
            return 180;
        }

        @Override
        String title() {
            return "dialog_size";
        }

        @Override
        void show() {
            ui.show(
                    true,
                    ui.field(DialogField.WIDTH),
                    ui.field(DialogField.LENGTH),
                    anchorButton,
                    ui.modalApply,
                    ui.modalCancel);
        }

        @Override
        void layout(Rect r, int buttonY) {
            int x = r.x(), y = r.y(), w = r.width();
            place(ui.field(DialogField.WIDTH), x + 18, y + 52, (w - 54) / 2, 18);
            place(ui.field(DialogField.LENGTH), x + 36 + (w - 54) / 2, y + 52, (w - 54) / 2, 18);
            place(anchorButton, x + 18, y + 82, w - 36, 18);
            place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
            place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
        }

        @Override
        void render(GuiGraphics g, Rect r) {
            int x = r.x(), y = r.y(), w = r.width();
            g.drawString(ui.mc.font, ui.tr("width_field"), x + 18, y + 38, -1);
            g.drawString(ui.mc.font, ui.tr("length_field"), x + 36 + (w - 54) / 2, y + 38, -1);
            SelectionBounds b = ui.state.bounds();
            if (b != null)
                g.drawString(
                        ui.mc.font,
                        ui.tr(
                                "current_anchor",
                                formatNumber(sizeFromCenter ? b.centerX() : b.minX()),
                                formatNumber(sizeFromCenter ? b.centerZ() : b.maxZ())),
                        x + 18,
                        y + 108,
                        Theme.TEXT_MUTED);
            g.drawString(ui.mc.font, ui.tr("lower_left_axes"), x + 18, y + 124, Theme.TEXT_MUTED);
        }
    };
    boolean moveInChunks;

    final Window moveWindow = new Window() {
        @Override
        int width() {
            return 560;
        }

        @Override
        int height() {
            return 180;
        }

        @Override
        String title() {
            return "dialog_move";
        }

        @Override
        void show() {
            ui.show(
                    true,
                    ui.field(DialogField.OFFSET_X),
                    ui.field(DialogField.OFFSET_Z),
                    moveUnitsButton,
                    ui.modalApply,
                    ui.modalCancel);
        }

        @Override
        void layout(Rect r, int buttonY) {
            int x = r.x(), y = r.y(), w = r.width();
            place(ui.field(DialogField.OFFSET_X), x + 18, y + 52, (w - 54) / 2, 18);
            place(ui.field(DialogField.OFFSET_Z), x + 36 + (w - 54) / 2, y + 52, (w - 54) / 2, 18);
            place(moveUnitsButton, x + 18, y + 82, w - 36, 18);
            place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
            place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
        }

        @Override
        void render(GuiGraphics g, Rect r) {
            int x = r.x(), y = r.y(), w = r.width();
            g.drawString(ui.mc.font, ui.tr("offset_x"), x + 18, y + 38, -1);
            g.drawString(ui.mc.font, ui.tr("offset_z"), x + 36 + (w - 54) / 2, y + 38, -1);
            g.drawString(ui.mc.font, ui.tr("move_note"), x + 18, y + 108, Theme.TEXT_MUTED);
        }
    };

    final Window centerWindow = new Window() {
        @Override
        int width() {
            return 560;
        }

        @Override
        int height() {
            return 172;
        }

        @Override
        String title() {
            return "dialog_center";
        }

        @Override
        void show() {
            ui.show(true, ui.field(DialogField.CENTER_NAME), centerCopy, centerCreate, ui.modalCancel);
        }

        @Override
        void layout(Rect r, int buttonY) {
            int x = r.x(), y = r.y(), w = r.width();
            place(ui.field(DialogField.CENTER_NAME), x + 18, y + 58, w - 36, 18);
            place(centerCopy, x + 18, buttonY - 22, Math.max(70, (w - 48) / 2), 18);
            place(centerCreate, x + 30 + (w - 48) / 2, buttonY - 22, Math.max(70, (w - 48) / 2), 18);
            place(ui.modalCancel, x + w / 2 - 55, buttonY, 110, 18);
        }

        @Override
        void render(GuiGraphics g, Rect r) {
            int x = r.x(), y = r.y();
            SelectionBounds b = ui.state.bounds();
            g.drawString(
                    ui.mc.font,
                    b == null
                            ? ui.tr("no_selection")
                            : ui.tr("center_value", formatNumber(b.centerX()), formatNumber(b.centerZ())),
                    x + 18,
                    y + 34,
                    -1);
            g.drawString(ui.mc.font, ui.tr("center_waypoint_y"), x + 18, y + 82, Theme.TEXT_MUTED);
        }
    };

    SelectionDialogs(ExactSelectionController ui) {
        super(ui);
    }

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
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : BigDecimal.valueOf(value).toPlainString();
    }

    void createCenterWaypoint() {
        SelectionBounds b = ui.state.bounds();
        if (b == null || ui.state.phase() != SelectionState.Phase.SELECTED || ui.waypoints == null) {
            ui.say("minimap_missing");
            return;
        }
        String name = ui.field(DialogField.CENTER_NAME).getValue().trim();
        if (name.isEmpty()) name = ui.tr("center_waypoint_default").getString();
        int x = (int) Math.floor(b.centerX()), z = (int) Math.floor(b.centerZ());
        int y = ui.mc.player == null ? 0 : ui.mc.player.blockPosition().getY();
        String finalName = name;
        try {
            WaypointAccess.Point[] point = {ui.waypoints.create(ui.context, x, y, z, finalName)};
            ui.history.recordApplied(new SelectionHistory.Action() {
                @Override
                public void undo() throws Exception {
                    ui.waypoints.removeCreated(ui.context, point[0]);
                }

                @Override
                public void redo() throws Exception {
                    point[0] = ui.waypoints.recreate(ui.context, point[0]);
                }

                @Override
                public String label() {
                    return ui.tr("history_waypoint").getString();
                }
            });
            ui.say("waypoint_created");
            ui.dialog = Dialog.NONE;
            ui.updateSelection();
        } catch (Exception e) {
            ui.say("save_failed");
            LOG.error("Could not create the center waypoint", e);
        }
        ui.focus(null);
    }

    void applySizeOrMove() throws Exception {
        SelectionBounds current = ui.state.bounds();
        if (ui.dialog == Dialog.SIZE) {
            long width = positive(ui.field(DialogField.WIDTH).getValue());
            long length = positive(ui.field(DialogField.LENGTH).getValue());
            if (current == null) throw new IllegalArgumentException();
            SelectionBounds next = sizeFromCenter
                    ? SelectionBounds.aroundCenter(current.centerX(), current.centerZ(), width, length)
                    : SelectionBounds.fromLowerLeft(current.minX(), current.maxZ(), width, length);
            if (ui.chunks) next = next.expandedToChunks();
            ui.setSelection(next, "resize");
        } else if (ui.dialog == Dialog.MOVE) {
            if (current == null) throw new IllegalArgumentException();
            long dx = Long.parseLong(ui.field(DialogField.OFFSET_X).getValue().trim());
            long dz = Long.parseLong(ui.field(DialogField.OFFSET_Z).getValue().trim());
            if (moveInChunks) {
                dx = Math.multiplyExact(dx, 16);
                dz = Math.multiplyExact(dz, 16);
            }
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
            ui.uiState.sizeFromCenter = sizeFromCenter;
            ui.uiState.save();
            anchorButton.setMessage(ui.tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        });
        anchorButton.setMessage(ui.tr(sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        moveUnitsButton = ui.button("units_blocks", () -> {
            moveInChunks = !moveInChunks;
            ui.uiState.moveInChunks = moveInChunks;
            ui.uiState.save();
            moveUnitsButton.setMessage(ui.tr(moveInChunks ? "units_chunks" : "units_blocks"));
        });
        moveUnitsButton.setMessage(ui.tr(moveInChunks ? "units_chunks" : "units_blocks"));
        centerCopy = ui.button("copy_center", this::copyCenter);
        centerCreate = ui.button("create_center_waypoint", this::createCenterWaypoint);
    }
}
