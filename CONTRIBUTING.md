# Contributing

**English** | [Русский](CONTRIBUTING.ru.md)

Thanks for helping with Exact Selection for Xaero's map. Bug reports, ideas and pull requests are all welcome.

## Reporting a problem

Open an [issue](https://github.com/RigalHD/xaero-exact-selection/issues/new/choose) and include:

- the versions of the mod, Minecraft, Fabric Loader, Fabric API, Xaero's World Map and, if installed, Xaero's Minimap;
- what you did, what you expected and what happened instead;
- the game log (`logs/latest.log`) if the game crashed or the tool stopped responding;
- a screenshot if something looks wrong on the map or in a window.

Saved areas live in `config/xaero-exact-selection/`. An area file names the world or server it belongs to, so attach one only if you are fine with sharing that.

## Building and checking

You need Java 21. The Gradle wrapper downloads everything else, including the Xaero mods.

```sh
./gradlew build
```

This compiles the mod, runs the JUnit checks and fails on unformatted code or on any compiler warning. The JAR is written to `build/libs/`.

Before opening a pull request, also run:

```sh
./gradlew spotlessApply
python3 tools/check_localization.py
```

`spotlessApply` formats the code (palantir-java-format, 120 columns). The localization check makes sure both language files have the same keys and placeholders, that every key is used, and that both READMEs have the same sections.

Changes to the map or the windows are best checked in a real client as well. The in-game scenarios in [`gametest/`](gametest/README.md) open a world and drive the tool with real input:

```sh
./gradlew -PgameTest runClientGameTest
```

## Guidelines

- Every text the player sees goes into both `en_us.json` and `ru_ru.json`. In Russian a saved shape is called «область».
- Add a line under `## Unreleased` in [CHANGELOG.md](CHANGELOG.md) for anything a player would notice.
- If a change affects how the mod is used, update both [README.md](README.md) and [README.ru.md](README.ru.md).
- Keep pull requests focused on one change, and describe how you checked it.
- Calls into Xaero go through `MapBridge` (World Map) and `WaypointAccess` (Minimap). The Minimap side is wrapped in `SafeWaypointAccess`, so an unsupported Minimap turns the waypoint features off instead of crashing the game; keep new calls behind these two.

## Where things are

- `ExactSelectionController` owns the state; each part of the interface (`SelectionPanel`, `AreasWindow`, `GroupsWindow`, `RulerTool`, `PolygonTool` and so on) is a `UiPart`.
- Each window describes itself in a `Window` object; `Windows.window()` is the one place that lists them.
- `SavedAreaStore` reads and writes the area files; `AreaExportService` writes PNG exports.
- `src/test` holds the JUnit checks; `gametest/` holds the in-game scenarios; `tools/` holds the localization check and the Xaero API check.

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
