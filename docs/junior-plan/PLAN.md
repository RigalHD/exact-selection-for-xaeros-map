# План реализации для исполнителя

Это задание для модели-исполнителя. Оно продолжает `docs/AUDIT_PLAN.md` (этапы 5 и 6) и рассчитано на то, что ты выполняешь его буквально, шаг за шагом.

Состояние на начало: ветка `feature/zones-routes-cleanup`, последний коммит кода — `2b50978`. `./gradlew build` проходит.

Три самых рискованных шага уже сделаны и проверены автором плана. Они лежат готовыми патчами в `docs/junior-plan/patches/`. Каждый патч накладывался на `2b50978` по порядку, после каждого сборка проходила.

## 0. Правила

### Что можно и чего нельзя

- **Ветка.** Работай в `feature/zones-routes-cleanup`. Не переключай ветку, не пушь, не делай rebase и amend.
- **Порядок.** Задачи выполняй по номерам. Не начинай следующую, пока предыдущая не закоммичена.
- **Один коммит на задачу** (в задаче 3 — один коммит на окно). Сообщение на английском, в повелительном наклонении, как в истории: `Run the checks under JUnit`.
- **Не переименовывай** поля, методы и классы, которых нет в этом плане. Тесты ищут их по имени через рефлексию сразу во всех частях интерфейса (`UiHarness.holders`). Одинаковое имя в двух частях сломает тесты незаметно.
- **Не трогай** три вещи, даже если они кажутся некрасивыми:
  - строку `ui.message` и поля `toastText`, `toastShownAt`, `exportNotice`, `lastExport` — на них завязаны 28 мест в тестах;
  - код в `drawMapOverlays`, который двигает второй угол выделения, — его проверяют тесты отрисовки;
  - `InputRouter.keyPressed` — решение по клавишам Xaero отложено владельцем.
- **Тесты.** Меняй файлы в `src/test` только там, где это прямо написано в задаче. Если тест упал — чини свой код, а не тест.
- **Новые строки интерфейса** добавляй сразу в `en_us.json` и `ru_ru.json`. В русском сохранённая фигура называется «область», не «зона».

### Команды

Запускай из корня проекта. Переменная нужна, чтобы Gradle взял готовый кэш:

```bash
export GRADLE_USER_HOME=/Users/rigalhd/Documents/Codex/2026-10-20/new-chat-2/work/gradle-home
```

Перед каждым коммитом, в этом порядке:

```bash
./gradlew --no-daemon spotlessApply
```

```bash
./gradlew --no-daemon build
```

```bash
python3 tools/check_localization.py
```

```bash
graphify update .
```

Что значит «зелёно»:
- `build` заканчивается строкой `BUILD SUCCESSFUL`;
- в выводе нет `AssertionError`, `FAILED`, `error:` и `warning:` (сборка идёт с `-Werror`);
- скрипт переводов печатает строку, начинающуюся с `PASS:`.

В выводе будут `NoSuchMethodError` и `NoClassDefFoundError` от проверки `SafeWaypointAccess`. Это норма: так тест изображает неподдерживаемую миникарту.

### Когда остановиться и спросить

Остановись, ничего не коммить и опиши, что произошло, если:
- патч не накладывается (`git apply` сообщает об ошибке);
- сборка красная, и ты не починил её за две попытки;
- для исправления нужно изменить тест, который план не называет;
- текст-якорь из плана («найди строку…») не находится в файле.

Не обходи проблему своим решением.

---

## Задача 1. Три мелких улучшения (готовый патч)

**Что делает патч `01-small-tasks.diff`.**
- Новый класс `UiState`: вид панели, единицы, цвет и заливка следующей области, выбор в окнах «Размеры» и «Сдвиг» сохраняются в `config/xaero-exact-selection/ui.json` и переживают перезапуск игры.
- У контроллера появился шестой параметр конструктора `UiState`. Старый конструктор с пятью параметрами (им пользуются тесты) передаёт `UiState.inMemory()`, поэтому тесты ничего не пишут на диск.
- `ExactSelectionController.forget()` и подписка на `ClientPlayConnectionEvents.DISCONNECT` в `ToolKeys`: после выхода из мира инструмент отпускает ссылку на мир карты.
- `WaypointsWindow.filteredPoints()` запоминает последний результат: список запрашивается несколько раз за кадр.

**Шаги.**

1. Наложи патч:

```bash
git apply docs/junior-plan/patches/01-small-tasks.diff
```

2. В `CHANGELOG.md` в разделе `## Unreleased` перед строкой, начинающейся с `- Internal: the code is formatted`, добавь две строки:

```
- The panel's view, the units, the color and fill of the next area and the choices of the Size and Move windows are remembered between game sessions.
- Leaving a world releases the map world the tool was holding.
```

3. В `README.md` найди предложение `Color and fill are remembered for the next area.` и замени его на `Color and fill are remembered for the next area, also after a restart.`

4. В `README.ru.md` найди `Цвет и заливка запоминаются для следующей области.` и замени на `Цвет и заливка запоминаются для следующей области, в том числе после перезапуска игры.`

5. Выполни команды из раздела 0. Коммит: `Remember the interface state between sessions; release the world on disconnect`.

---

## Задача 2. Проверки под JUnit (готовый патч)

**Что делает патч `02-junit.diff`.**
- В `build.gradle` добавлены JUnit 5.13.4 и `useJUnitPlatform()`; цикл, который запускал каждый `*Check` отдельной задачей, удалён.
- `ManagementUiCheck.java` удалён. Вместо него `ManagementUiTest.java`: общая подготовка в `@BeforeAll`, 30 сценариев — отдельные тесты в прежнем порядке (`@Order`).
- Новый `ChecksTest.java`: по одному тесту на каждый из девяти оставшихся `*Check.main`.
- `forkEvery = 1`: каждый класс тестов идёт в своей JVM, потому что тесты подменяют статический экземпляр `Minecraft`.

Сценарии в `ManagementUiTest` зависят друг от друга: каждый начинает с состояния, которое оставил предыдущий. **Не делай их независимыми и не меняй порядок.**

**Шаги.**

1. Наложи патч:

```bash
git apply docs/junior-plan/patches/02-junit.diff
```

2. Запусти сборку. В выводе должно быть 39 строк с `PASSED`.

3. В `README.md` в разделе `## Building` найди абзац, начинающийся с `Tests cover geometry`, и добавь перед ним абзац:

```
The checks run under JUnit as part of `./gradlew build`; the report is in `build/reports/tests/test/index.html`.
```

4. В `README.ru.md` в разделе `## Сборка` добавь последним абзацем раздела:

```
Проверки идут под JUnit в составе `./gradlew build`; отчёт лежит в `build/reports/tests/test/index.html`.
```

5. В `CHANGELOG.md` в строке, начинающейся с `- Internal: the code is formatted`, допиши в конец: ` The checks run under JUnit, one test per scenario.`

6. Команды из раздела 0. Коммит: `Run the checks under JUnit`.

---

## Задача 3. Окна через `Window` вместо девяти `switch`

Сейчас `Windows.java` и `InputRouter.java` описывают 13 окон параллельными `switch`. Цель — чтобы каждое окно само описывало себя в объекте `Window`, а `Windows` спрашивал открытое окно.

### 3.1. Первые два окна (готовый патч)

```bash
git apply docs/junior-plan/patches/03-window-first-two.diff
```

Патч добавляет:
- класс `Window` (`src/main/java/dev/exactselection/Window.java`);
- метод `Windows.window()`: возвращает объект открытого окна или `null`, если окно ещё не перенесено;
- в каждом `switch` — проверку «если `window() != null`, спроси его, иначе работай по-старому»;
- два перенесённых окна как образец: `SIZE` (`SelectionDialogs.sizeWindow`) и `AREAS` (`AreasWindow.window`).

Команды из раздела 0. Коммит: `Describe windows by a Window object; move Size and Areas`.

Прочитай оба образца, прежде чем идти дальше: остальные окна переносятся точно так же.

### 3.2. Рецепт переноса одного окна

Для каждого окна из таблицы ниже:

1. В классе-владельце добавь поле `final Window <имя поля> = new Window() { … };`.
2. Заполни методы по таблице. Тело каждого метода **перенеси дословно** из соответствующей ветки `case` в `Windows.java` или `InputRouter.java`. Ничего не переписывай по-своему.
3. При переносе:
   - `ui.<владелец>.` перед полями и методами самого владельца убери (внутри владельца они доступны напрямую);
   - `x`, `y`, `w`, `h` получи в начале метода: `int x = r.x(), y = r.y(), w = r.width(), h = r.height();` — объявляй только те, что используются, иначе сборка упадёт на предупреждении;
   - если нужен `place(...)`, добавь в файл `import static dev.exactselection.ExactSelectionController.place;` (если его ещё нет).
4. В `Windows.window()` добавь строку `case <ОКНО> -> ui.<владелец>.<имя поля>;` перед `default`.
5. Старые ветки `case` этого окна **пока не удаляй** — они станут недостижимыми и уйдут в шаге 3.4.
6. Команды из раздела 0. Коммит: `Move the <name> window to Window`.

### 3.3. Таблица окон

Обозначения: «центр» — заголовок по центру (ничего не переопределять), «слева» — переопределить `titleOnLeft()` и вернуть `true`. «—» — метод не переопределять.

Переноси в этом порядке.

**MOVE** — владелец `SelectionDialogs`, поле `moveWindow`
- `width` 560, `height` 180, `title` `"dialog_move"`, центр, `returnTo` —
- `show`: тело `if (ui.dialog == Dialog.MOVE)` из `showWindowWidgets`
- `layout`: тело `case MOVE` из `layoutDialog`
- `render`: тело `case MOVE` из второго `switch` в `renderDialog`
- `click`, `scroll` —

**CENTER** — владелец `SelectionDialogs`, поле `centerWindow`
- 560 × 172, `"dialog_center"`, центр, `returnTo` —
- `show`, `layout`, `render`: ветки `CENTER`
- `click`, `scroll` —

**GROUP_NAME** — владелец `GroupsWindow`, поле `nameWindow`
- 420 × 140, `title`: `renamingGroup ? "group_rename_title" : "group_new_title"`, центр
- `returnTo`: `Dialog.GROUPS`
- `show`: `ui.show(true, ui.field(DialogField.GROUP_NAME), ui.modalApply, ui.modalCancel);`
- `layout`: из общей ветки `case AREA_NAME, GROUP_NAME` возьми только то, что относится к группе:

```java
place(ui.field(DialogField.GROUP_NAME), x + 16, y + 48, w - 32, 18);
place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
```

- `render`:

```java
g.drawString(ui.mc.font, ui.tr("group_name"), x + 16, y + 32, -1);
if (!ui.message.isEmpty())
    g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.message, w - 32), x + 16, y + 80, Theme.WARNING);
```

- `click`, `scroll` —

**AREA_NAME** — владелец `NewAreaWindow`, поле `window`
- 420 × 236, `title`: `routeToSave != null ? "route_new_title" : "area_new_title"`, центр
- `returnTo`: `areaNameReturn`
- `show`: тело `if (ui.dialog == Dialog.AREA_NAME)` из `showWindowWidgets`, включая строку про `newAreaOpacityButton.visible`
- `layout`:

```java
place(ui.field(DialogField.NEW_AREA), x + 16, y + 48, w - 32, 18);
layoutNewArea(x, y, w);
place(ui.modalApply, x + w / 2 - 116, buttonY, 110, 18);
place(ui.modalCancel, x + w / 2 + 6, buttonY, 110, 18);
```

- `render`:

```java
g.drawString(ui.mc.font, ui.tr(routeToSave != null ? "route_name" : "area_name"), x + 16, y + 32, -1);
renderNewArea(g, x, y, w);
if (!ui.message.isEmpty())
    g.drawString(ui.mc.font, ui.mc.font.plainSubstrByWidth(ui.message, w - 32), x + 16, y + 194, Theme.WARNING);
```

- `click`: `clickAreaNameDialog(event, r.x(), r.y(), r.width(), r.height());`
- `scroll` —

**PICKER** — владелец `ColorWindows`, поле `pickerWindow`
- 420 × 236, `"picker_title"`, центр, `returnTo`: `pickerReturn`
- `show`: `ui.show(true, ui.modalApply, ui.modalCancel);`
- `layout`: тело `case PICKER`
- `render`: `renderPicker(g, r.x(), r.y(), r.width(), r.height());`
- `click`: `clickPicker(event, r.x(), r.y(), r.width(), r.height());`
- `scroll` —

**COLOR** — владелец `ColorWindows`, поле `colorWindow`
- 420 × 164, `"color_title"`, центр, `returnTo`: `colorReturn`
- `show`: тело `if (ui.dialog == Dialog.COLOR)` из `showWindowWidgets`
- `layout`: `layoutColorDialog(r.x(), r.y(), r.width(), r.height(), buttonY);`
- `render`: `renderColorDialog(g, r.x(), r.y(), r.width(), r.height());`
- `click`: `clickColorDialog(event, r.x(), r.y(), r.width(), r.height());`
- `scroll` —

**IMPORT** — владелец `TransferWindow`, поле `importWindow`
- 560 × 330, `"import_title"`, слева, `returnTo`: `Dialog.AREAS`
- `show`: `ui.show(true, ui.managementClose, importOpenFolder);`
- `layout`: тело `case IMPORT`
- `render`: `renderImportDialog(g, r.x(), r.y(), r.width(), r.height());`
- `click`: `clickImportRow(event, r.x(), r.y(), r.width(), r.height());`
- `scroll`: `importScroll = InputRouter.scrolled(importScroll, step, importFiles.size(), importRows(height));`

**ASSIGN_GROUP** — владелец `GroupsWindow`, поле `chooserWindow`
- 420 × 330, `"group_picker_title"`, слева, `returnTo`: `Dialog.AREAS`
- `show`: `ui.show(true, manageGroups, ui.managementClose);`
- `layout`: тело `case ASSIGN_GROUP`
- `render`: `renderGroupChooser(g, r.x(), r.y(), r.width(), r.height());`
- `click`: `clickGroupChooser(event, r.x(), r.y(), r.width(), r.height());`
- `scroll`:

```java
if (ui.areasWindow.areaDeleteConfirmation || groupDeleteConfirmation) return;
assignmentScroll = InputRouter.scrolled(assignmentScroll, step, ui.groups().size() + 1, visibleAssignmentRows(height));
```

**GROUPS** — владелец `GroupsWindow`, поле `window`
- 760 × 460, `"groups_window"`, слева, `returnTo`: `groupsReturnDialog`
- `show`: `showGroupWidgets();`
- `layout`: `layoutGroups(r.x(), r.y(), r.width(), r.height(), buttonY);`
- `render`: `renderGroupsDialog(g, r.x(), r.y(), r.width(), r.height());`
- `click`: `clickGroupRow(event, r.x(), r.y(), r.width(), r.height());`
- `scroll`:

```java
if (ui.areasWindow.areaDeleteConfirmation || groupDeleteConfirmation) return;
groupScroll = InputRouter.scrolled(groupScroll, step, ui.groups().size(), visibleGroupDialogRows(height));
```

**WAYPOINTS** — владелец `WaypointsWindow`, поле `window`
- 560 × 330, `"dialog_waypoints"`, центр, `returnTo` —
- `show` — (виджеты этого окна показывает `updateWaypointWidgets`)
- `layout`: `layoutWaypoints(r.x(), r.y(), r.width(), r.height(), buttonY);`
- `render`: `renderWaypointsDialog(g, r.x(), r.y(), r.width(), r.height());`
- `click`: `clickWaypointRow(event, r.x(), r.y(), r.width(), r.height());`
- `scroll`:

```java
if (pending != null)
    modalScroll = InputRouter.scrolled(modalScroll, step, filteredPoints().size(), waypointRows(height));
```

**EXPORT** — владелец `MapExport`, поле `window` (сам `ExportWindow` не трогай)
- `width` 560, `height`: `ui.exportWindow.hasTargetArea() ? 242 : 220`, `"export_title"`, центр
- `returnTo`: `exportReturn`
- `show`:

```java
ui.exportWindow.show(exportRunning);
ui.show(true, ui.modalCancel);
```

- `layout`:

```java
ui.exportWindow.layout(r.x(), r.y(), r.width(), buttonY);
place(ui.modalCancel, r.x() + r.width() / 2 + 6, buttonY, 110, 18);
```

- `render`: `ui.exportWindow.render(g, r.x(), r.y(), r.width(), ui.message);`
- `click`, `scroll` —

### 3.4. Уборка после переноса всех окон

Делай только когда в `Windows.window()` перечислены все 13 окон.

1. `Windows.window()`: замени `default -> null;` на `case NONE -> null;`. Если компилятор говорит, что `switch` не покрывает какое-то значение, — это окно ты пропустил; вернись к 3.2.
2. `Windows.cancelDialog()`: замени присваивание с `switch` на:

```java
Window open = window();
ui.dialog = open == null ? Dialog.NONE : open.returnTo();
```

3. `Windows.dialogRect()`: всё тело замени на:

```java
Window open = window();
int width = Math.min(open == null ? 560 : open.width(), ui.screen.width - 20);
int height = open == null ? 0 : Math.min(open.height(), ui.screen.height - 16);
return new Rect((ui.screen.width - width) / 2, (ui.screen.height - height) / 2, width, height);
```

4. `Windows.layoutDialog()`: удали `switch` целиком; после `int buttonY = …` остаётся `window().layout(r, buttonY);`. Неиспользуемые `x`, `w`, `h` удали, `y` оставь, если он нужен для `buttonY`.
5. `Windows.showWindowWidgets()`: тело — `Window open = window(); if (open != null) open.show();`.
6. `Windows.renderDialog()`: удали `String title = switch …`, условие с выравниванием и второй `switch`; останется блок с `open.titleOnLeft()` и `open.render(g, r)` без `if (open != null)` и без `return`.
7. `InputRouter.mouseClicked`: вместо `switch` — `ui.windows.window().click(event, r);`.
8. `InputRouter.scrollWindow`: тело — `ui.windows.window().scroll(step, ui.windows.dialogRect().height());`.
9. Удали импорты, ставшие ненужными (сборка назовёт их — `spotlessApply` уберёт сам).
10. Обнови Javadoc класса `Windows` на: `/** What all windows share: opening and closing them, their frame and title, and the shared buttons. Each window describes itself in a {@link Window}; {@link #window()} is the one place that lists them. */`
11. В `docs/AUDIT_PLAN.md` в разделе 5 отметь `- [x] 3.5 Интерфейс окна.`
12. Команды из раздела 0. Коммит: `Remove the per-window switches`.

**Не переноси** в `Window` методы `openDialog`, `applyDialog`, `updateWindowWidgets`, `confirmDelete` и `InputRouter.enterInField`. Это отдельная работа, её в этом плане нет.

---

## Задача 4. Три места, где части правят чужие поля

Каждый пункт — отдельный коммит.

### 4.1. Открытие окна «Области»

В `AreasWindow` добавь метод:

```java
/** The window opens with nothing checked; properties apply only to areas the user picks. */
void resetForOpening() {
    areaSearch.setValue("");
    selectedAreaIds.clear();
    areaId = null;
    areaAnchorId = null;
    ui.areaEdits.areaDraft.clear();
    autosaveOpen = false;
    areaDeleteConfirmation = areaDeleteFromMenu = false;
    ui.groupsWindow.groupDeleteConfirmation = false;
    ui.setDialogValue(DialogField.AREA_NAME, "");
    ui.message = "";
}
```

В `Windows.openDialog`:
- удали строку `if (next == Dialog.AREAS) ui.areasWindow.areaSearch.setValue("");`
- тело ветки `else if (next == Dialog.AREAS) { … }` замени на `ui.areasWindow.resetForOpening();`

Коммит: `Let the Areas window reset itself when it opens`.

### 4.2. Удаление из контекстного меню

В `AreasWindow` добавь метод:

```java
/** Asks to delete one area from the map's right-click menu; either answer returns to the map. */
void askDeleteFromMenu(Area area) {
    selectedAreaIds.clear();
    selectedAreaIds.add(area.id());
    areaId = area.id();
    ui.areaEdits.areaDraft.clear();
    ui.dialog = Dialog.AREAS;
    areaDeleteConfirmation = areaDeleteFromMenu = true;
    ui.message = "";
    ui.focus(ui.confirm);
}
```

В `RightClickMenu.createMenuWidgets`, в обработчике `quickMenuDelete`, всё после строки `if (area == null) return;` до конца лямбды замени на `ui.areasWindow.askDeleteFromMenu(area);`.

Коммит: `Let the Areas window ask for a deletion from the menu`.

### 4.3. Сообщение об экспорте файла областей

В `MapExport` добавь метод:

```java
/** Announces a written file and keeps the Show file button for it. */
void announceFile(Path file, String notice, Component tooltip) {
    lastExport = file.toAbsolutePath().normalize();
    exportNotice = notice;
    ui.message = notice;
    setExportTooltip(tooltip);
}
```

В `TransferWindow.exportTransfer` все строки от `ui.mapExport.lastExport = …` до `ui.mapExport.setExportTooltip(…);` включительно замени на:

```java
ui.mapExport.announceFile(
        file,
        ui.tr("areas_exported", areas.size(), file.getFileName().toString()).getString(),
        Component.literal(file.toAbsolutePath().normalize().toString()));
```

Коммит: `Let the map export announce a written area file`.

### 4.4. Лишний код в отрисовке окна вейпоинтов

В `WaypointsWindow.renderWaypointsDialog` удали две последние строки метода:

```java
ui.confirm.setMessage(ui.tr("delete_count", chosen));
ui.confirm.active = chosen > 0;
```

То же самое уже делает `updateWaypointWidgets`. Коммит: `Do not set the delete button while drawing the waypoint window`.

---

## Задача 5. Проверка переводов

1. Замени `tools/check_localization.py` файлом из патчей:

```bash
cp docs/junior-plan/patches/check_localization.py.new tools/check_localization.py
```

2. Запусти его. Ожидаемый вывод — строка `PASS: … keys used by the code` и строка `NOTE: README.md and README.ru.md differ in their headings`. Вторая строка пропадёт после задачи 7.

Скрипт теперь дополнительно проверяет, что каждый ключ используется в коде и что каждый ключ из кода есть в файлах. Ключи, которые код собирает склейкой, перечислены в `DYNAMIC` и `DYNAMIC_ENDINGS`. Если добавишь новый такой ключ — допиши его начало туда.

Коммит: `Check that every language key is used and every used key exists`.

---

## Задача 6. Комментарии к классам

Добавь комментарий перед объявлением класса. Текст бери дословно. Больше в этих файлах ничего не меняй. Один коммит на всю задачу: `Document the classes that had no description`.

| Файл | Комментарий |
|---|---|
| `GridPoint.java` | `/** A block corner of the world: the point where four blocks meet. Polygon vertices and route points are block corners. */` |
| `GeometryBounds.java` | `/** The outer edges of a shape in block corners: {@code max} is the first corner past the shape, so width is {@code maxX - minX}. Compare {@link SelectionBounds}, which holds blocks and includes both ends. */` |
| `AreaGeometry.java` | `/** The shape of a selection or a saved area: a rectangle, a polygon or a route. Vertices are block corners, in drawing order. */` |
| `RectangleGeometry.java` | `/** A rectangle given by its two corner blocks, kept in the order they were drawn. */` |
| `ChunkSelection.java` | `/** Chunk mode of the two-click selection: turns the first corner and the pointer into whole 16-block chunks. */` |
| `SelectionState.java` | `/** The current selection: its phase, its shape and the saved area it edits, if any. Snapshots of it are what undo and redo restore. */` |
| `SelectionHistory.java` | `/** Undo and redo of everything the tool changes: the selection, saved areas and waypoints. At most 100 steps. */` |
| `AreaPalette.java` | `/** The 24 colors offered for areas, and conversions between RGB, "#RRGGBB" text and hue, saturation and brightness. */` |
| `AreaExportService.java` | `/** Exports a part of the map to PNG: Xaero renders whole chunks on the client thread, then the image is cropped to the shape, enlarged and overlaid with saved areas in the background. */` |
| `AreaExportConfigOverride.java` | `/** Xaero's export settings for the duration of one export of ours, without changing what the user has set. */` |
| `AreaRenderPipelines.java` | `/** The render pipeline for area fills and outlines on the minimap: translucent, without depth test or culling. */` |
| `GeometryScreenRenderer.java` | `/** Draws shapes on the World Map screen: filled triangles clipped to the screen and smoothed outlines. */` |
| `GuiGraphicsBridge.java` | `/** Gives the renderer the GUI render state of a GuiGraphics; implemented by GuiGraphicsMixin. */` |
| `MinimapAreaOverlay.java` | `/** Saved areas on Xaero's minimap, drawn before its own elements; it reads the areas of the dimension the minimap shows. */` |
| `mixin/GuiMapMixin.java` | `/** Hooks the tool into Xaero's World Map screen: creates the controller with the screen, passes it input before Xaero and drawing after the map, keeps the zoom anchored on the pointer, and exposes the camera as {@link MapBridge}. */` |
| `mixin/GuiGraphicsMixin.java` | `/** Exposes the GUI render state, which shapes are submitted to. */` |
| `mixin/MinimapOverlayMixin.java` | `/** Draws saved areas on the minimap before its own elements. Optional: an unsupported Minimap only loses this overlay. */` |
| `mixin/MapSaveLoadAccessor.java` | `/** Reaches Xaero's PNG exporter and its "export in progress" flag. */` |
| `mixin/PNGExporterAccessor.java` | `/** Reads and redirects the folder Xaero exports to. */` |
| `mixin/PNGExporterConfigMixin.java` | `/** Lets one export of ours use its own settings: every setting the exporter reads goes through {@link AreaExportConfigOverride}. */` |

У `PolygonGeometry`, `SavedAreaStore` и `XaeroWaypointBridge` комментарии уже есть.

В `docs/AUDIT_PLAN.md` в пункте 4.5 ничего не меняй; в разделе 5 этап 6 отметишь в задаче 9.

---

## Задача 7. README

Цель — одинаковые разделы в обоих файлах и короткие абзацы. **Факты не меняй**: ни одной новой возможности, ни одной изменённой цифры. Текст только перемещается и делится.

1. В `README.ru.md`:
   - раздел `## Новое в 1.3` удали как раздел, а его три абзаца перенеси: первый и второй — в начало `## Сохранённые области`, третий — в конец `## Выделение и геометрия`;
   - раздел `## Проверка текущей поставки` переименуй в `## Лицензия`; его первый абзац (про проверку версий) перенеси в конец раздела `## Сборка`;
   - добавь подзаголовки `### Линейка` и `### Многоугольники` перед абзацами про линейку и про инструмент «Контур» (в английском файле они стоят в том же месте);
   - добавь заголовок `## Экспорт в PNG` перед абзацем, начинающимся с `Для экспорта текущего выделения`.
2. Сверь порядок: в обоих файлах заголовки должны идти так — Features, Installation and compatibility, Selection and editing (внутри Ruler, Polygons), Saved areas and groups, Xaero waypoints, PNG export, Building, License.
3. В обоих файлах раздели абзацы длиннее пяти предложений на маркированные списки: одно утверждение — один пункт. Таблицы не трогай.
4. Запусти `python3 tools/check_localization.py`. Строки `NOTE: README.md and README.ru.md differ in their headings` быть не должно.

Коммит: `Give both READMEs the same sections; split long paragraphs`.

---

## Задача 8. CHANGELOG

1. Раздел `## Unreleased` раздели на четыре подзаголовка в этом порядке: `### Added`, `### Changed`, `### Fixed`, `### Internal`.
   - `Fixed` — строки, начинающиеся с `Fix:`; слово `Fix:` убери, первую букву сделай заглавной.
   - `Internal` — строки, начинающиеся с `Internal:`; слово убери так же.
   - `Added` — строки про новую возможность (линейка, маршруты, слои, меню области, клавиши и т. п.).
   - `Changed` — всё остальное.
2. Строку с несколькими исправлениями через `;` раздели на отдельные пункты.
3. Текст пунктов не переписывай.
4. Разделы прошлых версий не трогай.

Коммит: `Sort the unreleased changes into Added, Changed, Fixed and Internal`.

---

## Задача 9. Сборка и отметки в плане

1. `gradle.properties`: добавь строку `mod_version=1.3.3+1.21.11`.
2. `build.gradle`: строку `version = '1.3.3+1.21.11'` замени на `version = project.mod_version`.
3. `build.gradle`: после блока `processResources { … }` добавь:

```groovy
jar {
    from('LICENSE')
}
```

4. `src/main/resources/fabric.mod.json`: в объект `"suggests"` добавь `"modmenu": "*"` (не забудь запятую после строки про `xaerominimap`).
5. Сборка. Проверь, что лицензия попала в JAR:

```bash
unzip -l build/libs/exact-selection-for-xaeros-map-1.3.3+1.21.11.jar | grep LICENSE
```

6. В `docs/AUDIT_PLAN.md`, раздел 5: в абзаце «Состояние» допиши, что выполнены интерфейс окна (3.5), JUnit (3.10), запоминание состояния (1.15), кэш фильтра (2.9), комментарии классов (4.5), разделы README (4.2), формат CHANGELOG (4.4) и проверка ключей (4.8). Отметь `- [x]` у пунктов `3.10`, `3.5`, `4.1–4.8`.

Коммит: `Keep the version in gradle.properties; ship the license in the jar`.

Версию не повышай и выпуск не делай — это решение владельца.

---

## Чего в этом плане нет

Не делай этого, даже если останется время. Каждая из этих работ требует решений, которых здесь нет.

- Модель сообщений (`message`, всплывающее сообщение, кнопка «Показать файл»).
- Перенос логики из отрисовки в `beforeRender`.
- Перенос `applyDialog`, `updateWindowWidgets`, `enterInField`, `openDialog` в `Window`.
- Независимые тесты с отдельным контроллером на каждый сценарий.
- Подключение `gametest/` и `tools/XaeroApiCheck.java` к Gradle и CI.
- Переименование `AutosaveSettings`, слияние файлов настроек.
- Новые документы (`CONTRIBUTING.md`, `docs/ARCHITECTURE.md`, `docs/STORAGE.md`, `docs/COMPATIBILITY.md`).
- Удаление веток, повышение версии, выпуск.
- Серверная часть и всё из `docs/ROADMAP.md`.

## Отчёт в конце

Когда закончишь или остановишься, напиши:
- какие задачи закоммичены (номер и хеш);
- на какой задаче остановился и почему;
- вывод последней сборки (строка `BUILD …` и число `PASSED`).
