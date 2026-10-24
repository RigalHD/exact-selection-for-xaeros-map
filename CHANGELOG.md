# Changelog

## 1.3.0+1.21.11

- Replace the Copy button rows with small copy icons directly after coordinate and metric values in both panel modes and the Areas window, including the name. Shorten both panels by 30 pixels and fit text and icons inside narrow layouts.
- Keep the default PNG export folder field empty and display its resolved path as a grey placeholder. Choosing Default folder clears a custom path while retaining a valid export destination.
- Save moved to the panel header beside Expand / Compact. The New area palette lines up with the window and takes a custom `#RRGGBB` color with a preview. Areas window: Save sits under Edit territory and Export map and leaves no gap when hidden, the Selected section moved to the bottom of the column, and autosave settings open from a gear button.
- PNG export window: folder (system picker, default folder, full target path), day/night using Xaero's night export, 1/2/4/8 pixels per block with the resulting size, labels on/off; remembered for all worlds. The image now looks like the in-game map: crisp enlarged pixels, translucent fills with thin outlines, and labels in the area color with Minecraft's pixel font, shadow and dark backing.
- Export checked areas, all areas or a whole group to JSON exchange files and import them into any world (groups matched by name, duplicates skipped, one undo step). Shift/Ctrl/Cmd range and toggle selection in the Areas list, list sorting by date added, name, group or visibility, and a copy-name button.
- Areas window: name, group, color and fill are edited as a draft and written with Save in one undo step; Autosave settings (main switch plus name, group, color, fill and territory shape) with an indicator. Edit becomes Edit territory and shows when that territory is being edited; its stored shape is drawn faded. Added a Selected section and labelled metrics with inline copy icons; the window opens with nothing checked. Removed Rename and Merge.
- Fix waypoints in the selection not being found (0 or "no set") when the World Map shows the minimap's current waypoint world rather than the auto-detected one, e.g. on servers with custom subworlds. Selections, highlights, editing and center waypoints now use the waypoint world the map displays, including Xaero's coordinate division between dimensions.
- Choose the color (24-color palette) and fill opacity directly in the New area window; both are remembered for the next area. The selection is cleared after saving, as its own undo step.
- Add quick Save with group selection and Apply for editing an existing saved area. The editing source is retained through geometry edits and selection history.
- Add a 24-color palette and custom RGB input. Apply colors and opacity to multiple checked areas.
- Add Areas in selection (saved areas overlapping the current selection) next to Areas, and management of the entire current waypoint set, with no waypoints checked by default outside a selection. The waypoint list has a draggable scrollbar.
- Show the perimeter on the area line when both fit; the panel gets one line shorter.
- Rename, recolor and delete checked waypoints with snapshot validation, rollback and undo/redo.
- Delete draft or completed polygon vertices with Ctrl/Cmd + double click. Convert rectangles through corner drags or edge insertion while editing a saved area.
- Display and copy perimeter with inline copy actions, and replace history buttons with tooltip-equipped arrows.
- Add English/Russian documentation, localization validation, waypoint edit tests and additional responsive UI/geometry checks.
- Preserve the existing saved-area schema and paths. Verify the default Xaero Maven dependencies with Gradle and add a Java 21 GitHub Actions build.

## 1.2.15+1.21.11

- Safely finish or cancel rectangle drags after losing the map context.
- Keep rectangle sizes and chunk alignment correct when an edge crosses the opposite edge.
- Insert polygon vertices on the selected edge instead of at an offset cursor position.
- Cull offscreen polygons before projecting their triangles.
- Move PNG composition/encoding into the background. Preserve a completed PNG when temporary-file cleanup fails and retain the original error on export failure.
- Add regression checks for these scenarios and separate map gesture state from the controller.

## 1.2.14+1.21.11

- Handle an unset current dimension while Xaero changes worlds or dimensions.
- Use the same fractional pointer position for previews, dragging and clicks, and match Xaero’s zoom cursor math at odd window sizes.
- Restrict waypoint row hit areas to visible rows. Hide polygon labels at the same minimum visible size as rectangle labels.
- Name undo/redo actions in tooltips, provide an actionable PNG size-limit error, and clean up failed area writes.
- Avoid copying entire waypoint sets every frame. Discover standalone checks and release version information automatically.

## 1.2.13+1.21.11

- Render selected and saved rectangle boundaries with fractional screen coordinates.
- Remove downward pixel rounding and inward shifts of the right and bottom edges at distant zoom levels.

## 1.2.12+1.21.11

- Preserve dimensions on stationary handle clicks and apply the final pointer position on release.
- Select complete chunks in either direction and preserve resize/move mode labels after rebuilding the screen.
- Keep polygon movement independent of chunk snapping.
- Include PNG labels only where the overlay and export territory intersect and the label fits.
- Anchor polygon names within visible geometry and hide fully offscreen labels.
- Reuse validated saved geometry and cull minimap areas before projecting every vertex.
