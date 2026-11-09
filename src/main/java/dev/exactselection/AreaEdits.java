package dev.exactselection;

import static dev.exactselection.ModLog.LOG;

import dev.exactselection.ExactSelectionController.DialogField;
import dev.exactselection.SavedAreaStore.Area;
import dev.exactselection.SavedAreaStore.Group;
import dev.exactselection.SavedAreaStore.State;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Writes to the stored areas, each one undo step, and the unsaved edits of the checked areas. */
final class AreaEdits extends UiPart {
    final AreaDraft areaDraft = new AreaDraft();

    AreaEdits(ExactSelectionController ui) {
        super(ui);
    }

    /**
     * Writes the edited territory's new shape at once and folds it into the selection step, so one undo restores
     * both the selection and the saved area.
     */
    SelectionHistory.Action withGeometryAutosave(SelectionHistory.Action selection, Area area, AreaGeometry geometry) {
        State before = ui.areaStore.state();
        State next = areaState(
                before.areas().stream()
                        .map(a -> a.id().equals(area.id()) ? a.withGeometry(geometry) : a)
                        .toList(),
                before.groups());
        try {
            ui.areaStore.replace(next);
        } catch (Exception e) {
            ui.say("area_save_failed");
            LOG.error("Could not autosave the edited territory", e);
            return selection;
        }
        return new SelectionHistory.Action() {
            @Override
            public void undo() throws Exception {
                ui.areaStore.replace(before);
                ui.areasWindow.syncAreaSelection();
                selection.undo();
            }

            @Override
            public void redo() throws Exception {
                ui.areaStore.replace(next);
                ui.areasWindow.syncAreaSelection();
                selection.redo();
            }

            @Override
            public String label() {
                return selection.label();
            }
        };
    }

    boolean changeAreas(State requested, String label) {
        State before = ui.areaStore.state();
        if (before.equals(requested)) return false;
        if (requested.areas().size() > SavedAreaStore.MAX_AREAS
                || requested.groups().size() > SavedAreaStore.MAX_GROUPS) {
            ui.say("areas_limit", SavedAreaStore.MAX_AREAS, SavedAreaStore.MAX_GROUPS);
            return false;
        }
        // The first area of a world switches the map overlay on, or it would vanish once the tool is turned off.
        State next = before.areas().isEmpty() && !requested.areas().isEmpty()
                ? new State(
                        requested.areas(), requested.groups(), true, requested.showOnMinimap(), requested.showNames())
                : requested;
        try {
            // Unsaved name, group, color and fill edits survive this write; undo and redo drop them in
            // syncAreaSelection.
            ui.areaStore.replace(next);
            ui.history.recordApplied(new SelectionHistory.Action() {
                @Override
                public void undo() throws Exception {
                    ui.areaStore.replace(before);
                    ui.areasWindow.syncAreaSelection();
                }

                @Override
                public void redo() throws Exception {
                    ui.areaStore.replace(next);
                    ui.areasWindow.syncAreaSelection();
                }

                @Override
                public String label() {
                    return ui.tr("history_" + label).getString();
                }
            });
            return true;
        } catch (Exception e) {
            ui.say("area_save_failed");
            LOG.error("Could not save saved areas", e);
            return false;
        }
    }

    /** Writes the draft fields chosen by {@code fields} to every checked area as one undo step. */
    boolean commitDraft(Predicate<AutosaveSettings.Field> fields) {
        if (ui.loadFailed || ui.areasWindow.selectedAreaIds.isEmpty()) return false;
        if (areaDraft.groupSet && areaDraft.groupId != null && ui.groupsWindow.findGroup(areaDraft.groupId) == null) {
            areaDraft.groupSet = false;
            areaDraft.groupId = null;
        }
        boolean any = false;
        for (AutosaveSettings.Field field : AutosaveSettings.Field.values())
            any |= fields.test(field) && areaDraft.pending(field);
        if (!any) return false;
        List<Area> areas = areaDraft.apply(ui.areas(), Set.copyOf(ui.areasWindow.selectedAreaIds), fields);
        State next = areaState(areas, ui.groups());
        if (!next.equals(ui.areaStore.state()) && !changeAreas(next, "area_update")) return false;
        // Keep only the fields that were not written.
        areaDraft.clear(fields);
        Area single = ui.areasWindow.selectedAreaIds.size() == 1 ? ui.areasWindow.selectedArea() : null;
        ui.setDialogValue(
                DialogField.AREA_NAME, areaDraft.name != null ? areaDraft.name : single == null ? "" : single.name());
        ui.say("area_changes_saved");
        return true;
    }

    /** Before the checked areas change or the window closes: autosaved fields are written, the rest dropped. */
    void flushDraft() {
        if (areaDraft.isEmpty()) return;
        commitDraft(ui.autosave::covers);
        if (!areaDraft.isEmpty()) {
            areaDraft.clear();
            ui.say("area_changes_discarded");
        }
    }

    /** Records a changed draft field and writes it at once when autosave covers it. */
    void stage(AutosaveSettings.Field field) {
        if (ui.autosave.covers(field)) commitDraft(f -> f == field);
    }

    /** Changes one stored area as one undo step. */
    boolean changeArea(Area area, UnaryOperator<Area> change) {
        return changeAreas(
                areaState(
                        ui.areas().stream()
                                .map(a -> a.id().equals(area.id()) ? change.apply(a) : a)
                                .toList(),
                        ui.groups()),
                "area_update");
    }

    /**
     * The list position that puts {@code area} past the next shown area it overlaps, up (1) or down (-1) the drawing
     * order (later is drawn over earlier), or -1 at the end: a step past an area elsewhere on the map would change
     * nothing.
     */
    int nextLayer(Area area, int direction) {
        List<Area> areas = ui.areas();
        for (int i = areas.indexOf(area) + direction; i >= 0 && i < areas.size(); i += direction) {
            AreaGeometry other = areas.get(i).geometry(), shape = area.geometry();
            // A route may cross a shape with no point inside it, so its bounds decide.
            boolean over = shape.closed() && other.closed()
                    ? shape.overlaps(other)
                    : shape.bounds().intersectsPositiveArea(other.bounds());
            if (over && ui.areasWindow.shownOnMap(areas.get(i))) return i;
        }
        return -1;
    }

    void moveLayer(Area area, int direction) {
        int to = nextLayer(area, direction);
        if (to < 0) return;
        List<Area> areas = new ArrayList<>(ui.areas());
        areas.remove(area);
        areas.add(to, area);
        changeAreas(areaState(areas, ui.groups()), "area_order");
    }

    /** Brings back the version before the last write of an area file that can no longer be read. */
    void restoreAreas() {
        MapBridge.Frame frame = ui.checkedFrame();
        if (frame == null) return;
        try {
            ui.areaStore.restoreBackup();
            ui.areaStore.useContext(frame.context());
            ui.loadFailed = false;
            ui.say("area_restored");
        } catch (IOException e) {
            ui.say("area_restore_failed");
            LOG.error("Could not restore saved areas", e);
        }
    }

    State areaState(List<Area> areas, List<Group> groups) {
        State current = ui.areaStore.state();
        return new State(areas, groups, current.showOnMap(), current.showOnMinimap(), current.showNames());
    }

    /** A switch of the saved-area overlays, stored with the areas of the world. */
    private enum Overlay {
        MAP,
        MINIMAP,
        NAMES
    }

    void toggleAreaMapVisibility() {
        flip(Overlay.MAP, "area_map_visibility");
    }

    void toggleAreaMinimapVisibility() {
        flip(Overlay.MINIMAP, "area_minimap_visibility");
    }

    void toggleAreaNames() {
        flip(Overlay.NAMES, "area_names_visibility");
    }

    private void flip(Overlay overlay, String label) {
        State s = ui.areaStore.state();
        changeAreas(
                new State(
                        s.areas(),
                        s.groups(),
                        s.showOnMap() ^ (overlay == Overlay.MAP),
                        s.showOnMinimap() ^ (overlay == Overlay.MINIMAP),
                        s.showNames() ^ (overlay == Overlay.NAMES)),
                label);
    }
}
