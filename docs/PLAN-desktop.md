# Plan: Flux for Windows (desktop version)

For an agent building the PC version of Flux. Written 2026-11-16, against `main` at `d60f979`
(v0.1 schematic sync implemented on Android, not released).

## 0. Ground rules from the user

- **Android is the main version.** All new features are built there first. The desktop version
  is a follower: it is updated to match Android **only when the user asks** ("update the PC
  version"). Between those requests it may lag behind; that is expected.
- Android work must never be slowed or blocked by the desktop code: the Android gate
  (`./gradlew test lint assembleDebug`) must not build or test the desktop module.
- No synchronisation of annotations between PC and Android (not now). The desktop stores its own
  data in its own format.
- Decision already made (2026-11-16): a **Windows executable** built with **Compose
  Multiplatform for desktop (JVM)**, not a web app. Reasons: `core/model` and `core/parser` are
  pure Kotlin/JVM and reuse unchanged; the Compose UI and render code port with small changes;
  files are opened from the local disk without browser limits. A web version (Kotlin/Wasm) would
  need the parsers ported off `java.util.zip`, `java.io` and JVM charsets first; not planned.

## 1. Read first

1. `CLAUDE.md`: environment (JDK 17 path, git, gh), commands, rules, pitfalls, how this user
   works (commit when asked; push/release only when asked; English or Portuguese).
2. `docs/ARCHITECTURE.md`: modules, data flow, drawing order, gestures, persistence.
3. `CHANGELOG.md` and `ROADMAP.md` to know what the Android version currently does.

Facts that matter here:

- Units: µm `Long`, Y up, everywhere outside drawing; only `ViewTransform` converts to screen.
- `samples/real/` is private and gitignored: never commit it. `samples/synthetic/` are committed
  fixtures and in-app demos.
- Unlike Android, **you can run the desktop app on this machine**, and you can render Compose UI
  off-screen to PNG (`ImageComposeScene`) and look at the result. Use that to verify screens.

## 2. What the Android code looks like (and what is Android-only)

| Module | Lines | Android-specific | Desktop strategy |
|---|---|---|---|
| `core/model` (Kotlin JVM) | ~970 | none | **Shared**: depend on the project directly |
| `core/parser` (Kotlin JVM) | ~2,160 | none (XML via `XmlParserFactory`; kXML in tests) | **Shared**: depend on it; use kXML at runtime |
| `core/render` (Android library) | ~2,010 | `android.graphics.Paint/Typeface/Canvas` for text (BoardCanvas labels and badges, SchematicCanvas texts), `Bitmap` (Thumbnail), `MotionEvent` (StylusInput) | **Ported copy** in the desktop module |
| `app` (Android app) | ~4,690 | Activity, `AndroidViewModel`, Room, DataStore + SharedPreferences, SAF picker/`Uri`, `PdfRenderer`, `BitmapFactory`, `DateUtils`, `BackHandler`, `android.util.Xml` | **Ported copy** of the UI and view model with a desktop platform layer |

Why ported copies instead of turning `core/render` and `app` into Kotlin Multiplatform modules:
a shared KMP module would make every Android change also compile (and be tested) for desktop,
which is exactly what the user does not want. Copies are synchronised on request with a recorded
baseline commit (section 6). If syncing becomes painful later, converting `core/render` to KMP
is the next step; propose it to the user, do not do it unasked.

Versions in use (gradle/libs.versions.toml): Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01,
coroutines 1.9.0, JDK 17 toolchain. Pick the **Compose Multiplatform** plugin version that is
compatible with Kotlin 2.0.21 (check JetBrains' compatibility table; Kotlin's
`org.jetbrains.kotlin.plugin.compose` plugin is already in the catalog). Do not bump Kotlin or
AGP for Android just to suit desktop; if a bump is unavoidable, ask the user first.

## 3. Module layout

```
desktop/                         new Gradle module, Kotlin JVM + Compose Multiplatform desktop
  build.gradle.kts
  SYNC.md                        Android commit the desktop matches + port notes (section 6)
  src/main/kotlin/com/pcbsolderview/desktop/
    Main.kt                      application { Window(...) }, menu bar, file associations/args
    platform/                    files, storage, settings, PDF, thumbnails (desktop replacements)
    render/                      port of core/render (package com.pcbsolderview.render kept)
    ui/                          port of app/ui
    BoardViewModel.kt            port of app/BoardViewModel.kt (plain class + CoroutineScope)
  src/test/kotlin/...            JVM tests + off-screen screenshot tests
```

- `settings.gradle.kts` already includes `:desktop` **only when asked for** (added 2026-11-16, active
  once `desktop/build.gradle.kts` exists), so the Android gate never
  sees it:
  ```kotlin
  if (providers.gradleProperty("desktop").isPresent) include(":desktop")
  ```
  The user switches with `dev pc` / `dev android` (`dev.cmd`, `scripts/dev.ps1`); `dev build | run | test`
  in pc mode already call the `:desktop:` tasks below, so keep those task names working.
  Desktop commands then are `./gradlew -Pdesktop :desktop:run`, `:desktop:test`,
  `:desktop:createDistributable`, `:desktop:packageMsi`. Put them in CLAUDE.md.
- Dependencies: `project(":core:model")`, `project(":core:parser")`, `compose.desktop.currentOs`,
  `compose.material3`, `compose.materialIconsExtended`, kotlinx-coroutines-swing, kxml2 (+ xmlpull
  API) for `XmlParserFactory { KXmlParser() }`, kotlinx-serialization-json (storage), Apache
  PDFBox (PDF fallback), JUnit 5.
- Keep package names of ported files equal to Android's (`com.pcbsolderview.render`,
  `com.pcbsolderview.ui`) so diffs between the two copies stay readable during syncs.

## 4. Porting map (Android file → desktop replacement)

Render (`core/render` → `desktop/render`), copy then change only what is listed:

| Android | Desktop change |
|---|---|
| `ViewTransform`, `GridIndex`, `ComponentIndex`, `CopperIndex`, `BoardPicker`, `Grid`, `BodyStyle`, `Annotations`, `SchematicColors`, `SchematicScene`, `BoardScene` | Copy unchanged (Compose `Path` is multiplatform). Copy their JVM tests too. |
| `BoardCanvas`, `SchematicCanvas` text drawing (`android.graphics.Paint`, `Typeface`, `nativeCanvas.drawText`) | On desktop `nativeCanvas` is a Skia canvas: use `org.jetbrains.skia.Font` / `Typeface` (`FontMgr.default.matchFamilyStyle`) and `TextLine`, cached per size/style, or Compose `TextMeasurer`. Keep the same anchoring rules (baseline offsets, upright text, overbars, LOD thresholds). |
| `ViewGestures` | Keep the touch logic (Windows touch screens, pens). Add mouse: wheel = zoom at cursor (`PointerEventType.Scroll`), left drag = pan, middle/right drag = pan, Ctrl+wheel or Shift+drag = rotate in 90° steps or free with snapping, double-click = fit. Long-press stays for touch; on mouse the status ring opens with right-click on a part. |
| `StylusInput` | Drop (no S Pen side button). Pen tool still draws with the mouse/pen. |
| `Thumbnail` (`android.graphics.Bitmap`) | Render with Compose `ImageBitmap` + `CanvasDrawScope`, save PNG via Skia `Image.encodeToData`. |

App (`app` → `desktop`):

| Android | Desktop replacement |
|---|---|
| `MainActivity` | `Main.kt`: `application { Window(title = "Flux", state = rememberWindowState(...)) }`, remembered size/position, minimum size, menu bar (File: Open…, Open recent, Close board; View: Board / Split / Schematic, Fit, Flip, Realistic view; Help: About with version and synced Android commit), keyboard shortcuts (Ctrl+O, Ctrl+F search, Esc = Back, Ctrl+Z undo, F = fit, 1/2/3 = layouts), command-line arguments (files to open), drag-and-drop of files onto the window. |
| `AndroidViewModel` + `viewModelScope` | Plain class owning a `CoroutineScope(SupervisorJob() + Dispatchers.Main)` (Swing main dispatcher), cancelled on window close. Keep `UiState` and every function name identical so syncs are mechanical. |
| SAF `Uri`, `OpenableColumns`, `contentResolver` | `java.awt.FileDialog` (native, multi-select, filters .cvg/.xml/.csv/.txt/.xlsx/.zip/.SchDoc/.PcbDoc/.pdf) → `InputFile(name, bytes)`. Also accept a folder: open every supported file in it (an Altium project folder). |
| `android.util.Xml` | `XmlParserFactory { KXmlParser() }`. |
| Room (`AnnotationDb`: marks, strokes, recents) | JSON files with kotlinx.serialization under `%APPDATA%\Flux\` (`boards\<sha1(key)>\annotations.json`, `recents.json`). Same keys (`"${source}:${name}:${parts}"`) and the same fields. Writes atomic (temp file + move). No migrations needed for the first version; add a `version` field. |
| DataStore + SharedPreferences (`AppearancePrefs`) | One `settings.json` (same `Appearance` fields) read synchronously at start, written on change. |
| `BoardStore` (`Context.filesDir`) | Same logic with `%APPDATA%\Flux\boards\`. |
| `PdfPane` (`PdfRenderer`) | Apache PDFBox `PDFRenderer.renderImageWithDPI` → `BufferedImage.toComposeImageBitmap()`, rendered off the UI thread, page tabs as on Android. |
| `BitmapFactory` (recents thumbnails) | `org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()`. |
| `DateUtils.getRelativeTimeSpanString` | Small helper ("5 min ago", "yesterday", date). |
| `BackHandler` | Esc key handler with the same priority order (panels, selection, drawing tool, leave board). |
| `LocalHapticFeedback` | No-op. |
| Theme (`Theme.kt`), screens, panels | Copy. Check sizes: Android uses 52 dp touch targets; on desktop keep them (works with touch screens) but allow a "compact" density setting later if the user asks. |

Everything not listed: copy unchanged and fix compile errors only.

## 5. Phases (each ends with tests, a run, and a commit when the user asks)

**D1 — skeleton (≈1 day).** `desktop` module behind `-Pdesktop`, `Main.kt` window, opens files via
the dialog, parses with the shared importer, shows board name, part count and the import notices.
Done when `./gradlew -Pdesktop :desktop:run` opens the RP2040 `.cvg` and the Android gate is
unchanged (run it to prove the desktop module is not included).

**D2 — board view (≈1–2 days).** Port render (section 4), `BoardPane`, top bar, search, details,
nets, layers, settings, highlight chip, status paint, pen/eraser with the mouse, undo, mouse
gestures. Done when an off-screen render of the IEPE and RP2040 boards matches the parser's
`BoardPng` previews in layout (read both PNGs), and JVM tests pass.

**D3 — persistence (≈1 day).** JSON storage for annotations, recents with copies of the files and
thumbnails, settings. Done when a board reopened from recents shows its status paint and drawings
(test with a temporary `%APPDATA%` directory via a system property).

**D4 — schematic and PDF (≈1 day).** Port `SchematicPane`, `SplitPanes`, `SymbolPanel`,
`SchNetPanel`, follow selection, PDF pane via PDFBox. Done when the "Board + schematic" demo and
the RP2040 `.cvg` + `.SchDoc` show split view with shared selection (off-screen render), and the
RP2040 PDF renders.

**D5 — packaging (≈0.5–1 day).** `compose.desktop.application { nativeDistributions {
targetFormats(Msi, Exe); packageName = "Flux"; packageVersion = <Android versionName mapped to
x.y.z>; windows { menuGroup = "Flux"; upgradeUuid = <fixed UUID, never change it>; shortcut =
true; dirChooser = true }; icon from the launcher icon (convert to .ico) } }`. First deliverable:
`createDistributable` (a folder with `Flux.exe` and the bundled runtime, no install, no admin
rights). Then `packageMsi`. Note for the user: unsigned installers trigger a Windows SmartScreen
warning; signing needs a certificate they would have to buy, do not set it up unasked.
File associations (.cvg, .SchDoc, .PcbDoc) only if the user wants them.

Total ≈ 5–6 days of agent work.

## 6. Sync procedure ("update the PC version")

`desktop/SYNC.md` records:

```
Synced with Android: <commit hash> (<Android versionName>, <date>)
Desktop version: <x.y.z>
Not ported (by design): S Pen side button, haptics, Android-only settings …
Open differences: …
```

When the user asks to update the PC version:

1. Read `desktop/SYNC.md` → baseline commit `B`.
2. `git log --oneline B..HEAD` and `git diff --stat B..HEAD -- core app samples/synthetic` to see
   what changed on Android; read the CHANGELOG entries since then.
3. `core/model`, `core/parser`: shared, nothing to port. Build and run `:desktop:test`; fix
   desktop call sites if APIs changed.
4. `core/render` and `app`: for each changed file, apply the same change to its desktop copy
   (`git diff B..HEAD -- <android file>` is the patch to port; most hunks apply unchanged because
   the copies keep the same package and names). Skip Android-only changes and list them in
   SYNC.md under "Not ported".
5. New Android features that need a new platform piece (e.g. a new Android API): implement the
   desktop equivalent from section 4's patterns, or ask the user if it makes no sense on PC.
6. Run desktop tests, the off-screen screenshot tests, `:desktop:run` smoke test, and the
   **Android gate** too (it must still pass; you should not have touched Android code).
7. Update `SYNC.md` (new baseline = current Android HEAD), bump the desktop version, rebuild the
   distributable/installer, commit "Desktop: sync with Android <short hash>". Release to GitHub
   only when asked (attach `Flux-<version>-windows.msi` or a zipped distributable).

The other direction (rule from 2026-11-16): every PC-only change that Android could also have is
listed in `SYNC.md` › "PC features not on Android yet" in the same commit; `dev android` prints the
list, and the agent shows it when Android work starts (see CLAUDE.md).

## 7. Documentation to update when the desktop module lands

- CLAUDE.md: module map row for `desktop`, the `-Pdesktop` commands, the rule "Android first;
  desktop follows on request via desktop/SYNC.md", and that the Android gate must not include it.
- docs/ARCHITECTURE.md: a "desktop" section (module, platform layer, storage location, what
  differs from Android).
- README: how to run/install on Windows.
- CHANGELOG: a separate "Desktop" heading per desktop release, so Android entries stay clean.
