Synced with Android: 4b6b4cd (in-app updates, 2026-11-26)
Desktop version: 0.1.4-test1

# Desktop ↔ Android sync record

The first line is read by the build (Help → About shows it) and by `dev status`. Keep its format.
How to update the PC version: `docs/PLAN-desktop.md`, section 6. The baseline above is the last
Android commit that touched `core/`, `app/` or `samples/synthetic/` and whose changes are in the
desktop copies (`git diff <baseline>..HEAD -- core/render app` is the patch to port next time).

## Layout

`core/model` and `core/parser` are shared (project dependencies). Everything else is a copy with
the Android package names kept, so `git diff --no-index` between the two copies stays readable:

| Android | Desktop copy |
|---|---|
| `core/render/src/main/kotlin/com/pcbsolderview/render/*` | `desktop/src/main/kotlin/com/pcbsolderview/render/*` |
| `app/src/main/java/com/pcbsolderview/ui/*` | `desktop/src/main/kotlin/com/pcbsolderview/ui/*` |
| `app/src/main/java/com/pcbsolderview/BoardViewModel.kt` | `desktop/src/main/kotlin/com/pcbsolderview/BoardViewModel.kt` |
| `app/src/main/java/com/pcbsolderview/data/*` | `desktop/src/main/kotlin/com/pcbsolderview/data/*` (rewritten, same API) |
| `MainActivity.kt` | `Main.kt` (window, menu bar, keys, drag and drop) |
| (Android framework) | `platform/` (folders, dialogs, PDF, window prefs, demos) |
| JVM tests of `core/render` and `app` | `desktop/src/test/...` (same files) |

## Changed in the copies (re-apply when porting Android changes)

- `render/AndroidGraphics.kt` (desktop only): `Paint`, `Typeface`, `GraphicsColor`, `NativeCanvas`
  and `drawText/drawCircle/drawLine` on the Skia canvas, so `BoardCanvas`/`SchematicCanvas` text code
  stays as on Android. In those two files only the `android.graphics.*` imports were removed and
  `android.graphics.Color.` → `GraphicsColor.`, `android.graphics.Canvas` → `NativeCanvas`.
- `render/ViewGestures.kt`: + `mouseGesture` (right button = long press, middle = pan, left = pan /
  draw / erase, Shift+drag = rotate, double click = `onDoubleTap`), + wheel zoom / Ctrl+wheel rotate,
  mouse hover reported through `onHover`; `stylusButton()` is always false; `StylusInput` dropped.
- `render/Thumbnail.kt`: Skia surface, returns `org.jetbrains.skia.Image`.
- `BoardViewModel.kt`: plain class with its own scope (`close()`), `open(files: List<File>)`
  (folders expanded by `SupportedFiles`), `openDemo` reads `Demos` (resources), kXML parser.
- `data/`: JSON files in `%APPDATA%\Flux\` instead of Room/DataStore, same entity classes and DAO
  function names. Annotations: `annotations\<sha1(boardKey)>.json` (kept apart from
  `boards\<sha1>\`, which is rewritten on every open, as on Android). Settings: `settings.json`.
- `ui/BoardScreen.kt`: `BackHandler` comes from `ui/DesktopCommands.kt` (Esc); Fit/Flip/search
  focus are published to the menu bar via `RegisterDesktopCommands`.
- `ui/BoardPane.kt`, `ui/SchematicPane.kt`: double click fits (`onDoubleTap`).
- Hover highlight (desktop only feature, asked for on 2026-11-16): `BoardCanvas` takes `hovered` and
  draws a light outline + tinted pads (`BoardColors.hover`); `BoardPane` computes the part under the
  mouse once (also used by the hover label) and passes it in inspect tools only.
- Selection pulse (desktop only, 2026-11-16): `SELECTION_PULSE_MS` = 250 ms (Android 900) and a
  thinner glow (6–11 px, Android 10–28 px) in `BoardCanvas` and `SchematicCanvas`.
- Mirror: same as Android (Mirror button independent of Flip, "MIRRORED" label); the PC adds
  View › Mirror view and `DesktopCommands.mirror` for the menu.
- Board-sized parts (desktop only, 2026-11-16): `BoardPicker.boardSized` (box ≥ half the board)
  are left out of the box hit-test, so clicking or hovering empty board never selects a module
  footprint as big as the board; its pads still select it.
- Side panel (desktop only, 2026-11-16): opening or closing it does not move the views.
  `BoardScreen` sets `anchorNextResize` on the shown view states; `BoardViewState` /
  `SchematicViewState` then skip the keep-the-centre pan for that one resize.
- `ui/PdfPane.kt`: PDFBox (`platform/Pdf.kt`) instead of `android.graphics.pdf.PdfRenderer`.
- `ui/StartScreen.kt`: thumbnails via Skia, `relativeTime()` instead of `DateUtils`.

- `ui/ChecklistPanel.kt`: "Click ... right-click" wording; right-click on a line = highlight all
  (`onClick` with `PointerMatcher.mouse(Secondary)`), as in the part panel.

- `ui/BoardScreen.kt` `LayoutToggle`: hover tooltip and "click to hide (1)" wording. `Main.kt`:
  keys 1 / 2 toggle Board / Schematic (Android has no keys; 3 is gone) and View menu has two
  check items instead of the three radio items.
- `data/AppearancePrefs.kt`: the schematic appearance keys (`sch_paper`, `sch_ink`,
  `sch_net_color`, `sch_grid`, `sch_grid_step_um`, `sch_text_scale`) in `settings.json`.
- In-app updates (2026-11-26): `core/update` is shared; `update/UpdateController.kt` plays the
  Android `UpdateViewModel` (plain class, own scope, `update.json` instead of SharedPreferences) and
  `update/WindowsInstall.kt` replaces `ApkInstaller`: `RunMode` (development run / MSI / portable,
  from `jpackage.app-path` and `portable.txt`) and the PowerShell helpers `UpdateScripts.msi` /
  `.portable`, which wait for Flux to close, install, and start it again. Older versions install
  in place too ("Go back" with a warning; Android only saves the APK). `ui/UpdatesSection.kt`
  follows Android's, plus `UpdatesDialog` (Help › Check for updates…). `show_test_versions` in
  `settings.json`.

## Not ported (by design)

- "Keep the screen on" (Android setting; a PC screen saver is the user's own setting).
- S Pen side button (`StylusInput`), haptics (Compose desktop's haptic feedback is a no-op).
- Android splash/window themes, "Open with"/share intents (the command line and drag and drop
  play that role), edge-to-edge insets.

## PC features not on Android yet

<!-- `dev android` and `dev status` (Android mode) print this list. Add an item for every PC
     change that Android could also have (date, what it does, where it lives); remove it when it
     is ported to Android or the user decides Android should not have it. -->

## PC only by the user's decision (2026-11-17: "those changes can be only for the pc version")

- 2026-11-16 Hover highlight: the part under the mouse is outlined, with its name and value
  (`BoardCanvas` `hovered`, `BoardPane`). Android equivalent: S Pen hover.
- 2026-11-16 Short, thin selection pulse: 250 ms and a 6–11 px glow (Android: 900 ms, 10–28 px)
  (`SELECTION_PULSE_MS` in `BoardCanvas`, `SchematicCanvas`).
- 2026-11-16 Opening or closing the side panel does not move the board or schematic
  (`anchorNextResize` in `BoardViewState` / `SchematicViewState`, set in `BoardScreen`).
- 2026-11-16 Parts as big as the board (a module footprint) are not selected by clicking empty
  board, only through their pads (`BoardPicker.boardSized`).
- 2026-11-16 Search results open right under the search field instead of in the middle of the
  board area (`searchFieldLeft` in `BoardScreen`).

## PC only by nature (no Android equivalent needed)

- 2026-11-18 Ctrl+drag = box selection (also starting on a part), Ctrl+click = add / remove a part
  from the selection (`onBoxStart` / `onCtrlTap` in `render/ViewGestures.kt` and `BoardCanvas`, wired
  in `ui/BoardPane.kt`; `toggleInSelection` and `SELECTION_KEY` in `BoardViewModel`, box selections
  carry that key so Ctrl+click extends them). Android has box selection by long press.
- Menu bar, keyboard shortcuts, drag and drop, open a folder, files from the command line,
  mouse gestures (wheel zoom, Shift+drag / Ctrl+wheel rotate, double-click fit, right-click ring).
- Design review 2026-11-16: hover tooltips (`TooltipArea` in `BarIcon`); "click" instead of "tap"
  in tooltips and hints; Settings › "Mouse & pen" with one Rotation switch (also gates
  Ctrl+wheel; "Only the S Pen draws" hidden); search field on its own row below 1240 dp (more
  buttons than Android); right-click = "highlight all" in the part panel; View menu checkboxes
  for Tools, Layers & view and Settings (Settings disabled on the start screen).

## Ported from PC to Android

- 2026-11-16 Mirror button (on Android made independent of Flip, with a "MIRRORED" label; the PC
  took that version back in the sync to 4a042b5).
