package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import xaero.map.controls.ControlsRegister;
import static dev.exactselection.ExactSelectionController.AREA_NAME_FIELD;
import static dev.exactselection.ExactSelectionController.HEX_FIELD;
import static dev.exactselection.ExactSelectionController.RENAME_FIELD;

/** Mouse and keyboard from Xaero's screen: each event goes to the window, panel or map tool it belongs to. */
final class InputRouter extends UiPart {
    InputRouter(ExactSelectionController ui) { super(ui); }

    boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        ui.shiftDown = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        ui.altDown = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        ui.checkedFrame();
        if (ui.dialog != Dialog.NONE) {
            for (AbstractWidget widget : ui.widgets) {
                if (widget.visible && widget.mouseClicked(event, twice)) {
                    // The click may have closed the widget's own window.
                    ui.updateVisibility();
                    if (widget.visible) ui.focus(widget);
                    return true;
                }
            }
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                int[] r = ui.windows.dialogRect();
                switch (ui.dialog) {
                    case COLOR -> ui.colorWindows.clickColorDialog(event, r[0], r[1], r[2], r[3]);
                    case PICKER -> ui.colorWindows.clickPicker(event, r[0], r[1], r[2], r[3]);
                    case IMPORT -> ui.transferWindow.clickImportRow(event, r[0], r[1], r[2], r[3]);
                    case AREA_NAME -> ui.newAreaWindow.clickAreaNameDialog(event, r[0], r[1], r[2], r[3]);
                    case AREAS -> ui.areasWindow.clickAreaRow(event, r[0], r[1], r[2], r[3]);
                    case GROUPS -> ui.groupsWindow.clickGroupRow(event, r[0], r[1], r[2], r[3]);
                    case ASSIGN_GROUP -> ui.groupsWindow.clickGroupChooser(event, r[0], r[1], r[2], r[3]);
                    case WAYPOINTS -> ui.waypointsWindow.clickWaypointRow(event, r[0], r[1], r[2], r[3]);
                    default -> {}
                }
            }
            return true;
        }
        if (ui.selectionPanel.toggle.mouseClicked(event, twice)) return true;
        if (!ui.enabled()) return false;
        if (ui.rightClickMenu.quickMenu) {
            for (Button item : ui.rightClickMenu.quickMenuButtons()) if (item.visible && item.mouseClicked(event, twice)) return true;
            ui.rightClickMenu.quickMenu = false;
            return true;
        }
        if (ui.selectionPanel.overPanel(event.x(), event.y())) {
            ui.focus(null);
            for (AbstractWidget widget : ui.widgets) {
                if (widget != ui.selectionPanel.toggle && widget.visible && widget.mouseClicked(event, twice)) {
                    ui.updateVisibility();
                    if (ui.enabled() && ui.waypointsWindow.pending == null && widget.visible) ui.focus(widget);
                    break;
                }
            }
            return true;
        }
        ui.focus(null);
        if (ui.overNativeWidget(event.x(), event.y())) return false;
        return ui.mapPointer.pressMap(event, twice);
    }

    boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && ui.dragging) {
            ui.dragging = false;
            // A right click that did not pan the map opens the quick actions of the selection or saved area under it.
            if (Math.hypot(event.x() - ui.mapPointer.rightPressX, event.y() - ui.mapPointer.rightPressY) < 4 && ui.enabled()
                    && ui.rightClickMenu.openQuickMenu(ui.checkedFrame(), event.x(), event.y())) return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && ui.colorWindows.pickerDrag != 0) {
            ui.colorWindows.dragPicker(event.x(), event.y());
            ui.colorWindows.pickerDrag = 0;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && ui.waypointsWindow.scrollbarDragging) {
            ui.waypointsWindow.dragWaypointScrollbar(event.y());
            ui.waypointsWindow.scrollbarDragging = false;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && ui.rulerTool.pressed) { ui.rulerTool.releaseRuler(event); return true; }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && ui.gesture instanceof MapGesture.Held held) { ui.mapPointer.releaseHeld(event, held); return true; }
        // Native release clears held toolbar buttons, even when released outside them.
        // Its map press flags stay false, so it cannot create a native map click.
        return false;
    }

    boolean mouseScrolled(double x, double y, double vertical) {
        // Zooming moves the selection away from the menu, which stays where it opened.
        ui.rightClickMenu.quickMenu = false;
        int step = vertical > 0 ? 1 : vertical < 0 ? -1 : 0;
        if (ui.dialog != Dialog.NONE) {
            scrollWindow(step);
            return true;
        }
        if (!ui.enabled() || !ui.selectionPanel.overPanel(x, y)) return false;
        if (!ui.selectionPanel.compact && step != 0) {
            ui.selectionPanel.panelScroll = scrolled(ui.selectionPanel.panelScroll, step * 20, ui.selectionPanel.maxPanelScroll(), 0);
            ui.selectionPanel.layoutMainPanel();
        }
        return true;
    }

    private void scrollWindow(int step) {
        int height = ui.windows.dialogRect()[3];
        WaypointsWindow points = ui.waypointsWindow;
        TransferWindow transfer = ui.transferWindow;
        AreasWindow areas = ui.areasWindow;
        GroupsWindow groups = ui.groupsWindow;
        boolean confirming = areas.areaDeleteConfirmation || groups.groupDeleteConfirmation;
        switch (ui.dialog) {
            case WAYPOINTS -> {
                if (points.pending != null)
                    points.modalScroll = scrolled(points.modalScroll, step, points.filteredPoints().size(), WaypointsWindow.waypointRows(height));
            }
            case IMPORT -> transfer.importScroll = scrolled(transfer.importScroll, step, transfer.importFiles.size(), TransferWindow.importRows(height));
            case AREAS -> {
                if (!confirming) areas.areaScroll = scrolled(areas.areaScroll, step, areas.displayedAreas().size(), areas.visibleAreaDialogRows(height));
            }
            case GROUPS -> {
                if (!confirming) groups.groupScroll = scrolled(groups.groupScroll, step, ui.groups().size(), groups.visibleGroupDialogRows(height));
            }
            case ASSIGN_GROUP -> {
                if (!confirming) groups.assignmentScroll = scrolled(groups.assignmentScroll, step, ui.groups().size() + 1, groups.visibleAssignmentRows(height));
            }
            default -> {}
        }
    }

    /** A list's position after one wheel step, kept within its rows beyond the ones that fit. */
    private static int scrolled(int scroll, int step, int rows, int fitting) {
        return Math.max(0, Math.min(rows - fitting, scroll - step));
    }

    void keyReleased(KeyEvent event) {
        ui.shiftDown = event.key() != GLFW.GLFW_KEY_LEFT_SHIFT && event.key() != GLFW.GLFW_KEY_RIGHT_SHIFT
                && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        ui.altDown = event.key() != GLFW.GLFW_KEY_LEFT_ALT && event.key() != GLFW.GLFW_KEY_RIGHT_ALT
                && (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
    }

    boolean keyPressed(KeyEvent event) {
        ui.shiftDown = event.key() == GLFW.GLFW_KEY_LEFT_SHIFT || event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT
                || (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        ui.altDown = event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT
                || (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        if (!ui.enabled()) return false;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            escape();
            return true;
        }
        boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        boolean editing = ui.screen.getFocused() instanceof EditBox field && ui.widgets.contains(field);
        if (ui.rulerTool.mode && ui.dialog == Dialog.NONE && !editing && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            ui.rulerTool.removeLastPoint();
            return true;
        }
        if (ui.polygonTool.mode && ui.dialog == Dialog.NONE && !editing && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            ui.polygonTool.removeLastPolygonPoint();
            return true;
        }
        if (ui.polygonTool.mode && !ui.holding() && ui.dialog == Dialog.NONE && !editing && enter) {
            ui.polygonTool.finishPolygon();
            return true;
        }
        // Other windows work on what they opened with; an undo would change it behind them.
        boolean undoable = ui.dialog == Dialog.NONE || ui.dialog == Dialog.AREAS || ui.dialog == Dialog.GROUPS || ui.dialog == Dialog.WAYPOINTS;
        if (!editing && undoable && (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            if (event.key() == GLFW.GLFW_KEY_Z) {
                if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) ui.redo(); else ui.undo();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_Y) { ui.redo(); return true; }
        }
        // Esc clears the selection step by step; the map key just closes the map, which keeps it.
        if (!editing && ui.dialog == Dialog.NONE && ControlsRegister.keyOpenMap.matches(event)) {
            ui.bridge.exactselection$closeMap();
            return true;
        }
        if (!editing && ui.dialog == Dialog.NONE) {
            // Each key presses its button, so it follows the same rules for when that is available.
            Button bound = ToolKeys.POLYGON.matches(event) ? ui.selectionPanel.polygonModeButton : ToolKeys.RULER.matches(event) ? ui.selectionPanel.rulerButton
                    : ToolKeys.AREAS.matches(event) ? ui.selectionPanel.areasButton : ToolKeys.UNITS.matches(event) ? ui.selectionPanel.chunkMode : null;
            if (bound != null) {
                if (bound.active) bound.onPress(event);
                // The compact panel has no units button to show the new value.
                if (bound == ui.selectionPanel.chunkMode) ui.message = ui.selectionPanel.chunkMode.getMessage().getString();
                return true;
            }
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            moveFocus((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            return true;
        }
        if (ui.screen.getFocused() instanceof EditBox field && ui.widgets.contains(field)) {
            if (enter) enterInField(field);
            else field.keyPressed(event);
        } else if (ui.screen.getFocused() instanceof AbstractWidget widget && ui.widgets.contains(widget)) {
            widget.keyPressed(event);
        }
        return true;
    }

    /** Esc takes one step back: what is held or open first, then the tool in use, then the selection, then the tool itself. */
    private void escape() {
        if (ui.rightClickMenu.quickMenu) ui.rightClickMenu.quickMenu = false;
        else if (ui.gesture instanceof MapGesture.VertexDrag) { ui.cancelGesture(); ui.say("draft_cancelled"); }
        else if (ui.gesture instanceof MapGesture.Drag) ui.cancelGesture();
        else if (ui.dialog != Dialog.NONE) ui.windows.cancelDialog();
        else if (ui.rulerTool.mode && ui.rulerTool.pointA != null) ui.rulerTool.clearRuler();
        else if (ui.rulerTool.mode) { ui.rulerTool.setRulerMode(false); ui.message = ""; }
        else if (ui.polygonTool.mode) {
            ui.polygonTool.discardDraft();
            ui.polygonTool.mode = false;
            ui.say("polygon_cancelled"); ui.updateSelection();
        }
        else if (ui.dirty) { ui.dirty = false; ui.syncFields(); ui.focus(null); }
        else if (ui.gesture instanceof MapGesture.Corners && ui.state.phase() == SelectionState.Phase.PREVIEW) ui.cancelGesture();
        else if (ui.state.bounds() != null) ui.reset();
        else ui.disable();
    }

    private void moveFocus(int step) {
        List<AbstractWidget> available = ui.widgets.stream().filter(w -> w.visible && w.active).toList();
        int index = available.indexOf(ui.screen.getFocused());
        if (!available.isEmpty()) ui.focus(available.get(index < 0
                ? step > 0 ? 0 : available.size() - 1
                : Math.floorMod(index + step, available.size())));
    }

    /** Enter in a text field does what its window is for. */
    private void enterInField(EditBox field) {
        if ((ui.dialog == Dialog.SIZE || ui.dialog == Dialog.MOVE || ui.dialog == Dialog.AREA_NAME || ui.dialog == Dialog.GROUP_NAME)
                && ui.modalApply.active) ui.windows.applyDialog();
        else if (ui.dialog == Dialog.AREAS && field == ui.dialogFields[AREA_NAME_FIELD])
            ui.areaEdits.commitDraft(ui.autosave.covers(AutosaveSettings.Field.NAME) ? f -> f == AutosaveSettings.Field.NAME : f -> true);
        else if (ui.dialog == Dialog.CENTER) ui.selectionDialogs.createCenterWaypoint();
        else if (ui.dialog == Dialog.COLOR && field == ui.dialogFields[HEX_FIELD]) ui.colorWindows.applyHexColor();
        else if (ui.dialog == Dialog.WAYPOINTS && field == ui.dialogFields[RENAME_FIELD] && ui.waypointsWindow.pointRename.active) ui.waypointsWindow.renamePoints();
        else if (ui.dialog == Dialog.NONE) ui.applyFields();
    }

    boolean charTyped(CharacterEvent event) {
        if (!ui.enabled()) return false;
        if (ui.screen.getFocused() instanceof EditBox field && ui.widgets.contains(field)) field.charTyped(event);
        return true;
    }
}
