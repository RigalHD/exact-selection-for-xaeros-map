# Changelog

## 1.4.0+1.21.11

### Added

- Layers: Move up and Move down in the right-click menu put a zone over or under the next one it overlaps, on the map, minimap and PNG. The first list order of the Areas window shows the layers.
- Overlap: with two areas checked, the Areas window shows the area they share, with a copy button.
- The right-click menu of a saved area shows its name, group and area or length; a click on one of them copies it.
- Ruler: a button in the panel header measures a line through points snapped to block corners (click A and B, or drag; Ctrl/Cmd + click adds a point, Backspace takes it back). The panel stays compact while it is on. The map shows the line and its length; the panel shows the length with a copy button, ΔX, ΔZ, the distance along the axes, and the same length in the Nether or Overworld.
- Routes: Save keeps the ruler's line as an entry of the Areas window, drawn as an open line on the map, minimap and PNG.
- A right click on a saved area opens a menu for it: Edit territory, Color…, Fill, Hide and Delete zone. Of overlapping areas it takes the smallest.
- Show on the map: a button beside the area's name in the Areas window, or a double click on a row, moves the map to the area and zooms to fit.
- Sticking: corners, vertices, dragged edges and moved shapes stick to saved areas nearby, so neighbours share a border. Alt turns it off.
- Tool keys in Options → Controls: Polygon (O), ruler (R), Areas (J), blocks or chunks (N).
- The area file names its world and keeps its previous version as `.bak`; the Areas window restores it when the file cannot be read.
- The Groups window shows each group's total area beside its area count, and the totals for all areas and for areas without a group.
- The panel's view, the units, the color and fill of the next area and the choices of the Size and Move windows are remembered between game sessions.

### Changed

- Undo and redo with the waypoint window open update its list. The loaded-area hints name the Save button, and the name fields of existing areas and of routes are labelled as such.
- Exporting a part of the map nobody explored says so instead of pointing to the game log. A widget that goes away no longer keeps the focus, which could leave the tool keys and undo dead after an export.
- Closing the map keeps the tool on with its selection, contour and undo history until the world changes, and the map key closes the map with the tool on.
- Delete zone in the right-click menu asks for confirmation first, like the Areas window.
- The Center and Color windows have a Cancel button; they closed only with Esc.
- Switching units to chunks no longer resizes a saved area being edited; it keeps its shape until you change it.
- Saved-area and settings files are flushed to disk before replacing the old file, so a power cut cannot leave an empty one.
- The waypoint color filter shows the color instead of its number.
- Two groups cannot share a name, since import matches groups by name.
- Names from an imported file lose control characters and the game's formatting sign.
- An error while drawing the tool skips the frame and is written to the game log instead of closing the game.
- Faster with many areas: an unchanged area file is not read again when the map opens, the minimap reuses what the map read, polygons keep their triangles and bounds, sticking skips areas away from the pointer, and tooltips are rebuilt only when their text changes.
- The Russian interface says «область» everywhere instead of mixing it with «зона».
- Leaving a world releases the map world the tool was holding.
- The Show file button stays until the next export instead of disappearing with the next message, and has its own row in the panel under the status line.
- One dimension holds up to 5000 areas and 1000 groups instead of 1000 and 200, and import takes files up to 64 MB instead of 16 MB.
- The world map draws all saved areas as one batch and clips only the ones crossing the screen's edge: 5000 areas on screen take about 2 ms a frame instead of 50.
- The switch that turns the tool on is a flat icon under Xaero's gear, like Xaero's own map buttons, instead of a square sign on a button: a selection with corner handles, white while the tool is off, teal and filled while it is on.

### Fixed

- Areas and center coordinates from 10 million with a half showed as `1.00045005E7`. The units key no longer reports the units when its button is unavailable, and a failed undo or redo is written to the game log.
- Shift did nothing while dragging an existing vertex; it now lines the vertex up with a neighbouring one, both in a contour and on a finished polygon.
- A first area saved in a new world vanished from the map while the Map switch was off. Saved areas are now always drawn with the tool on, and the first area switches the map overlay on.
- Esc, the Polygon button, Clear or turning the tool off lost a contour for good; undo now brings it back.
- Edit on an area off screen seemed to do nothing; the map now moves to it.
- Ctrl/Cmd+Z behind the new-area, color, size and similar windows changed what they work on, and Create then did nothing.
- At the limit of areas or groups the message spoke of a failed write; it now names the limit.
- The Show file button lost its tooltip on a window resize.
- Default names repeated after a deletion.
- A click on the map held back by typed coordinates in the expanded panel said nothing.
- A stopped export left its temporary folder behind.
- Edit in the Areas window during a two-click selection left it open, which blocked the right-click menu, export and redo until the next click on the map; undo from a window could do the same.
- Edit in the Areas window turns the ruler off; its clicks did not reach the loaded area.
- After collapsing the panel with typed coordinates, the map and buttons were blocked with no explanation. The typed values are still kept; the compact panel and a click on the map now say to expand the panel or press Esc.
- Redo after turning the tool off and on again turned it off; it now clears the selection.
- The waypoint filter buttons showed "all" after a window resize while the filter stayed on.
- Importing a rectangle drawn right to left in the same session added a copy instead of skipping it.
- With both the area file and its previous version unreadable, a second Restore replaced the kept damaged file, losing the latest areas. Restore now checks the previous version first and changes nothing when it cannot be read, and an earlier `.broken` file is never overwritten.
- An area file saved by a newer version of the mod was treated as damaged and offered a restore; it now says to update the mod.
- Deleting the area being edited from the Areas window left the selection editing an area that no longer existed.
- Areas in selection missed a route that crosses the selection with none of its points inside.
- Export was offered for an area just under the limit whose whole chunks exceed it, and then refused.
- Importing a route was skipped as a duplicate of a polygon with the same points, name and style.
- The export tooltip still promised 1 pixel per block; the scale is chosen in the export window.
- The export window's day or night, scale, areas, labels and fill choices were kept only when an export ran; the file name it shows no longer changes every second and is the one it writes.

### Internal

- The in-game scenarios in `gametest/` run with `./gradlew -PgameTest runClientGameTest`.
- Dependency versions live in `gradle.properties`, and the localization check also verifies the JAR name in both READMEs.
- The code is formatted with Spotless and compiles without warnings, and the build checks both. Repeated colors, the world limit and the config folder have names; enums and records replace number codes and small arrays. The checks run under JUnit, one test per scenario.
- A saved area is a record over one geometry; the in-game test scenarios are kept in `gametest/`.
- The 4100-line controller is split by job into the panel, map overlays, map input, right-click menu, Polygon tool, ruler, and the Areas, Groups, waypoint, color, transfer and Size/Move/Center windows. `tools/build_cached.py`, which no longer built the mod, is removed.

## 1.3.3+1.21.11

- Area and perimeter are copied rounded to whole blocks: down by default, or up or not at all (Rounding in the Areas window's gear settings).
- Mod Menu opens the mod's settings: autosave and copy rounding. The mod has an icon.
- Vertices of a contour or polygon are dragged with the left button alone (Ctrl/Cmd still works). While drawing a contour, a click on its side does nothing and shows the vertex controls, a double click there adds a vertex, and Ctrl/Cmd + click away from the vertices adds one instead of doing nothing. The vertex controls are shown when a contour starts.
- Area outlines on the world map and minimap are smoothed: their sides fade out over a pixel instead of showing steps on diagonals.
- The right-click menu looks like Xaero's own: a white frame, a title row and compact rows. In the expanded panel Save is above Apply; the expand/collapse chevrons are slightly smaller.
- Fix: with the Polygon tool on, a click on a side of a contour just closed started a new contour and dropped the selection, so a double click there could not add a vertex; it now acts as when editing.
- Fix: a double click slower than Minecraft's 250 ms but within the system's 500 ms on a side or vertex is still a double click.
- Fix: after a new contour was undone, the next change could not be undone.

## 1.3.2+1.21.11

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
- Fix: a new selection started while a saved area was loaded kept editing that area, so Save or autosave could write the new frame over it.

## 1.3.1+1.21.11

- Internal: the PNG export window moved to its own class, one atomic file writer replaces four copies, the Areas window sort is cached instead of recomputed every frame, and unused code was removed.
- Fixes: an undo or redo step that fails is dropped instead of blocking every older step; showing, hiding or switching layers no longer silently discards an unsaved name, group, color or fill; a large export lowers only that window's scale, not the remembered one; nothing is checked after deleting areas; import refuses files over 16 MB.
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
