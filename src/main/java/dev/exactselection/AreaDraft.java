package dev.exactselection;

import dev.exactselection.SavedAreaStore.Area;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Unsaved name, group, color and fill changes for the areas checked in the Areas window. */
final class AreaDraft {
    /** New name of the single checked area, or null when unchanged. */
    String name;

    boolean groupSet;
    /** New group id, null for "no group"; only meaningful when {@link #groupSet}. */
    String groupId;

    Integer color, opacity;

    boolean isEmpty() {
        return name == null && !groupSet && color == null && opacity == null;
    }

    boolean pending(AutosaveSettings.Field field) {
        return switch (field) {
            case NAME -> name != null;
            case GROUP -> groupSet;
            case COLOR -> color != null;
            case OPACITY -> opacity != null;
            case GEOMETRY -> false;
        };
    }

    /** True when some pending field is not written by autosave, so it still needs Save. */
    boolean needsSave(AutosaveSettings settings) {
        for (AutosaveSettings.Field field : AutosaveSettings.Field.values())
            if (pending(field) && !settings.covers(field)) return true;
        return false;
    }

    void clear() {
        clear(field -> true);
    }

    void clear(Predicate<AutosaveSettings.Field> fields) {
        if (fields.test(AutosaveSettings.Field.NAME)) name = null;
        if (fields.test(AutosaveSettings.Field.GROUP)) {
            groupSet = false;
            groupId = null;
        }
        if (fields.test(AutosaveSettings.Field.COLOR)) color = null;
        if (fields.test(AutosaveSettings.Field.OPACITY)) opacity = null;
    }

    /** {@code areas} with the selected pending fields applied to every area in {@code targets}. */
    List<Area> apply(List<Area> areas, Set<String> targets, Predicate<AutosaveSettings.Field> fields) {
        boolean rename = name != null && targets.size() == 1 && fields.test(AutosaveSettings.Field.NAME);
        boolean group = groupSet && fields.test(AutosaveSettings.Field.GROUP);
        boolean recolor = color != null && fields.test(AutosaveSettings.Field.COLOR);
        boolean fill = opacity != null && fields.test(AutosaveSettings.Field.OPACITY);
        return areas.stream()
                .map(a -> {
                    if (!targets.contains(a.id())) return a;
                    Area edited = rename ? a.withName(name) : a;
                    if (recolor) edited = edited.withColor(color);
                    if (fill) edited = edited.withOpacity(opacity);
                    return group ? edited.withGroup(groupId) : edited;
                })
                .toList();
    }
}
