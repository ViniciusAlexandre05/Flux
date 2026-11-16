Synced with Android: 38d646d (0.1 incl. native .PcbDoc reader, 2026-11-16)
Desktop version: 0.1.0

# Desktop â Android sync record

The first line is read by the build (Help â About shows it) and by `dev status`. Keep its format.
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
  `android.graphics.Color.` â `GraphicsColor.`, `android.graphics.Canvas` â `NativeCanvas`.
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
  thinner glow (6â11 px, Android 10â28 px) in `BoardCanvas` and `SchematicCanvas`.
- Bottom view (desktop only, 2026-11-16): no "BOTTOM VIEW (mirrored)" label in `BoardPane`; a
  Mirror button (top bar, while the bottom is shown) and View › Mirror bottom view toggle
  `UiState.mirrorBottom`; `flipView` / `toggleMirror` in `BoardScreen` apply it.
- Board-sized parts (desktop only, 2026-11-16): `BoardPicker.boardSized` (box ≥ half the board)
  are left out of the box hit-test, so clicking or hovering empty board never selects a module
  footprint as big as the board; its pads still select it.
- Side panel (desktop only, 2026-11-16): opening or closing it does not move the views.
  `BoardScreen` sets `anchorNextResize` on the shown view states; `BoardViewState` /
  `SchematicViewState` then skip the keep-the-centre pan for that one resize.
- `ui/PdfPane.kt`: PDFBox (`platform/Pdf.kt`) instead of `android.graphics.pdf.PdfRenderer`.
- `ui/StartScreen.kt`: thumbnails via Skia, `relativeTime()` instead of `DateUtils`.

## Not ported (by design)

- S Pen side button (`StylusInput`), haptics (Compose desktop's haptic feedback is a no-op).
- Android splash/window themes, "Open with"/share intents (the command line and drag and drop
  play that role), edge-to-edge insets.

## Open differences

- Desktop-only features (not on Android): hover highlight of the part under the mouse, board-sized parts not
  picked on empty board, menu bar,
  keyboard shortcuts, drag and drop, open folder.
- The schematic has no hover highlight yet.
