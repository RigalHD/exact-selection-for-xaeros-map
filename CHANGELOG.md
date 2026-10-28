# Changelog

## 1.3.1+1.21.11

- Like a photo editor: dragging inside a finished selection moves it (handles and edges still resize, Shift still moves from anywhere). Size, Move and Center wait for a finished selection. The territory being edited is named above its frame on the map. A right click inside the selection opens quick actions: Save… and Clear selection, or Color…, Fill and Delete zone for an edited territory, each applied at once and undoable. A click outside a polygon starts a new selection, as it does for a rectangle.
- Area and perimeter are copied rounded down to whole blocks; Exact copying in the Areas window's gear settings keeps the fractions.
- Search by name in the Areas window filters the list (case-insensitive); All checks only the found areas.
- The Areas window lists all four corners of a rectangle by direction (NW, NE, SW, SE), each with a copy button, instead of A and B.
- Clicking the color preview in the save window or in a zone's color window opens a full color picker (saturation and brightness square, hue bar); in the zone's color window Apply recolors it at once.
- The yellow frame around waypoints inside the selection now surrounds Xaero's icon above the point, following its waypoint scale and the window size, instead of a small square under it.
- The expanded panel is narrower and shorter: half-width coordinate fields with Apply and Save beside them, and the buttons in three rows. The compact panel drops the corner rows and Clear. Expand / Compact is an icon; the Groups button left the panel (Manage groups… in the Areas window). Applying an edited territory is labelled Save.
- Messages and warnings appear for a few seconds near the bottom centre of the screen instead of being cut short in the panel.
- Fix: the compact panel keeps the warning when saved areas could not be loaded; All in the Areas window is unavailable when every found area is checked; a new area saved from the Areas window is scrolled into view in any order; an error drawing areas on the minimap skips that frame instead of breaking the minimap.
- Fix: a polygon or an open saved rectangle can be dragged from just inside its edges and corners; small ones on a zoomed-out map could not be moved at all; with the Polygon tool on, a click just outside a side adds a vertex as anywhere else.
- Fix: Show file in folder no longer disappears with the bottom message a few seconds after an export.
- Fix: a contour finished while a saved area was open kept editing that area, so Save or shape autosave wrote the new contour over it.
- Fix: a click inside the selection or on a handle whose pointer moved a pixel or two no longer moves or resizes it, not even while the button is held (zoomed out, a pixel is many blocks); with the Polygon tool on, such a click only adds the vertex. In the Areas window, All with a search that finds nothing no longer leaves the previous area active, and the checked count stays visible when the search field has no room.
- Fix: with the Polygon tool on, pressing inside a finished shape hid it as a new contour; a drag now moves it and a click still starts a contour.
- Size, Move and Center moved from the expanded panel to the selection's right-click menu (Size is left out for polygons).
- Map export of a saved area can leave out that area's own fill (This area's fill: off); its outline, name and other areas stay.
- Names: ON/OFF in the Areas window hides area names on the world map while the areas stay drawn; saved per world and undoable, older files keep names shown.
- Drop the separate XaeroLib requirement: World Map already requires its own XaeroLib, and every XaeroLib for 1.21.11 (1.0.44 and newer) has what the addon uses.
- Internal: the PNG export window moved to its own class, one atomic file writer replaces four copies, the Areas window sort is cached instead of recomputed every frame, and unused code was removed.
- Fixes: a new selection started while a saved area was loaded kept editing that area, so Save or autosave could write the new frame over it; an undo or redo step that fails is dropped instead of blocking every older step; showing, hiding or switching layers no longer silently discards an unsaved name, group, color or fill; a large export lowers only that window's scale, not the remembered one; nothing is checked after deleting areas; import refuses files over 16 MB.
- A folder button beside Import in the Areas window and beside Export group in the Groups window opens the folder with exported areas.
- Support Xaero’s World Map 1.40.4–1.47.x and Xaero’s Minimap 25.3.5 and newer instead of only 1.41.0 / 26.1.0. Every current release for 1.21.11 was checked for the classes, methods and fields the addon uses and loaded through Fabric. If a future Minimap changes them, the minimap overlay and waypoint features turn off instead of crashing the game.
- Rename the addon to Exact Selection for Xaero's map across metadata, documentation, release artifacts and logs. Keep existing mod IDs, storage paths and exchange files compatible.
- Add a remembered Areas on/off switch to PNG export. Hide saved fills, outlines and names while preserving the selected territory and in-game area visibility.
- Copy rectangle corners A and B with separate buttons in both panel modes and the Areas window. Coordinate pairs use a space between X and Z.
- Replace the Copy button rows with small copy icons directly after coordinate and metric values in both panel modes and the Areas window, including the name. Shorten both panels by 30 pixels and fit text and icons inside narrow layouts.
- Keep the default PNG export folder field empty and display its resolved path as a grey placeholder. Choosing Default folder clears a custom path while retaining a valid export destination.
- Save moved to the panel header beside Expand / Compact. The New area palette lines up with the window and takes a custom `#RRGGBB` color with a preview. Areas window: Save sits under Edit territory and Export map and leaves no gap when hidden, the Selected section moved to the bottom of the column, and autosave settings open from a gear button.
- PNG export window: folder (system picker, default folder, full target path), day/night using Xaero's night export, 1/2/4/8 pixels per block with the resulting size, labels on/off; remembered for all worlds. The image now looks like the in-game map: crisp enlarged pixels, translucent fills with thin outlines, and labels in the area color with Minecraft's pixel font, shadow and dark backing.
- Export checked areas, all areas or a whole group to JSON exchange files and import them into any world (groups matched by name, duplicates skipped, one undo step). Shift/Ctrl/Cmd range and toggle selection in the Areas list, list sorting by date added, name, group or visibility, and a copy-name button.
- Areas window: name, group, color and fill are edited as a draft and written with Save in one undo step; Autosave settings (main switch plus name, group, color, fill and territory shape) with an indicator. Edit becomes Edit territory and shows when that territory is being edited; its stored shape is drawn faded. Added a Selected section and labelled metrics with inline copy icons; the window opens with nothing checked. Removed Rename and Merge.
- Fix waypoints in the selection not being found (0 or "no set") when the World Map shows the minimap's current waypoint world rather than the auto-detected one, e.g. on servers with custom subworlds. Selections, highlights, editing and center waypoints now use the waypoint world the map displays, including Xaero's coordinate division between dimensions.
- Choose the color (24-color palette) and fill opacity directly in the New area window; both are remembered for the next area. The selection is cleared after saving, as its own undo step.
- Add Areas in selection (saved areas overlapping the current selection) next to Areas, and management of the entire current waypoint set, with no waypoints checked by default outside a selection. The waypoint list has a draggable scrollbar.
- Show the perimeter on the area line when both fit; the panel gets one line shorter.

## 1.3.0+1.21.11

- Add quick Save with group selection and Apply for editing an existing saved area. The editing source is retained through geometry edits and selection history.
- Add a 24-color palette and custom RGB input. Apply colors and opacity to multiple checked areas.
- Add All areas and management of the entire current waypoint set, with no points selected by default outside a selection.
- Rename, recolor and delete checked waypoints with snapshot validation, rollback and undo/redo.
- Delete draft or completed polygon vertices with Ctrl/Cmd + double click. Convert rectangles through corner drags or edge insertion while editing a saved area.
- Display and copy perimeter. Group copy actions, use full expanded labels, and replace history buttons with tooltip-equipped arrows.
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
