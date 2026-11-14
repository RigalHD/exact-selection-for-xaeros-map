# План: остаток этапа 5 (пункты 3.6–3.9 аудита)

Продолжает `docs/junior-plan/PLAN.md`. Состояние на начало: ветка `feature/zones-routes-cleanup`, коммит `150ab18`, `./gradlew build` проходит, 39 тестов `PASSED`.

Пункты аудита: 3.6 «части правят поля друг друга», 3.7 «строка `message` служит и текстом, и состоянием», 3.8 «логика в отрисовке», 3.9 «имена». Задача 4 прошлого плана уже закрыла три примера из 3.6 (`resetForOpening`, `askDeleteFromMenu`, `announceFile`) и один из 3.8 (кнопка удаления в окне вейпоинтов).

## Итог выполнения

Сделаны A, B1–B3, C и D1–D4. Отступления от плана:

- **C сделана вторым заходом.** Первая попытка сломала раскладку, поэтому кнопка «Показать файл» получила своё место: отдельную строку в панели под статусом; в окне «Области» она уступает место кнопке восстановления; в окне «Группы» ниже 262 точек скрыта. В `checkExportNotice` сценарий теперь начинает с `lastExport = null` и проверяет, что кнопку не прячут ни истёкшее, ни постороннее сообщение.
- **A.** Методы названы без совпадений между частями: `closeMenu`, `cancelAreaDelete`, `cancelGroupDelete`, `dropPending`, `showCompact` (не пишет `UiState`: компактный вид линейки не запоминается). `openAreaNameDialog()` без параметра оставлен: его зовёт тест. Добавлены `forgetAreas`, `forgetGroups`, `endPress` для записей из контроллера, которых не было в таблице.
- **B3.** `beforeRender` добавлен только в `GestureChecks`; двум другим тестам он не понадобился.
- **D1.** Вместо нового класса объявлен настоящий тип `IOException`: его и ловят тесты. `SelectionHistory.Action` не тронут: его реализует `SelectionCheck` с `throws Exception`.
- **D2.** `createAreaWidgets` и `createGroupWidgets` не переименованы: имя `createWidgets` занято в `ExportWindow`, а остальные части названы так же.

## 0. Правила

Те же, что в разделе 0 `PLAN.md`: та же ветка, один коммит на задачу, перед каждым коммитом `spotlessApply`, `build`, `check_localization.py`, `graphify update .`. Остановиться и спросить, если сборка красная после двух попыток или текст-якорь не находится.

**Тесты.** Отличие от прошлого плана: здесь есть задачи, которые по замыслу меняют поведение, а значит и тесты. Менять тест можно только в задаче, где он назван, и только так, как там написано. Во всех остальных задачах тесты не трогаются, а упавший тест значит, что ошибка в коде.

**Имена.** Тесты ищут поля и методы по имени через `UiHarness.holders` (по всем частям сразу, `setAccessible(true)`). Поэтому:
- поле можно сделать `private`: тесты его всё равно найдут;
- переименовать поле или метод можно только в задаче D2 и только вместе со строкой в тестах;
- новое имя не должно совпадать с именем в другой части.

## 1. Решения владельца

**Принято владельцем 2026-11-11: Р1 (а), Р2 (а), Р3 (а), Р4 (а).** Значит, делаются все задачи; D3 без видимых текстов, D4 только переименование.

Задачи A, B1, B2 и D1 решений не требовали. Ниже варианты, из которых выбирали.

**Р1. Кнопка «Показать файл» (3.7).** Сейчас она видна, пока `ui.message` равно тексту об экспорте; любое следующее сообщение («Скопировано») прячет её навсегда.
- (а) **Рекомендую:** кнопка живёт до следующего экспорта, до нажатия на неё с удалённым файлом или до выхода из мира. Текст внизу экрана гаснет через 6 с, как сейчас, и на кнопку не влияет.
- (б) Кнопка живёт фиксированное время, например 60 с.
- (в) Оставить как есть, 3.7 не делать.

**Р2. Второй угол рамки двигается в отрисовке (3.8).** `drawMapOverlays` вызывает `state.hover(…)` и `syncFields()`. Перенести в `beforeRender` можно, но три теста зовут только `renderMapOverlays` и ждут, что угол сдвинулся: `GestureChecks.java:114`, `AreaChecks.java:276`, `ToolChecks.java:138`.
- (а) **Рекомендую:** перенести; в этих трёх местах перед `renderMapOverlays(g, x, y)` добавить `controller.beforeRender(x, y)` с теми же координатами.
- (б) Не трогать (так решил прошлый план).

**Р3. Имена (3.9).**
- (а) **Рекомендую:** переименовать поля и внутренние ключи, а видимые игроку тексты не менять. «Delete zone» и «Edit territory» останутся как есть: в английском «zone» и «territory» стоят в README и в CHANGELOG.
- (б) То же, плюс видимые тексты: «Delete zone» → «Delete area», «Edit territory» → «Edit shape». Это затронет README и CHANGELOG.
- (в) Не переименовывать: 3.9 свести к исключениям (D1) и настройкам (Р4).

**Р4. Файлы настроек (3.9).** Сейчас их три: `settings.json` (`AutosaveSettings`: автосохранение и округление), `export.json` (`ExportPreferences`), `ui.json` (`UiState`, задача 1).
- (а) **Рекомендую:** только переименовать класс `AutosaveSettings` → `ModSettings`; файлы оставить. Миграции нет, ничего не потеряется.
- (б) Слить в один `settings.json` с разделами `autosave`, `copy`, `export`, `ui`: при первом запуске прочитать старые файлы, записать новый, старые удалить. Нужен новый тест миграции.

## Задача A. Чужие поля пишет только владелец (3.6)

Каждая строка таблицы — запись в поле другой части. Добавь владельцу метод из столбца «Метод», тело — ровно то, что сейчас делают строки в столбце «Где», и замени эти строки вызовом. Поведение не меняется, тесты не трогаются.

| Поле | Где | Метод у владельца |
|---|---|---|
| `rightClickMenu.quickMenu = false` | `ColorWindows:126`, `InputRouter:45`, `:98`, `:215` | `RightClickMenu.close()` |
| `polygonTool.mode = false` | `AreasWindow:286`, `InputRouter:227`, `RulerTool:41` | `PolygonTool.turnOff()` (только `mode = false`) |
| `polygonTool.draftUndo.clear(); draftRedo.clear(); stateBefore = null` | `Windows:23–25`, `GroupsWindow:201–203` | `PolygonTool.forgetDraftHistory()` |
| `newAreaWindow.newAreaColor = color` | `ColorWindows:283`, `:375` | `NewAreaWindow.useColor(int)` |
| `newAreaWindow.areaNameReturn = …` + `openAreaNameDialog()` | `AreasWindow:962–963`, `SelectionPanel:609–610` | `openAreaNameDialog(Dialog returnTo)`; старый метод без параметра удалить |
| `groupsWindow.groupId = null` | `AreasWindow:225` | `GroupsWindow.dropMissingGroup()` |
| `groupsWindow.groupDeleteConfirmation = false` | `AreasWindow:197`, `Windows:81` | `GroupsWindow.cancelDeleteConfirmation()` |
| `areasWindow.areaDeleteConfirmation`, `areaDeleteFromMenu` | `Windows:70–76` | `AreasWindow.cancelDeleteConfirmation()`: снимает подтверждение, при `areaDeleteFromMenu` закрывает окно |
| `areasWindow.selectedAreaIds`, `areaId`, `areaScroll` | `NewAreaWindow:145–162` | `AreasWindow.checkOnly(Area area)` и `AreasWindow.scrollTo(Area area)` |
| `colorWindows.pickerDrag = NONE` | `InputRouter:75`, `ExactSelectionController:661` | `ColorWindows.endPickerDrag()` |
| `waypointsWindow.scrollbarDragging = false` | `InputRouter:80`, `ExactSelectionController:657` | `WaypointsWindow.endScrollbarDrag()` |
| `waypointsWindow.pending = null` | `Windows:85` | `WaypointsWindow.close()` |
| `selectionPanel.compact`, `panelScroll` | `RulerTool:54–55` | `SelectionPanel.setCompact(boolean)`; она же пишет `uiState.compactPanel` |
| `uiState.*` | `NewAreaWindow:165–167`, `SelectionDialogs:258`, `:265`, `SelectionPanel:67` | не трогать: `UiState` — хранилище, его поля пишут все |

Когда таблица закрыта, сделай `private` поля, которые теперь пишет только владелец и никто не читает снаружи (компилятор покажет, если читает). Поля, которые снаружи читают, оставь как есть: геттеры здесь не нужны.

Проверка, что задача закрыта; команда должна вывести только строки с `uiState`:

```bash
grep -nE "ui\.[a-zA-Z]+\.[a-zA-Z]+(\[[^]]*\])?\s*(=[^=]|\+=|-=|\+\+|--)" src/main/java/dev/exactselection/*.java | grep -v "\.visible\s*=\|\.active\s*="
```

Коммиты: по одному на владельца, например `Let the polygon tool reset its own state`.

## Задача B. Отрисовка только читает (3.8)

**B1. Прокрутка списков.** Пять методов отрисовки поправляют прокрутку:

| Метод | Строка |
|---|---|
| `AreasWindow.renderAreasDialog` | `areaScroll = Math.min(…)` |
| `GroupsWindow.renderGroupsDialog` | `groupScroll = Math.min(…)` |
| `GroupsWindow.renderGroupChooser` | `assignmentScroll = Math.min(…)` |
| `TransferWindow.renderImportDialog` | `importScroll = Math.min(…)` |
| `WaypointsWindow.renderWaypointsDialog` | `modalScroll = Math.min(…)` |

Перенеси каждую строку в `layout` объекта `Window` того же окна: `layoutDialog` вызывается в каждом кадре перед отрисовкой (`updateVisibility` → `layoutDialog`). Число строк считай тем же методом, что и в отрисовке (`visibleAreaDialogRows(r.height())` и т. п.). Коммит: `Keep the lists' scroll in layout, not in drawing`.

**B2. Кнопка разворота.** `drawInterface` ставит `selectionPanel.toggle.active`. Перенеси строку в `updateVisibility`. Коммит: `Set the toggle state with the other widgets`.

**B3. Второй угол рамки** (если Р2 = а). Блок `if (state.phase() == SelectionState.Phase.PREVIEW && …) { … }` из `drawMapOverlays` перенеси в конец `beforeRender`, перед `lastMouseX = mouseX`. `frame`, `mouseX`, `mouseY` там уже есть. Затем поправь три теста из Р2. Коммит: `Move the selection corner before drawing`.

**B4.** Гашение сообщения в `renderToast` уходит в задачу C.

## Задача C. Уведомления (3.7), если Р1 = а или б

1. В `MapExport` вместо пары `exportNotice` + сравнения строк — поле `Path lastExport` (имя оставить, его читают тесты) и `long shownUntil` для Р1 (б). `hasExportNotice()` возвращает `lastExport != null` (для (б) — `&& now < shownUntil`).
2. `announceFile` и `exportCompleted` больше не пишут `exportNotice`; текст идёт через `ui.message`, как любое сообщение.
3. `openLastExport()` при удалённом файле обнуляет `lastExport` (сейчас кнопку прячет смена сообщения на `export_file_missing`). Выход из мира отдельной работы не требует: `ExactSelectionController.forget()` отпускает весь контроллер вместе с `MapExport`.
4. `SelectionPanel.renderToast`: убрать ветку `if (!ui.mapExport.hasExportNotice())`, текст гаснет всегда. Гашение (`ui.message = ""`) перенести из отрисовки в `beforeRender`-подобный метод `SelectionPanel.expireToast(long now)`, который вызывается из `updateVisibility`. Поля `toastText` и `toastShownAt` оставить с теми же именами.
5. Тесты `WindowChecks.checkExportNotice`, строки 31–46: заменить проверку «кнопка видна, пока сообщение не сменилось» на «после истёкшего текста и после постороннего сообщения `x` кнопка всё ещё видна». Остальные проверки метода (путь файла, маленькие экраны, удалённый файл) не трогать.
6. Поле `exportNotice` удалить (его читают только `MapExport` и тест из шага 5).
7. CHANGELOG, раздел `### Changed`: `- The Show file button stays until the next export instead of disappearing with the next message.`

Коммит: `Keep the Show file button apart from the message line`.

## Задача D. Имена (3.9)

**D1. Исключения** (без решения). Новый класс `WaypointWriteException extends Exception` (сообщение + причина). `WaypointAccess` и `SafeWaypointAccess` объявляют его вместо `throws Exception`; `XaeroWaypointBridge` оборачивает в него исключения Xaero. `SelectionHistory.Action` (`undo()`/`redo()` объявляют `throws Exception`): посмотреть, что реально бросают его реализации; если только `WaypointWriteException` и `IOException` хранилища, объявить их, иначе оставить как есть и записать причину в отчёт. `SafeWaypointAccess` по-прежнему ловит `LinkageError` (на нём держится проверка неподдерживаемой миникарты). Коммит: `Name the exceptions of waypoint writes`.

**D2. Поля и методы** (если Р3 = а или б). Каждое переименование — вместе с тестами (`grep -rn '"<старое>"' src/test`):

| Было | Стало | Строк в тестах |
|---|---|---|
| `SelectionPanel.zonesButton` | `areasInSelectionButton` | 2 |
| `AreasWindow.areaSave` | `saveSelectionButton` | 4 |
| `AreasWindow.areaLoad` | `editShapeButton` | 13 |
| `AreasWindow.areaDelete` | `deleteButton` | 2 |
| `RulerTool.rulerDistance` | `distance` | 5 |
| `RulerTool.rulerRoute` | `route` | 0 |
| `createAreaWidgets`, `createGroupWidgets` | `createWidgets` в своих классах | 0 |

Перед каждым переименованием проверь, что нового имени нет ни в одной части (`grep -rnw <новое> src/main`). Коммит на строку таблицы.

**D3. Ключи переводов** (если Р3 = а или б). Внутренние ключи: `zones_in_selection*` → `areas_in_selection*`, `quick_menu_delete` → `quick_menu_delete_area`. Менять в `en_us.json`, `ru_ru.json`, коде и тестах одновременно; `check_localization.py` должен остаться `PASS`. Для Р3 (б) ещё и английский текст двух ключей, README и CHANGELOG (`### Changed`). Коммит: `Name the language keys after areas`.

**D4. Настройки.** Р4 (а): переименовать `AutosaveSettings` → `ModSettings` (файл, класс, все упоминания и javadoc), коммит `Rename AutosaveSettings to ModSettings`. Р4 (б): дополнительно `ModSettings` владеет одним файлом `settings.json` с разделами `autosave`, `copy`, `export`, `ui`. `ExportPreferences` и `UiState` становятся его разделами. Миграция: если в файле нет раздела `export` или `ui`, прочитать `export.json` / `ui.json`, записать общий файл, старые удалить. Новый тест в `SessionChecks`: старые три файла → один новый с теми же значениями, старых нет. Строка в CHANGELOG, `### Changed`.

## Порядок

A → B1 → B2 → C → B3 → D1 → D2 → D3 → D4. В конце в `docs/AUDIT_PLAN.md` отметить `- [x] 3.6–3.9`, в абзаце «Состояние» исправить фразу «Этапы 5–6 не начаты» на фактическое состояние.

## Чего в этом плане нет

- Перенос `openDialog`, `applyDialog`, `updateWindowWidgets`, `enterInField` в `Window`.
- Независимые тесты с отдельным контроллером на сценарий.
- `InputRouter.keyPressed` и клавиши Xaero (отложено владельцем).
- Подключение `gametest/` и `XaeroApiCheck` к CI (3.11), выпуск 1.4.0.
