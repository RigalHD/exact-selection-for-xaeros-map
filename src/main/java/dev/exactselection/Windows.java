package dev.exactselection;

import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.ModLog.LOG;
import static dev.exactselection.Theme.ACCENT;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.ExactSelectionController.DialogField;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** What all windows share: opening and closing them, their frame and title, and the shared buttons. */
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
        if (next == Dialog.AREAS) ui.areasWindow.areaSearch.setValue("");
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
            // The window opens with nothing checked; properties apply only to areas the user picks.
            ui.areasWindow.selectedAreaIds.clear();
            ui.areasWindow.areaId = null;
            ui.areasWindow.areaAnchorId = null;
            ui.areaEdits.areaDraft.clear();
            ui.areasWindow.autosaveOpen = false;
            ui.areasWindow.areaDeleteConfirmation =
                    ui.groupsWindow.groupDeleteConfirmation = ui.areasWindow.areaDeleteFromMenu = false;
            ui.setDialogValue(DialogField.AREA_NAME, "");
            ui.message = "";
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
            default -> null;
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
        ui.dialog = open != null
                ? open.returnTo()
                : switch (ui.dialog) {
                    case AREA_NAME -> ui.newAreaWindow.areaNameReturn;
                    case COLOR -> ui.colorWindows.colorReturn;
                    case PICKER -> ui.colorWindows.pickerReturn;
                    case ASSIGN_GROUP, IMPORT -> Dialog.AREAS;
                    case EXPORT -> ui.mapExport.exportReturn;
                    case GROUP_NAME -> Dialog.GROUPS;
                    case GROUPS -> ui.groupsWindow.groupsReturnDialog;
                    default -> Dialog.NONE;
                };
        ui.focus(null);
    }

    /** Where the open window sits: centred, and never larger than the screen allows. */
    Rect dialogRect() {
        Window open = window();
        if (open != null) {
            int width = Math.min(open.width(), ui.screen.width - 20);
            int height = Math.min(open.height(), ui.screen.height - 16);
            return new Rect((ui.screen.width - width) / 2, (ui.screen.height - height) / 2, width, height);
        }
        int width = Math.min(
                switch (ui.dialog) {
                    case AREAS, GROUPS -> 760;
                    case ASSIGN_GROUP, AREA_NAME, GROUP_NAME, COLOR, PICKER -> 420;
                    default -> 560;
                },
                ui.screen.width - 20);
        int height = switch (ui.dialog) {
            case SIZE, MOVE -> Math.min(180, ui.screen.height - 16);
            case CENTER -> Math.min(172, ui.screen.height - 16);
            case AREAS, GROUPS -> Math.min(460, ui.screen.height - 16);
            case ASSIGN_GROUP -> Math.min(330, ui.screen.height - 16);
            case AREA_NAME, PICKER -> Math.min(236, ui.screen.height - 16);
            case GROUP_NAME -> Math.min(140, ui.screen.height - 16);
            case COLOR -> Math.min(164, ui.screen.height - 16);
            case WAYPOINTS, IMPORT -> Math.min(330, ui.screen.height - 16);
            case EXPORT -> Math.min(ui.exportWindow.hasTargetArea() ? 242 : 220, ui.screen.height - 16);
            default -> 0;
        };
        return new Rect((ui.screen.width - width) / 2, (ui.screen.height - height) / 2, width, height);
    }

    void layoutDialog() {
        if (ui.dialog == Dialog.NONE) return;
        Rect r = dialogRect();
        int x = r.x(), y = r.y(), w = r.width(), h = r.height();
        int buttonY = y + h - 28;
        Window open = window();
        if (open != null) {
            open.layout(r, buttonY);
            return;
        }
        switch (ui.dialog) {
            case SIZE -> {
                place(ui.field(DialogField.WIDTH), x + 18, y + 52, (w - 54) / 2, 18);
                place(ui.field(DialogField.LENGTH), x + 36 + (w - 54) / 2, y + 52, (w - 54) / 2, 18);
                place(ui.selectionDialogs.anchorButton, x + 18, y + 82, w - 36, 18);
                place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case MOVE -> {
                place(ui.field(DialogField.OFFSET_X), x + 18, y + 52, (w - 54) / 2, 18);
                place(ui.field(DialogField.OFFSET_Z), x + 36 + (w - 54) / 2, y + 52, (w - 54) / 2, 18);
                place(ui.selectionDialogs.moveUnitsButton, x + 18, y + 82, w - 36, 18);
                place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case CENTER -> {
                place(ui.field(DialogField.CENTER_NAME), x + 18, y + 58, w - 36, 18);
                place(ui.selectionDialogs.centerCopy, x + 18, buttonY - 22, Math.max(70, (w - 48) / 2), 18);
                place(
                        ui.selectionDialogs.centerCreate,
                        x + 30 + (w - 48) / 2,
                        buttonY - 22,
                        Math.max(70, (w - 48) / 2),
                        18);
                place(ui.modalCancel, x + w / 2 - 55, buttonY, 110, 18);
            }
            case AREAS -> ui.areasWindow.layoutAreas(x, y, w, h, buttonY);
            case GROUPS -> ui.groupsWindow.layoutGroups(x, y, w, h, buttonY);
            case EXPORT -> {
                ui.exportWindow.layout(x, y, w, buttonY);
                place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case IMPORT -> {
                place(ui.managementClose, x + w - 82, y + 7, 70, 18);
                place(ui.transferWindow.importOpenFolder, x + 12, y + h - 28, 150, 18);
            }
            case ASSIGN_GROUP -> {
                place(ui.managementClose, x + w - 82, y + 7, 70, 18);
                place(ui.groupsWindow.manageGroups, x + 12, y + h - 36, w - 24, 18);
            }
            case AREA_NAME, GROUP_NAME -> {
                place(
                        ui.field(ui.dialog == Dialog.AREA_NAME ? DialogField.NEW_AREA : DialogField.GROUP_NAME),
                        x + 16,
                        y + 48,
                        w - 32,
                        18);
                if (ui.dialog == Dialog.AREA_NAME) ui.newAreaWindow.layoutNewArea(x, y, w);
                place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case PICKER -> {
                place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
                place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
            }
            case COLOR -> ui.colorWindows.layoutColorDialog(x, y, w, h, buttonY);
            case WAYPOINTS -> ui.waypointsWindow.layoutWaypoints(x, y, w, h, buttonY);
            default -> {}
        }
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
        if (open != null) {
            open.show();
            return;
        }
        if (ui.dialog == Dialog.SIZE)
            ui.show(
                    true,
                    ui.field(DialogField.WIDTH),
                    ui.field(DialogField.LENGTH),
                    ui.selectionDialogs.anchorButton,
                    ui.modalApply,
                    ui.modalCancel);
        if (ui.dialog == Dialog.MOVE)
            ui.show(
                    true,
                    ui.field(DialogField.OFFSET_X),
                    ui.field(DialogField.OFFSET_Z),
                    ui.selectionDialogs.moveUnitsButton,
                    ui.modalApply,
                    ui.modalCancel);
        if (ui.dialog == Dialog.CENTER)
            ui.show(
                    true,
                    ui.field(DialogField.CENTER_NAME),
                    ui.selectionDialogs.centerCopy,
                    ui.selectionDialogs.centerCreate,
                    ui.modalCancel);
        if (ui.dialog == Dialog.AREA_NAME) {
            ui.show(
                    true,
                    ui.field(DialogField.NEW_AREA),
                    ui.field(DialogField.HEX),
                    ui.newAreaWindow.newAreaGroup,
                    ui.newAreaWindow.newAreaOpacityButton,
                    ui.modalApply,
                    ui.modalCancel);
            // A route has nothing to fill.
            ui.newAreaWindow.newAreaOpacityButton.visible = ui.newAreaWindow.routeToSave == null;
        }
        if (ui.dialog == Dialog.COLOR) {
            // A swatch or Apply chooses the color; Cancel or Esc leaves without one.
            ui.show(true, ui.modalCancel);
            if (ui.colorWindows.colorReturn != Dialog.WAYPOINTS)
                ui.show(true, ui.field(DialogField.HEX), ui.colorWindows.colorHexApply);
        }
        if (ui.dialog == Dialog.GROUP_NAME)
            ui.show(true, ui.field(DialogField.GROUP_NAME), ui.modalApply, ui.modalCancel);
        if (ui.dialog == Dialog.PICKER) ui.show(true, ui.modalApply, ui.modalCancel);
        if (ui.dialog == Dialog.ASSIGN_GROUP) ui.show(true, ui.groupsWindow.manageGroups, ui.managementClose);
        if (ui.dialog == Dialog.AREAS) ui.areasWindow.showAreaWidgets();
        if (ui.dialog == Dialog.GROUPS) ui.groupsWindow.showGroupWidgets();
        if (ui.dialog == Dialog.IMPORT) ui.show(true, ui.managementClose, ui.transferWindow.importOpenFolder);
        if (ui.dialog == Dialog.EXPORT) {
            ui.exportWindow.show(ui.mapExport.exportRunning);
            ui.show(true, ui.modalCancel);
        }
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
        if (open != null) {
            if (open.titleOnLeft()) g.drawString(ui.mc.font, ui.tr(open.title()), x + 12, y + 12, ACCENT);
            else g.drawCenteredString(ui.mc.font, ui.tr(open.title()), x + w / 2, y + 10, ACCENT);
            open.render(g, r);
            return;
        }
        String title = switch (ui.dialog) {
            case SIZE -> "dialog_size";
            case MOVE -> "dialog_move";
            case CENTER -> "dialog_center";
            case AREAS -> "areas_window";
            case GROUPS -> "groups_window";
            case ASSIGN_GROUP -> "group_picker_title";
            case AREA_NAME -> ui.newAreaWindow.routeToSave != null ? "route_new_title" : "area_new_title";
            case GROUP_NAME -> ui.groupsWindow.renamingGroup ? "group_rename_title" : "group_new_title";
            case WAYPOINTS -> "dialog_waypoints";
            case COLOR -> "color_title";
            case PICKER -> "picker_title";
            case IMPORT -> "import_title";
            case EXPORT -> "export_title";
            default -> "title";
        };
        if (ui.dialog == Dialog.AREAS
                || ui.dialog == Dialog.GROUPS
                || ui.dialog == Dialog.ASSIGN_GROUP
                || ui.dialog == Dialog.IMPORT) g.drawString(ui.mc.font, ui.tr(title), x + 12, y + 12, ACCENT);
        else g.drawCenteredString(ui.mc.font, ui.tr(title), x + w / 2, y + 10, ACCENT);
        switch (ui.dialog) {
            case SIZE -> {
                g.drawString(ui.mc.font, ui.tr("width_field"), x + 18, y + 38, -1);
                g.drawString(ui.mc.font, ui.tr("length_field"), x + 36 + (w - 54) / 2, y + 38, -1);
                SelectionBounds b = ui.state.bounds();
                if (b != null)
                    g.drawString(
                            ui.mc.font,
                            ui.tr(
                                    "current_anchor",
                                    SelectionDialogs.formatNumber(
                                            ui.selectionDialogs.sizeFromCenter ? b.centerX() : b.minX()),
                                    SelectionDialogs.formatNumber(
                                            ui.selectionDialogs.sizeFromCenter ? b.centerZ() : b.maxZ())),
                            x + 18,
                            y + 108,
                            Theme.TEXT_MUTED);
                g.drawString(ui.mc.font, ui.tr("lower_left_axes"), x + 18, y + 124, Theme.TEXT_MUTED);
            }
            case MOVE -> {
                g.drawString(ui.mc.font, ui.tr("offset_x"), x + 18, y + 38, -1);
                g.drawString(ui.mc.font, ui.tr("offset_z"), x + 36 + (w - 54) / 2, y + 38, -1);
                g.drawString(ui.mc.font, ui.tr("move_note"), x + 18, y + 108, Theme.TEXT_MUTED);
            }
            case CENTER -> {
                SelectionBounds b = ui.state.bounds();
                g.drawString(
                        ui.mc.font,
                        b == null
                                ? ui.tr("no_selection")
                                : ui.tr(
                                        "center_value",
                                        SelectionDialogs.formatNumber(b.centerX()),
                                        SelectionDialogs.formatNumber(b.centerZ())),
                        x + 18,
                        y + 34,
                        -1);
                g.drawString(ui.mc.font, ui.tr("center_waypoint_y"), x + 18, y + 82, Theme.TEXT_MUTED);
            }
            case AREAS -> ui.areasWindow.renderAreasDialog(g, x, y, w, h);
            case GROUPS -> ui.groupsWindow.renderGroupsDialog(g, x, y, w, h);
            case ASSIGN_GROUP -> ui.groupsWindow.renderGroupChooser(g, x, y, w, h);
            case IMPORT -> ui.transferWindow.renderImportDialog(g, x, y, w, h);
            case EXPORT -> ui.exportWindow.render(g, x, y, w, ui.message);
            case AREA_NAME, GROUP_NAME -> {
                String label = ui.dialog == Dialog.GROUP_NAME
                        ? "group_name"
                        : ui.newAreaWindow.routeToSave != null ? "route_name" : "area_name";
                g.drawString(ui.mc.font, ui.tr(label), x + 16, y + 32, -1);
                if (ui.dialog == Dialog.AREA_NAME) ui.newAreaWindow.renderNewArea(g, x, y, w);
                if (!ui.message.isEmpty())
                    g.drawString(
                            ui.mc.font,
                            ui.mc.font.plainSubstrByWidth(ui.message, w - 32),
                            x + 16,
                            y + (ui.dialog == Dialog.AREA_NAME ? 194 : 80),
                            Theme.WARNING);
            }
            case WAYPOINTS -> ui.waypointsWindow.renderWaypointsDialog(g, x, y, w, h);
            case COLOR -> ui.colorWindows.renderColorDialog(g, x, y, w, h);
            case PICKER -> ui.colorWindows.renderPicker(g, x, y, w, h);
            default -> {}
        }
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
