# Xaero Exact Selection

**English** | [Русский](README.ru.md)

A client-side Fabric addon for Minecraft **1.21.11**. Create precise rectangles and polygons on Xaero’s World Map, save colored areas and groups, show them on the world map and minimap, and export selected map regions as PNG images.

**This is an unofficial addon, not affiliated with or endorsed by Xaero.**

## Installation and compatibility

- Minecraft **1.21.11**, Java **21**, Fabric Loader **0.17.3+**, and Fabric API **0.139.4+** for this Minecraft version.
- Xaero’s World Map **1.41.0** and XaeroLib **1.1.15+** are required.
- Xaero’s Minimap **26.1.0** is optional. It enables minimap overlays and waypoint creation/editing/deletion. Other Minimap versions are rejected by the addon metadata.
- Put `xaero-exact-selection-1.3.0+1.21.11.jar` in your instance’s `mods` folder and enable the selection tool on the fullscreen map. Install it on the client only.

The addon uses Xaero internals. Other World Map or Minecraft versions need separate compatibility work.

## Selection and editing

| Action | Result |
|---|---|
| Left click, then left click | Set the two corners of a rectangle |
| Drag a handle | Resize the rectangle |
| Shift + drag inside the selection | Move the whole rectangle or polygon |
| Right mouse drag | Pan the map |
| Scroll outside the panel | Zoom around the cursor |
| Coordinates / Size / Move | Enter exact coordinates, dimensions or offsets |
| Ctrl/Cmd+Z, Ctrl+Y or Cmd+Shift+Z | Undo / redo |
| Esc | Cancel a gesture/dialog/draft, clear the selection, then leave the tool |

The compact panel is enabled by default. Expand it for coordinate input, dimensions, movement, waypoint management and PNG export. The expanded panel scrolls on small screens. Undo and redo are small arrow buttons with action tooltips.

Blocks are the default unit. Both endpoint blocks are included: `(0, 0)` to `(15, 15)` is **16 × 16 = 256 blocks²**. Chunk mode snaps rectangle edges to the 16-block grid, including negative coordinates. Coordinate input stays exact. Width is the X extent and length is the Z extent. Polygon dimensions describe its bounding box; area and perimeter follow its actual shape.

The separate **Copy** section copies coordinates, area, perimeter, length or width. The Center dialog copies the bounding-box center or creates a waypoint there at the player’s current Y level. A 3×6 rectangle has a perimeter of 18 blocks. Non-integer lengths are displayed to two decimal places.

### Polygons

Enable the Polygon tool, click vertices and finish by clicking the first vertex or pressing Enter. Backspace removes the last draft vertex. Shift aligns new vertices horizontally or vertically. Simple, non-intersecting polygons with 3–256 vertices are supported and have a fill.

- Ctrl/Cmd + drag a vertex to move it.
- Double click an edge to insert a vertex on that edge.
- Ctrl/Cmd + double click a vertex to delete it, in both drafts and completed polygons. A completed shape must keep at least three vertices.
- Ctrl/Cmd + drag a rectangle corner to turn it into an editable polygon. A click without movement keeps the rectangle.
- Double click an edge of a saved rectangle opened for editing to insert a vertex and turn it into a polygon.

Invalid intersecting or duplicate-vertex edits are rejected. Polygon geometry and draft changes support undo/redo. Polygon width/length labels remain in the panel rather than covering the map.

## Saved areas and groups

**Save** appears next to the coordinates in the compact panel and in the expanded panel’s action row. Enter a name, use the Group button to cycle through existing groups (the default is no group), set the fill with the Fill button (0, 25, 50, 75 or 100%) and pick one of 24 colors right below. Color and fill are remembered for the next area. After saving, the selection is cleared so the next area can be drawn; the first undo brings the selection back, the second removes the saved area. Saving a new area does not enter editing mode.

To change an existing area, select it in **Areas** and choose **Edit**. Change its shape and choose **Apply** on the selection panel. This association survives moves, coordinate edits, redraws and undo/redo. Clear the selection or turn the tool off to end editing. **Save selection…** in the Areas window can save a separate copy while editing.

Areas and Groups have separate windows. Check area rows for bulk actions. **All areas…** in the expanded panel opens the list with all areas selected.

- **Color…** offers 24 colors and custom `#RRGGBB` / `#RGB` input. Color and opacity apply to all checked areas in one undoable action.
- **Group** assigns checked areas to a group; No group removes the assignment. Create, rename, hide or delete groups in the Groups window.
- Individual visibility and group visibility are independent. A hidden group suppresses its areas without changing their individual settings.
- Delete checked areas after confirmation. Merge replaces them with one enclosing rectangle, including any gaps between them. Both actions support undo.
- World map and Minimap switches independently control persistent overlays when the selection tool is off.

Settings and areas are stored per world/server, dimension and subworld in `config/xaero-exact-selection/`. The existing schema 3 format is unchanged. Writes use a temporary file and atomic replacement where supported. An unreadable existing file is kept and is not overwritten with empty data.

## Xaero waypoints

**Waypoints: N** / **All waypoints…** opens management of ordinary saved waypoints in the current Xaero set. Search by name, filter visibility/color, check rows, delete points, rename them together or choose one of Xaero’s 16 waypoint colors. Editing names preserves symbols and initials.

With a completed selection, the window initially shows and checks the points inside it. Without a selection, it opens the entire set with **nothing checked**. The scope button switches between the selection and the whole set. Actions affect only checked rows matching the filters, and the window stays open after each action.

Before each write, the addon checks that the points and set still match the displayed snapshot. Stale actions refresh the list instead of changing unexpected points. Save failures restore in-memory data and attempt to persist the rollback. Actions support undo/redo. Temporary, server, death and destination waypoints are excluded.

## PNG export

Expand the selection panel and choose **Export selection as PNG with areas**, or export one saved area from the Areas window. Saving the working selection first is unnecessary. Export uses one pixel per block; pixels outside a polygon are transparent. Visible saved areas are included with their colors, opacity, group visibility and readable labels.

Files use Xaero’s own export destination, normally `map exports/` in the game directory. **Show PNG in folder** reveals the result in the default file manager where supported, or opens its folder. Native map preparation runs on the client thread; image composition and PNG encoding run in the background.

Map data must be loaded. Export is limited to **8,000,000 pixels**, including chunk alignment. Coordinate drafts and unfinished gestures cannot be exported.

## Building

```sh
./gradlew build
```

The default build resolves Xaero dependencies from its Maven repository. The exact dependency coordinates above were verified with a successful Gradle build. GitHub Actions builds on Temurin Java 21, checks localization, and uploads `build/libs/*.jar`. To use local Xaero JARs:

```sh
./gradlew -PxaeroDeps=build/xaero-deps build
```

With an existing Minecraft 1.21.11 Loom cache, `tools/build_cached.py` compiles the source, runs every standalone `*Check.java` test with assertions, and remaps the output to the intermediary namespace without Gradle or network access:

```sh
python3 tools/build_cached.py --cache /absolute/path/to/gradle-home/caches --deps /absolute/path/to/xaero-jars
python3 tools/check_localization.py
```

`tools/IntegrationSmokeCheck.java` initializes Fabric against the real Minecraft/Xaero dependencies in a separate temporary game directory. It verifies mixin application and the remapped GUI bridge without launching a game window. It requires the Fabric runtime classpath, the intermediary Minecraft JAR, Fabric API and the addon/Xaero JARs in the temporary `mods` directory.

Tests cover geometry, saved-area persistence, waypoint write rollback, editing/undo, localization, and UI widget layouts at 320×240, 480×270 and 960×540. They do not establish visual correctness in a running Minecraft client.

## License

The addon code is licensed under [MIT](LICENSE). Xaero mods remain separate dependencies and are not bundled. See [CHANGELOG.md](CHANGELOG.md) for release notes.
