package dev.exactselection;

import dev.exactselection.ExactSelectionController.Dialog;
import dev.exactselection.ExactSelectionController.DialogField;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import dev.exactselection.SavedAreaStore.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import static dev.exactselection.Theme.ACCENT;
import static dev.exactselection.ExactSelectionController.place;
import static dev.exactselection.SelectionPanel.formatLength;

/** The Groups window, the window for naming a group and the chooser that assigns checked areas to one. */
final class GroupsWindow extends UiPart {
    private Button groupCreate, groupRename, groupVisibility, groupDelete;
    Button manageGroups;
    boolean groupDeleteConfirmation;
    boolean renamingGroup;
    Dialog groupsReturnDialog = Dialog.NONE;
    private final Set<String> assignmentTargets = new LinkedHashSet<>();
    int assignmentScroll;
    String groupId;
    int groupScroll;
    private State indexedState;
    private Map<String, Group> groupsById = Map.of();
    /** How many areas a set holds and the sum of their areas. */
    private record Totals(int count, double area) {}

    private Map<String, Totals> totals = new HashMap<>();

    GroupsWindow(ExactSelectionController ui) { super(ui); }

    private void openGroupsWindow(Dialog returnTo) {
        if (ui.dirty || ui.waypointsWindow.pending != null || !ui.polygonTool.draft.isEmpty() || ui.holding()) return;
        ui.polygonTool.draftUndo.clear(); ui.polygonTool.draftRedo.clear();
        ui.polygonTool.stateBefore = null;
        groupsReturnDialog = returnTo;
        ui.dialog = Dialog.GROUPS;
        groupDeleteConfirmation = false;
        if (selectedGroup() == null && !ui.groups().isEmpty())
            groupId = ui.groups().get(0).id();
        ui.message = "";
        ui.focus(null);
    }

    private void openGroupNameDialog(boolean rename) {
        if (ui.loadFailed || rename && selectedGroup() == null) return;
        renamingGroup = rename;
        ui.dialog = Dialog.GROUP_NAME;
        ui.setDialogValue(DialogField.GROUP_NAME, rename ? selectedGroup().name() : "");
        ui.message = "";
        ui.focus(ui.field(DialogField.GROUP_NAME));
    }

    void openGroupChooser() {
        if (ui.areasWindow.selectedAreaIds.isEmpty() || ui.loadFailed) return;
        assignmentTargets.clear();
        assignmentTargets.addAll(ui.areasWindow.selectedAreaIds);
        assignmentScroll = 0;
        ui.dialog = Dialog.ASSIGN_GROUP;
        ui.message = "";
        ui.focus(null);
    }

    int visibleGroupDialogRows(int height) {
        return Math.max(1, (height - 100) / 20);
    }

    int visibleAssignmentRows(int height) {
        return Math.max(1, (height - 110) / 20);
    }

    Group selectedGroup() {
        return findGroup(groupId);
    }

    /** Asked for every area in every frame, so the groups of the current state are indexed once. */
    Group findGroup(String id) {
        State state = ui.areaStore.state();
        if (state != indexedState) {
            indexedState = state;
            groupsById = new HashMap<>();
            for (Group group : state.groups()) groupsById.put(group.id(), group);
            totals = new HashMap<>();
        }
        return id == null ? null : groupsById.get(id);
    }

    /** Member count and total area of a group ({@code null}: the areas in no group), counted once per state. */
    private Totals totals(String groupId) {
        findGroup(null);
        return totals.computeIfAbsent(groupId == null ? "" : groupId, key -> {
            List<Area> members = groupAreas(groupId);
            return new Totals(members.size(), totalArea(members));
        });
    }

    /** Import matches groups by name, so two groups cannot share one; {@code except} is the group being renamed. */
    private boolean nameTaken(String name, String except) {
        boolean taken = ui.groups().stream().anyMatch(g -> !g.id().equals(except) && g.name().equalsIgnoreCase(name));
        if (taken) ui.say("group_name_taken", name);
        return taken;
    }

    void createGroup() {
        String name = ui.field(DialogField.GROUP_NAME).getValue().trim();
        if (name.isEmpty() || ui.loadFailed || nameTaken(name, null)) return;
        List<Group> groups = new ArrayList<>(ui.groups());
        Group group = new Group(UUID.randomUUID().toString(), name, true);
        groups.add(group);
        if (!ui.areaEdits.changeAreas(ui.areaEdits.areaState(ui.areas(), groups), "group_create")) return;
        groupId = group.id();
        ui.dialog = Dialog.GROUPS;
        groupScroll = Math.max(0, groups.size() - visibleGroupDialogRows(ui.windows.dialogRect().height()));
        ui.say("group_created", group.name());
        ui.focus(null);
    }

    void renameGroup() {
        Group current = selectedGroup();
        String name = ui.field(DialogField.GROUP_NAME).getValue().trim();
        if (current == null || name.isEmpty() || ui.loadFailed || nameTaken(name, current.id())) return;
        List<Group> groups = new ArrayList<>(ui.groups());
        groups.replaceAll(group -> group.id().equals(current.id())
                ? new Group(group.id(), name, group.visible()) : group);
        State next = ui.areaEdits.areaState(ui.areas(), groups);
        if (!ui.areaStore.state().equals(next) && !ui.areaEdits.changeAreas(next, "group_rename")) return;
        ui.dialog = Dialog.GROUPS;
        ui.say("group_renamed");
        ui.focus(null);
    }

    private void assignGroup(String nextGroupId) {
        if (ui.dialog != Dialog.ASSIGN_GROUP || assignmentTargets.isEmpty() || ui.loadFailed) return;
        Group group = findGroup(nextGroupId);
        if (nextGroupId != null && group == null) return;
        int count = assignmentTargets.size();
        ui.areaEdits.areaDraft.groupSet = true; ui.areaEdits.areaDraft.groupId = nextGroupId;
        ui.dialog = Dialog.AREAS;
        assignmentTargets.clear();
        ui.say(ui.autosave.covers(AutosaveSettings.Field.GROUP) ? "group_assigned" : "group_staged",
                count, group == null ? ui.tr("group_none").getString() : group.name());
        ui.focus(null);
        ui.areaEdits.stage(AutosaveSettings.Field.GROUP);
    }

    private void toggleGroup() {
        Group g = selectedGroup(); if (g == null || ui.loadFailed) return;
        List<Group> groups = new ArrayList<>(ui.groups());
        groups.replaceAll(v -> v.id().equals(g.id()) ? new Group(v.id(), v.name(), !v.visible()) : v);
        if (ui.areaEdits.changeAreas(ui.areaEdits.areaState(ui.areas(), groups), "group_toggle"))
            ui.say(g.visible() ? "group_hidden" : "group_shown", g.name());
    }

    private void askDeleteGroup() {
        if (selectedGroup() == null || ui.loadFailed) return;
        groupDeleteConfirmation = true;
        ui.focus(ui.confirm);
    }

    void confirmGroupDelete() {
        Group group = selectedGroup();
        if (group == null || !groupDeleteConfirmation || ui.loadFailed) return;
        List<Group> groups = ui.groups().stream()
                .filter(g -> !g.id().equals(group.id())).toList();
        List<Area> areas = ui.areas().stream().map(area ->
                Objects.equals(area.groupId(), group.id()) ? area.withGroup(null) : area).toList();
        int affected = (int) ui.areas().stream().filter(a -> Objects.equals(a.groupId(), group.id())).count();
        if (!ui.areaEdits.changeAreas(ui.areaEdits.areaState(areas, groups), "group_delete")) return;
        groupId = null;
        ui.say("group_deleted", affected);
        groupDeleteConfirmation = false;
        ui.focus(null);
    }

    void renderGroupsDialog(GuiGraphics g, int x, int y, int w, int h) {
        if (groupDeleteConfirmation) {
            Group group = selectedGroup();
            int count = group == null ? 0 : (int) ui.areas().stream()
                    .filter(area -> Objects.equals(area.groupId(), group.id())).count();
            String name = group == null ? "" : ui.mc.font.plainSubstrByWidth(group.name(), w / 3);
            g.drawCenteredString(ui.mc.font, ui.tr("group_delete_confirm", name, count), x + w / 2, y + h / 2 - 12, -1);
            g.drawCenteredString(ui.mc.font, ui.tr("group_delete_ungroup_note"), x + w / 2, y + h / 2 + 6, Theme.TEXT_MUTED);
            return;
        }
        int col = (w - 36) / 2, right = x + w - col - 12;
        List<Group> groups = ui.groups();
        int rows = visibleGroupDialogRows(h);
        groupScroll = Math.min(groupScroll, Math.max(0, groups.size() - rows));
        g.drawString(ui.mc.font, ui.tr("groups_list_title"), x + 12, y + 34, Theme.TEXT_MUTED);
        g.fill(x + 10, y + 46, x + 14 + col, y + h - 52, Theme.LIST_BACKGROUND);
        if (groups.isEmpty()) g.drawString(ui.mc.font, ui.tr("groups_empty"), x + 16, y + 54, Theme.TEXT_MUTED);
        for (int i = 0; i < rows && i + groupScroll < groups.size(); i++) {
            Group group = groups.get(i + groupScroll);
            int rowY = y + 48 + i * 20;
            if (Objects.equals(group.id(), groupId)) g.fill(x + 10, rowY, x + 14 + col, rowY + 20, Theme.ROW_CHECKED);
            g.fill(x + 14, rowY + 5, x + 22, rowY + 13, group.visible() ? ACCENT : Theme.INACTIVE);
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(group.name(), col - 22), x + 28, rowY + 1, -1);
            Totals members = totals(group.id());
            String details = ui.tr(group.visible() ? "group_state_shown" : "group_state_hidden").getString()
                    + " · " + ui.tr("group_member_count", members.count()).getString()
                    + " · " + ui.tr("group_area_total", formatLength(members.area())).getString();
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(details, col - 22), x + 28, rowY + 11, Theme.TEXT_MUTED);
        }
        Group group = selectedGroup();
        g.drawString(ui.mc.font, ui.tr("group_selected"), right, y + 34, Theme.TEXT_MUTED);
        g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(group == null ? ui.tr("group_not_selected").getString() : group.name(), col), right, y + 48, -1);
        // Below the group buttons, while the window is tall enough to leave them clear of the export notice.
        if (h >= 264) {
            Totals ungrouped = totals(null);
            double all = totals.computeIfAbsent("\u0000all", key -> new Totals(ui.areas().size(), totalArea(ui.areas()))).area();
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.tr("groups_total", ui.areas().size(),
                    formatLength(all)).getString(), col), right, y + 194, Theme.TEXT_MUTED);
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.tr("groups_ungrouped", ungrouped.count(),
                    formatLength(ungrouped.area())).getString(), col), right, y + 206, Theme.TEXT_MUTED);
        }
        ui.areasWindow.renderManagementStatus(g, x, y, w, h, "groups_window_hint");
    }

    /** The areas of one group, or with {@code groupId} null those in no group. */
    private List<Area> groupAreas(String groupId) {
        return ui.areas().stream().filter(area -> Objects.equals(area.groupId(), groupId)).toList();
    }

    /** The sum of the areas' own areas; overlapping parts count once per area. */
    static double totalArea(List<Area> areas) {
        return areas.stream().mapToDouble(area -> area.geometry().area()).sum();
    }

    void renderGroupChooser(GuiGraphics g, int x, int y, int w, int h) {
        g.drawString(ui.mc.font, ui.tr("group_picker_count", assignmentTargets.size()), x + 12, y + 32, Theme.TEXT_MUTED);
        int rows = visibleAssignmentRows(h), count = ui.groups().size() + 1;
        assignmentScroll = Math.min(assignmentScroll, Math.max(0, count - rows));
        List<Area> targets = ui.areas().stream().filter(a -> assignmentTargets.contains(a.id())).toList();
        for (int i = 0; i < rows && i + assignmentScroll < count; i++) {
            int index = i + assignmentScroll;
            Group group = index == 0 ? null : ui.groups().get(index - 1);
            String id = group == null ? null : group.id();
            int rowY = y + 48 + i * 20;
            if (ui.areaEdits.areaDraft.groupSet ? Objects.equals(ui.areaEdits.areaDraft.groupId, id)
                    : !targets.isEmpty() && targets.stream().allMatch(a -> Objects.equals(a.groupId(), id)))
                g.fill(x + 10, rowY, x + w - 10, rowY + 20, Theme.ROW_CHECKED);
            String name = group == null ? ui.tr("group_none").getString() : group.name();
            if (group != null && !group.visible()) name += " · " + ui.tr("group_state_hidden").getString();
            g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(name, w - 32), x + 16, rowY + 5, -1);
        }
        if (!ui.message.isEmpty()) ui.areasWindow.renderManagementStatus(g, x, y, w, h, "group_picker_tooltip");
    }

    void clickGroupRow(MouseButtonEvent event, int x, int y, int w, int h) {
        if (groupDeleteConfirmation) return;
        int col = (w - 36) / 2;
        int rows = visibleGroupDialogRows(h);
        if (event.x() >= x + 10 && event.x() < x + 14 + col
                && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
            int index = groupScroll + (int) ((event.y() - y - 48) / 20);
            if (index < ui.groups().size()) {
                groupId = ui.groups().get(index).id();
                if (event.x() < x + 26) toggleGroup();
                ui.focus(null);
            }
        }
    }

    void clickGroupChooser(MouseButtonEvent event, int x, int y, int w, int h) {
        int rows = visibleAssignmentRows(h);
        if (event.x() >= x + 10 && event.x() < x + w - 10
                && event.y() >= y + 48 && event.y() < y + 48 + rows * 20) {
            int index = assignmentScroll + (int) ((event.y() - y - 48) / 20);
            if (index <= ui.groups().size())
                assignGroup(index == 0 ? null : ui.groups().get(index - 1).id());
        }
    }

    void layoutGroups(int x, int y, int w, int h, int buttonY) {
        if (groupDeleteConfirmation) {
            place(ui.confirm, x + w / 2 - 116, buttonY, 110, 18);
            place(ui.cancel, x + w / 2 + 6, buttonY, 110, 18);
            return;
        }
        int col = (w - 36) / 2, right = x + w - col - 12, half = (col - 3) / 2;
        place(ui.managementUndo, x + w - 154, y + 7, 22, 18);
        place(ui.managementRedo, x + w - 129, y + 7, 22, 18);
        place(ui.managementClose, x + w - 102, y + 7, 90, 18);
        place(groupCreate, right, y + 78, col, 18);
        place(groupRename, right, y + 100, col, 18);
        place(groupVisibility, right, y + 122, col, 18);
        place(groupDelete, right, y + 144, col, 18);
        place(ui.transferWindow.groupExport, right, y + 166, col - 21, 18);
        place(ui.transferWindow.transferFolderButton, right + col - 18, y + 166, 18, 18);
        place(ui.mapExport.openExportButton, right, y + h - 44, col, 16);
    }

    void showGroupWidgets() {
        if (groupDeleteConfirmation) ui.show(true, ui.confirm, ui.cancel);
        else {
            ui.show(true, ui.managementClose, ui.managementUndo, ui.managementRedo, groupCreate, groupRename, groupVisibility,
                    groupDelete, ui.transferWindow.groupExport, ui.transferWindow.transferFolderButton);
            ui.show(ui.mapExport.hasExportNotice(), ui.mapExport.openExportButton);
        }
    }

    void updateGroupWidgets() {
        Group group = selectedGroup();
        groupCreate.active = !ui.loadFailed;
        groupRename.active = groupVisibility.active = groupDelete.active = group != null && !ui.loadFailed;
        ui.transferWindow.groupExport.active = group != null && !ui.loadFailed
                && ui.areas().stream().anyMatch(a -> Objects.equals(a.groupId(), group.id()));
        groupVisibility.setMessage(ui.tr(group != null && group.visible() ? "group_hide" : "group_show"));
    }

    void createGroupWidgets() {
        groupCreate = ui.button("group_create", () -> openGroupNameDialog(false));
        groupRename = ui.button("group_rename_action", () -> openGroupNameDialog(true));
        groupVisibility = ui.button("group_toggle", "group_toggle_tooltip", this::toggleGroup);
        manageGroups = ui.button("groups_manage", () -> openGroupsWindow(ui.dialog == Dialog.ASSIGN_GROUP ? Dialog.ASSIGN_GROUP : Dialog.AREAS));
        groupDelete = ui.button("group_delete", "group_delete_tooltip", this::askDeleteGroup);
    }
}
