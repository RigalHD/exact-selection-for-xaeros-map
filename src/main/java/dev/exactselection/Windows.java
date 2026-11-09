package dev.exactselection;

import static dev.exactselection.ModLog.LOG;
import static dev.exactselection.Theme.ACCENT;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.ExactSelectionController.DialogField;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * What all windows share: opening and closing them, their frame and title, and the shared buttons. Each window
 * describes itself in a {@link Window}; {@link #window()} is the one place that lists them.
 */
final class Windows extends UiPart {
    Windows(ExactSelectionController ui) {
        super(ui);
    }

    void openDialog(Dialog next) {
        if (ui.dirty || !ui.polygonTool.draft.isEmpty() || ui.holding() || ui.waypointsWindow.pending != null) return;
        ui.polygonTool.draftUndo.clear();
        ui.polygonTool.draftRedo.clear();
        ui.polygonTool.stateBefore = null;
        ui.dialog = next;
        if (next == Dialog.SIZE) {
            SelectionBounds b = ui.state.bounds();
            ui.setDialogValue(DialogField.WIDTH, b == null ? "1" : Long.toString(b.width()));
            ui.setDialogValue(DialogField.LENGTH, b == null ? "1" : Long.toString(b.length()));
        } else if (next == Dialog.MOVE) {
            ui.setDialogValue(DialogField.OFFSET_X, "0");
            ui.setDialogValue(DialogField.OFFSET_Z, "0");
        } else if (next == Dialog.CENTER) {
            ui.setDialogValue(
                    DialogField.CENTER_NAME, ui.tr("center_waypoint_default").getString());
        } else if (next == Dialog.AREAS) {
            ui.areasWindow.resetForOpening();
        }
        if (next == Dialog.SIZE)
            ui.selectionDialogs.anchorButton.setMessage(
                    ui.tr(ui.selectionDialogs.sizeFromCenter ? "anchor_center" : "anchor_lower_left"));
        if (next == Dialog.MOVE)
            ui.selectionDialogs.moveUnitsButton.setMessage(
                    ui.tr(ui.selectionDialogs.moveInChunks ? "units_chunks" : "units_blocks"));
        ui.focus(null);
    }

    /** The open window when it has been moved to {@link Window}; null while the switches below still describe it. */
    Window window() {
        return switch (ui.dialog) {
            case SIZE -> ui.selectionDialogs.sizeWindow;
            case AREAS -> ui.areasWindow.window;
            case MOVE -> ui.selectionDialogs.moveWindow;
            case CENTER -> ui.selectionDialogs.centerWindow;
            case GROUP_NAME -> ui.groupsWindow.nameWindow;
            case AREA_NAME -> ui.newAreaWindow.window;
            case PICKER -> ui.colorWindows.pickerWindow;
            case COLOR -> ui.colorWindows.colorWindow;
            case IMPORT -> ui.transferWindow.importWindow;
            case ASSIGN_GROUP -> ui.groupsWindow.chooserWindow;
            case GROUPS -> ui.groupsWindow.window;
            case WAYPOINTS -> ui.waypointsWindow.window;
            case EXPORT -> ui.mapExport.window;
            case NONE -> null;
        };
    }

    void cancelDialog() {
        if (ui.dialog == Dialog.AREAS && ui.areasWindow.areaDeleteConfirmation) {
            ui.areasWindow.areaDeleteConfirmation = false;
            if (ui.areasWindow.areaDeleteFromMenu) {
                ui.areasWindow.areaDeleteFromMenu = false;
                ui.dialog = Dialog.NONE;
            }
            ui.message = "";
            ui.focus(null);
            return;
        }
        if (ui.dialog == Dialog.GROUPS && ui.groupsWindow.groupDeleteConfirmation) {
            ui.groupsWindow.groupDeleteConfirmation = false;
            ui.focus(null);
            return;
        }
        if (ui.dialog == Dialog.WAYPOINTS) ui.waypointsWindow.pending = null;
        if (ui.dialog == Dialog.AREAS) ui.areaEdits.flushDraft();
        Window open = window();
        ui.dialog = open == null ? Dialog.NONE : open.returnTo();
        ui.focus(null);
    }

    /** Where the open window sits: centred, and never larger than the screen allows. */
    Rect dialogRect() {
        Window open = window();
        int width = Math.min(open == null ? 560 : open.width(), ui.screen.width - 20);
        int height = open == null ? 0 : Math.min(open.height(), ui.screen.height - 16);
        return new Rect((ui.screen.width - width) / 2, (ui.screen.height - height) / 2, width, height);
    }

    void layoutDialog() {
        if (ui.dialog == Dialog.NONE) return;
        Rect r = dialogRect();
        int y = r.y(), h = r.height();
        int buttonY = y + h - 28;
        window().layout(r, buttonY);
    }

    /** The fields and buttons every window shares. */
    void createWindowWidgets() {
        ui.confirm = ui.button("confirm", this::confirmDelete);
        ui.cancel = ui.button("cancel", this::cancelDialog);
        for (DialogField kind : DialogField.values()) {
            EditBox field = new EditBox(ui.mc.font, 0, 0, 100, 18, Component.empty());
            field.setMaxLength(kind.maxLength);
            if (kind == DialogField.HEX) field.setHint(Component.literal("#RRGGBB"));
            ui.dialogFields[kind.ordinal()] = field;
            ui.widgets.add(field);
        }
        ui.modalApply = ui.button("apply_short", this::applyDialog);
        ui.modalCancel = ui.button("cancel", this::cancelDialog);
        ui.managementClose = ui.button("done", this::cancelDialog);
        ui.managementUndo = ui.arrowButton("↶", ui::undo);
        ui.managementRedo = ui.arrowButton("↷", ui::redo);
    }

    /** Which widgets the open window shows; the Areas and Groups windows decide for themselves. */
    void showWindowWidgets() {
        Window open = window();
        if (open != null) open.show();
    }

    /** The shared buttons' labels and states for the open window. */
    void updateWindowWidgets() {
        if (ui.areasWindow.areaDeleteConfirmation && ui.dialog == Dialog.AREAS
                || ui.groupsWindow.groupDeleteConfirmation && ui.dialog == Dialog.GROUPS) {
            ui.confirm.setMessage(ui.tr(
                    ui.groupsWindow.groupDeleteConfirmation
                            ? "group_delete_confirm_button"
                            : "area_delete_confirm_button"));
            ui.cancel.setMessage(ui.tr("cancel"));
            ui.confirm.active = (ui.groupsWindow.groupDeleteConfirmation
                            ? ui.groupsWindow.selectedGroup() != null
                            : !ui.areasWindow.selectedAreas().isEmpty())
                    && !ui.loadFailed;
        }
        ui.selectionDialogs.centerCopy.active = ui.state.bounds() != null;
        ui.selectionDialogs.centerCreate.active =
                ui.state.phase() == SelectionState.Phase.SELECTED && ui.waypoints != null;
        ui.managementUndo.active = ui.history.canUndo();
        ui.managementRedo.active = ui.history.canRedo();
        ui.managementClose.setMessage(ui.tr(
                ui.dialog == Dialog.AREAS
                                || ui.dialog == Dialog.GROUPS && ui.groupsWindow.groupsReturnDialog == Dialog.NONE
                        ? "back_to_map"
                        : "back"));
        ui.modalCancel.setMessage(ui.tr("cancel"));
        ui.modalApply.setMessage(ui.tr(
                ui.dialog == Dialog.AREA_NAME
                        ? ui.newAreaWindow.routeToSave != null ? "route_create_button" : "area_create_button"
                        : ui.dialog == Dialog.GROUP_NAME
                                ? ui.groupsWindow.renamingGroup ? "save_name" : "group_create"
                                : "apply_short"));
        ui.modalApply.active = switch (ui.dialog) {
            case AREA_NAME -> !ui.field(DialogField.NEW_AREA).getValue().trim().isEmpty() && !ui.loadFailed;
            case GROUP_NAME ->
                !ui.field(DialogField.GROUP_NAME).getValue().trim().isEmpty() && !ui.loadFailed;
            default -> true;
        };
    }

    void applyDialog() {
        if (ui.dialog == Dialog.AREA_NAME) {
            ui.newAreaWindow.saveArea();
            return;
        }
        if (ui.dialog == Dialog.PICKER) {
            ui.colorWindows.applyPicker();
            return;
        }
        if (ui.dialog == Dialog.GROUP_NAME) {
            if (ui.groupsWindow.renamingGroup) ui.groupsWindow.renameGroup();
            else ui.groupsWindow.createGroup();
            return;
        }
        try {
            ui.selectionDialogs.applySizeOrMove();
            ui.dialog = Dialog.NONE;
            ui.focus(null);
            ui.message = "";
        } catch (IllegalArgumentException | ArithmeticException e) {
            ui.say("invalid_dimensions");
        } catch (Exception e) {
            ui.say("invalid_dimensions");
            LOG.error("Could not apply the size or move window", e);
        }
    }

    void renderDialog(GuiGraphics g) {
        Rect r = dialogRect();
        int x = r.x(), y = r.y(), w = r.width(), h = r.height();
        g.nextStratum();
        g.fill(0, 0, ui.screen.width, ui.screen.height, 0xC0080D12);
        g.fill(x, y, x + w, y + h, 0xFF141E29);
        g.renderOutline(x, y, w, h, Theme.BORDER);
        Window open = window();
        if (open.titleOnLeft()) g.drawString(ui.mc.font, ui.tr(open.title()), x + 12, y + 12, ACCENT);
        else g.drawCenteredString(ui.mc.font, ui.tr(open.title()), x + w / 2, y + 10, ACCENT);
        open.render(g, r);
    }

    private void confirmDelete() {
        if (ui.dialog == Dialog.GROUPS && ui.groupsWindow.groupDeleteConfirmation) {
            ui.groupsWindow.confirmGroupDelete();
            return;
        }
        if (ui.dialog == Dialog.AREAS && ui.areasWindow.areaDeleteConfirmation) {
            ui.areasWindow.confirmAreaDelete();
            return;
        }
        if (ui.dialog == Dialog.WAYPOINTS) ui.waypointsWindow.confirmPointDelete();
    }
}
