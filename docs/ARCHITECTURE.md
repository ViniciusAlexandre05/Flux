# Flux architecture

How the code is organised, how data moves through it, and where to change things. Start with
`CLAUDE.md` for environment, commands and rules.

## Data flow

```
files (SAF picker / "Open with" / share / recent copy)
  └─ BoardViewModel.load()  ── Dispatchers.Default
       └─ BoardImporter.import(List<InputFile>)        core/parser
            ├─ classify each file (sniffing, not extensions); .zip is expanded
            ├─ geometry: IPC-2581 → Ipc2581Parser, else Pick & Place → PickPlaceParser
            ├─ every BOM file → Board.withBom(bom)     (fills missing fields by designator)
            ├─ every .SchDoc (native or ASCII) → SchDocParser → Schematic sheets
            └─ ImportResult(board, schematic?, schematicPdf?)
       └─ BoardScene(result.board)                      core/render: cached Paths, indexes
       └─ SchematicScene + SchematicConnectivity + matchBoard()   (when there is a schematic)
       └─ remember(): recent-boards entry + private copy of the files (BoardStore)
  └─ UiState (StateFlow) → BoardScreen → BoardPane | SplitPanes(BoardPane, SchematicPane | PdfPane) | SchematicPane + panels
user actions → BoardViewModel functions → UiState / Room (annotations) / DataStore (settings)
```

## core/model

Pure Kotlin, no Android. **All lengths are micrometres (`Long`), Y axis up** (Altium/IPC frame).

- `Board.kt`: `Board` (outline, layers, tracks, vias, pads, holes, fills, texts, components,
  nets, units), `Component` (refDes, value, description, footprint, partNumber,
  `partNumberFromLibrary`, manufacturer, side, position, rotationDeg, `bounds` = hit box,
  `outline` = package body polygon, libraryRef, heightUm, padIds), `Pad` (shape, size, rotation,
  drill → through-hole, net, layer, componentRef), `Track` (segment or arc), `Via`, `Fill`
  (polygon with holes), `Layer` (type, side, order, rgb), `Rect`, `Point`, `rotatedBox()`.
  `Board.component(refDes)` and `Board.padsOf(component)` are the usual lookups.
- `Geometry.kt`: `arcPoints` (tessellation; start == end means full circle), `Pad.outline()`
  (CCW ring for every shape), `Track.centerline()`, `signedArea`, `oriented()`.
- `Search.kt`: `NaturalOrder` (R2 < R10), `Board.search()` (ranked: exact designator, prefix,
  value, contains), `sameKind()` / `Board.identicalTo()` (same part number, else same
  value + footprint), `Mount` / `MountFilter` / `Board.mountOf()` / `filterByMount()`,
  `Component.matchesQuery()` (all words contained, spaces/case ignored).
- `Schematic.kt`: `Schematic` (sheets; `symbolsOf(refDes)`, `sheetOf(symbol)`, `designators`),
  `SchematicSheet` (paper size/colour, symbols, wires, junctions, net labels, power ports, free
  graphics/texts, no-ERC marks), `SchSymbol` (`refDes` = board designator, `label` = "U1A" for part
  A of a multi-part U1, partId/partCount, comment, description, footprint, pins, graphics, texts,
  parameters, bounds), `SchPin` (`designator` = pad name, `position` = connection point, `base`,
  `angleDeg`, show flags, overlined name), `SchText` (9-point justification, rotation 0/90),
  `SchGraphic` (Poly, Shape, Arc). All in absolute sheet µm, Y up.
- `SchematicLayout.kt`: a sheet resolved into plain fills / strokes / dots / texts (power
  symbols, pin numbers and names, Altium default colours, text owners for fading). Pure, so the
  app canvas and the JVM preview renderer share it.
- `SchematicNets.kt`: `SchematicConnectivity` (nets from wires, junctions, pins, labels, power
  ports; same name joins across sheets; Altium auto names `NetR1_2`; pins identified per
  symbol), `netOf(PinRef)`, `netAt(sheet, x, y)`, `matchBoard(board)` (board net → schematic net
  holding most of its pads). `PinRef(refDes, pin)`, `SchNet`.

## core/parser

Pure Kotlin. Entry point: **`BoardImporter(xml: XmlParserFactory).import(files, onNotice, onProgress)`**
(`onProgress` last, so a trailing lambda is progress). It returns an **`ImportResult`**: the
`board`, the `schematic` (every .SchDoc chosen, one sheet each) and `schematicPdf` (a PDF chosen
without a .SchDoc). Only a schematic or PDF gives an empty board (`SourceFormat.SCHEMATIC_ONLY`).

- `classify()`: OLE compound-file magic (`D0 CF 11 E0…`) → `ALTIUM_PCBDOC`/`ALTIUM_SCHDOC` by
  extension, else by record text; `%PDF-` → PDF; `|HEADER=Protel for Windows - Schematic` text →
  ASCII `ALTIUM_SCHDOC` (`|RECORD=Board`, `Protel for Windows - Schematic`, `|RECORD=31|`),
  else UNKNOWN; `PK` header → XLSX (BOM) if it contains `xl/`, else ZIP; text starting with `<`
  and containing `IPC-2581`/`<Content` → IPC2581; otherwise tries a Pick & Place header, then a
  BOM header. Files it doesn't recognise are ignored (no error).
- Board geometry precedence: IPC-2581 > `.PcbDoc` > Pick & Place. A `.PcbDoc` next to an IPC-2581
  file is not used (notice); a damaged one falls back to a Pick & Place file or the schematic with
  a notice, alone it is an error suggesting IPC-2581 (`NativeFileMessages`, `ParseErrors.kt`). A damaged `.SchDoc` next to other files is skipped with a notice too. `BoardViewModel.load()`
  shows notices as the on-screen message (not when reopening a recent). The app picker is
  `OpenMultipleDocuments`, so several files (or a .zip) can be chosen at once.
- `altium/PcbDocParser` (S5b): native `.PcbDoc` → `Board` with IPC-2581 conventions. Text streams
  `Board6` (outline vertices, V9 layer stack), `Components6`, `Nets6`, `Polygons6` (pour nets);
  binary streams read with `BinaryReader` (bounded little-endian cursor; `sub()` = `u32 length` +
  payload): `Pads6`, `Vias6`, `Tracks6`, `Arcs6`, `Fills6`, `Regions6` (pours, graphics; f64
  vertices), `ComponentBodies6` (outline, `OVERALLHEIGHT`), `Texts6` (only each part's designator
  and comment). Units 1/10000 mil (`UNIT_UM`); layer ids are Altium's legacy numbers (`layerInfo`).
  Record layouts are in its KDoc. Through-hole pads become one `Pad` per copper layer; rounded
  rectangles come from pad sub-record 6 (alt shape 9 + corner %). Designators come from the board
  text (duplicates get `_2`); the value is the comment text, else derived like IPC. Damaged or
  unknown records are skipped and counted in one notice. `Rp2040PcbDocTest` compares it with the
  IPC-2581 export of the same board; `PcbDocParserTest` uses synthetic files (`PcbDocWriter`).
- `altium/`: `CompoundFile` (MS-CFB reader: FAT, DIFAT, mini stream, storages; streams by path,
  case-insensitive), `AltiumRecords` (`|KEY=value|` records from native streams, 4-byte length
  headers, binary records kept as empty placeholders so OWNERINDEX stays right; or from ASCII
  lines; keys case-insensitive; `%UTF8%` copies; line breaks → spaces), `SchDocParser`
  (records → `SchematicSheet`; coordinates are 10 mil + `_FRAC`/100000; owner tree by
  OWNERINDEX, counted from 0 after the header; children of other parts / display modes are
  dropped; "=Value" parameters and "=Title" sheet strings resolved; pin connection point =
  LOCATION + PINLENGTH along `PINCONGLOMERATE and 3`). Record numbers are listed in its KDoc.
- `ipc2581/Ipc2581Parser.kt`: single streaming pass (XmlPullParser) that records raw elements,
  then `build()` resolves them. Altium dialect handled:
  - Pads come from `Step/PadStack/LayerPad` (absolute `Location`, `Xform` rotation, primitive
    from `DictionaryStandard`/`DictionaryUser`, `PinRef` → component/pin). Only copper layers
    make `Pad`s. A PadStack with a `LayerHole platingStatus="VIA"` becomes a `Via`.
  - Nets: `PadStack net=` / `Set net=` ("No Net" → null), plus `LogicalNet` when present.
  - Features: `LayerFeature/Set/Features` Line, Arc (start == end = full circle), Polyline,
    Contour (Polygon + Cutouts) — wrapped or not in `UserSpecial`.
  - Components: package outline transformed by `Xform` (scale, mirror X, rotate CCW, offset)
    then `Location`; bottom parts mirror when `mirror` is absent. If a component has no
    PadStack pads, pads are synthesised from the package pins.
  - BOM: no value field in Altium exports → `deriveValue()` from the library name /
    description ("RES 100K OHM" → "100kΩ", "CAP 1UF" → "1uF"); part number from
    `Characteristics/Textual` keys (`PART_NUMBER_KEYS`), else the library name when
    `looksLikePartNumber()`; text repaired with `fixMojibake()` (Altium double-encodes some
    descriptions).
  - Units: `CadHeader units` for coordinates; each `Dictionary*` has its own `units`.
  - Only the first `Step` is read (panels repeat the board).
- `pnp/PickPlaceParser.kt`: columns found by header aliases (Designator, Center-X/Mid X/Ref X…,
  Layer/TB, Rotation, Comment, Description, Footprint); units per value, per column or from the
  preamble; space-aligned TXT supported. `FootprintSizeGuesser` invents a hit box.
- `bom/Bom.kt`: `BomParser` (designator ranges `R1-R4`), `Board.withBom()` (BOM part number
  replaces a library-name guess), `XlsxReader` (first sheet, shared/inline strings).
- `text/`: `decodeText` (UTF-8/UTF-16/Windows-1252), `DelimitedText`/`Table` (CSV, TSV,
  space-aligned), number+unit parsing, `fixMojibake`, `looksLikePartNumber`.

## core/render

Android library (Compose). Everything below works in board µm until `ViewTransform`.

- `ViewTransform`: immutable affine board→screen. Canonical form: mirror X (bottom view) →
  flip Y → rotate (clockwise, any angle) → scale → translate. `fitted()`, `fit()`, `zoomBy()`
  (clamped 0.5×…60× of fit), `rotateBy()`, `snapAngle()` (locks within 7° of 0/90/180/270),
  `mirror()` (negates the canonical rotation), `centerOn()`, `toBoard()`.
- `BoardScene(board)`: built once per board on a background thread. Per-layer `LayerScene`
  (fill Path + strokes grouped by width), `multiLayer` (through-hole pads + vias), `holes`,
  `viaRings`, component box paths, `drawOrder(side, hidden)`, `defaultHiddenLayers`,
  `netGeometry(net)`, `realistic(side)` (copper, exposed pads, silk, bodies by colour).
  Filled rings are CCW and holes CW so the non-zero fill rule works.
- `BoardCanvas`: the board composable. **Drawing order:** background → grid → board fill →
  (CAD: layers back-to-front, multi-layer, holes | Realistic: copper tone, pads, via rings,
  holes, silk, body shadows, bodies) → outline → net focus (fade + net) → component outlines →
  status paint → highlight focus (fade, status of highlighted, pads, outline) → selection
  (glow, pads, outline, pulse) → ink strokes → live stroke → labels → status badges (only with "Review", `DisplayOptions.showStatusBadges`) → FPS.
- **Gestures** (`ViewGestures.kt`, shared by the board, schematic and PDF views): `Modifier.viewGestures(state)` runs one `awaitEachGesture`: stylus → draw/erase only,
  never pans; finger long-press → `onLongPress` (caller returns true to take over: ring menu or
  box selection); finger drag with Pen tool → draw; otherwise pan + pinch + guarded two-finger
  rotation with snapping (haptic tick on snap); tap → `onTap(x, y, tolerance, fromStylus)`. A
  second `pointerInput` observes stylus hover. The view writes its latest callbacks into a
  `ViewGestureState` (`rememberViewGestureState()`) each recomposition, so the gesture coroutine
  never restarts; the stroke being drawn is `ViewGestureState.livePoints`. The canvases only
  fill that state in. On the schematic the S Pen selects with a tap and does nothing on a drag
  (there is no ink there).
- Schematic: `SchematicScene` / `SheetScene` (per sheet: `SchematicLayout`, paths grouped by
  file colour and width, wires apart, junction dots, `GridIndex` of symbols, `symbolAt`),
  `SchematicCanvas` (paper → body fills → status paint → strokes → wires → junctions → faded
  parts → net focus → highlight → selection → texts upright in screen space, skipped below
  3.5 px, with overbars), `SchematicFocus` (selected, highlighted, faded, marks, net),
  `SchematicColors` (theme mapping of the file colours: ORIGINAL, INVERTED lightness,
  HIGH_CONTRAST; cached per colour).
- Hit-testing: `GridIndex<T>` (uniform grid over anything with a bounding box; `candidates`,
  `hitTest` with containing-first, smallest-box, `accept` and `priority` rules) for schematic
  items too; `ComponentIndex` (a `GridIndex` with the board side rule: visible side only,
  smallest box wins), `CopperIndex` (pads exact outline, tracks as thick segments, vias, pours),
  `BoardPicker.pickComponent()` / `pickNet()` combine them with exact-hit-first priority.
- Other: `Annotations.kt` (`InkStroke`, `ComponentMark`, `DisplayOptions`, `ViewMode`,
  `GridStyle`, `CanvasTool`), `BodyStyle` (realistic colours by part type, copper tone, silk
  ink), `Grid` (manual mm/mil steps, density rules, auto step), `StylusInput` (side-button state
  from MotionEvents), `Thumbnail` (`BoardScene.thumbnail()` bitmap for recents).

## core/update

Pure Kotlin (java.net + kotlinx-serialization-json's tree API), shared by Android and the PC
version; plan in `docs/PLAN-updates.md`.
- `ReleaseManifest`: `flux-release.json`, attached to each GitHub release by `scripts/release.ps1`
  (tag, date, notes, an optional Android part with versionCode/minSdk/APK and an optional PC part
  with MSI and portable zip, each with size and SHA-256). Unknown keys are ignored; a newer
  `schema` is refused with a message.
- `GitHubReleaseSource`: the newest full release through `releases/latest/download/flux-release.json`
  (no API rate limit); the version list and test versions through the REST API.
- `UpdateChecker` + `Platform` (`AndroidPlatform` compares versionCode and minSdk,
  `DesktopPlatform` compares `Version`): `check()` and `list()`.
- `Downloader`: `.part` file, Range resume, size and SHA-256 check, cancellable.
- Every failure text is in `UpdateMessages`. Tests run against a local fake GitHub (`FakeGitHub`).
- Android (`app/.../update/`): `UpdateViewModel` (Settings › Updates, "Updated to" note),
  `ApkInstaller` (PackageInstaller session, result through `UpdateInstallReceiver` →
  `InstallEvents`; older versions are saved to Downloads). Online only when the user asks.

## app

- `BoardViewModel` (AndroidViewModel) owns **all** UI state in one `UiState` StateFlow.
  Main groups of functions:
  - loading: `open(uris)`, `openDemo`, `openRecent`, `renameRecent`, `removeRecent`, `closeBoard`
  - selection & search: `select`, `selectNet`, `onBoardTap` (dispatches by `Tool`),
    `componentAt`, `search`, `pickResult`, `selectAllResults`
  - schematic: `setViewLayout` (BOARD / SPLIT / SCHEMATIC), `setSheet`, `onSchematicTap`,
    `symbolAt`, `selectSchNet`, `selectRef`, `boardNetsOf`. Selection is shared: `selected`
    (board part) or `selectedSymbol` (schematic part not on the board), `selectedRef` for either;
    `selectedNet` (board) and `schNet` (schematic) kept in step through `boardToSch`.
    `withSchematicFollow` bumps `schFocusRequest` (schematic pans and opens the sheet) when
    "Follow selection" is on; schematic taps bump `focusRequest` (board zooms) in split view.
  - highlight group: `toggleIdentical(c, all)`, `selectMany` (box), `setHighlightFilter`,
    `setHighlightQuery`, `clearHighlight`. State: `highlightBase` (group) → `highlighted`
    (after All/SMD/PTH + query), `highlightOverride` (long-press "all"), `highlightKey`.
  - status: `setMark` (batch when the part is highlighted), `applyToHighlighted`
    (`BatchStatus.split` keeps parts with another status unless override), `setMarks`
  - drawing: `addStroke`, `eraseAt`, `clearAnnotations`, `undo` (deque of `Undo` records)
  - S Pen: `onStylusButton` (button = eraser, release = pen), `onStylusSeen`
  - view: `flipSide`, `setDisplay`, layers, panels, `setTool`, pen/paint settings,
    `setAppearance` (persisted settings).
- `ui/BoardScreen.kt`: `BoardScreen` → `StartScreen` (no board) or `BoardContent`:
  `TopBar` (back, title, search, Parts/Nets toggle, Board/Split/Schematic toggle when there is a
  schematic, Tools, Fit, 3D view, Flip, Layers, Settings; board-only buttons hidden in schematic
  view), the view area and the side panel (`DetailsPanel`, `NetPanel`, `SymbolPanel` or
  `SchNetPanel`): right in landscape, bottom in portrait. The view area is `BoardPane`,
  `SchematicPane`/`PdfPane`, or both in `SplitPanes`, plus the screen-wide overlays (search
  results, `SettingsPanel`, `HighlightChip`, message).
- `ui/BoardPane.kt`: `BoardViewState` (`rememberBoardViewState(scene)`: transform, viewport,
  `fit()`, `mirror()`; hoisted so the top bar's Fit/Flip act on it) and `BoardPane`
  (`BoardCanvas` plus its own overlays: `RadialStatusMenu`, `MarqueeOverlay`, S Pen hover label,
  `ToolPalette`/`ToolOptions`, `LayersPanel`). Flip switches the side in front; Mirror (top bar,
  `UiState.mirrored`, `toggleMirror` in `BoardScreen`) mirrors the view on either side, with a
  "MIRRORED" label in `BoardPane`. View states keep their
  centre point when the pane is resized.
- `ui/SchematicPane.kt`: `SchematicViewState` (one transform per sheet), `SchematicPane` (sheet
  tabs with a dot where the focused net runs, canvas, status ring on long-press, follow
  selection), `SymbolPanel`, `SchNetPanel`. `ui/SplitPanes.kt`: draggable divider (20–80 %,
  double-tap 50/70 %). `ui/PdfPane.kt`: Smart PDF fallback via `PdfRenderer` (pages rendered at
  3200 px, view only).
- Other UI files: `Theme.kt` (`PcbTheme`, palettes, `boardColorsFor`, `LocalBoardColors`,
  `schematicColorsFor`, `LocalSchematicColors`),
  `StartScreen.kt`, `DetailsPanel.kt`, `Panels.kt` (Layers, Settings, Net panel, ActiveToolChip),
  `ToolPalette.kt` (+ `PaletteDock`), `HighlightChip.kt`, `RadialMenu.kt`, `Marquee.kt`,
  `AppearanceSettings.kt` (Settings content, `Segmented`), `AppIcons.kt`.
- `MainActivity`: picks the window theme from the saved settings before drawing, sets the
  Android 13+ splash theme, handles VIEW/SEND intents, forwards raw touch/hover events to
  `StylusInput`.

## Persistence

- **Room** `pcbsolderview.db`, version 4 (`data/AnnotationDb.kt`):
  - `marks(boardKey, refDes, argb, note)` — status paint
  - `strokes(id, boardKey, argb, widthUm, points "x,y;x,y")` — ink
  - `recent_boards(boardKey, name, source, components, lastOpened, dir, customName)`
  - Migrations: 1→2 recents table, 2→3 customName, 3→4 status colour change.
  - `boardKey = "${source}:${board.name}:${components.size}"` — survives re-exports of the same
    board; the user-visible name is separate (`customName`).
- **BoardStore** (`data/BoardStore.kt`): copies opened files to `filesDir/boards/<sha1(key)>/`
  (`00_<name>`, `01_<name>`…) plus `thumb.png`; recents reopen from there.
- **Settings** (`data/AppearancePrefs.kt`): DataStore `appearance` (async, source of truth) and
  SharedPreferences `appearance_sync` (synchronous mirror for the first frame).
- In memory only (reset when the app restarts): per-board view options (`DisplayOptions` layers,
  view mode, mask colour), tool, pen colour, toolbox position, view layout, split position, sheet.
- Schematics need no new tables: BoardStore keeps every opened file (so a recent reopens with
  its .SchDoc/PDF), and status paint is keyed by designator, so it also covers schematic-only
  parts.

## desktop (Windows version)

Compose for Desktop module, built only with `-Pdesktop`. `core/model` and `core/parser` are shared;
`core/render`, the UI and `BoardViewModel` are copies with the same package names, so Android
changes port as patches (`desktop/SYNC.md` records the baseline commit and every deliberate
difference). Platform pieces:

- `render/AndroidGraphics.kt`: the `android.graphics.Paint/Typeface` subset the canvases use, drawn
  with Skia fonts, so text code is unchanged. `ViewGestures` adds the mouse (`mouseGesture`, wheel).
- `BoardViewModel`: plain class with its own scope (Swing main dispatcher), `open(List<File>)`.
- `data/`: the Room DAOs re-implemented over JSON files in `%APPDATA%lux` (`recents.json`,
  `annotations/<sha1(key)>.json`, `settings.json`, `window.json`, `boards/<sha1(key)>/` copies);
  every write lands on disk before the ui sees it and is not cancelled by closing the window.
- `platform/`: `appdirs` (`flux.home` overrides), `supportedfiles` (folder expansion), `demos`
  (synthetic samples as resources with an index), file/folder dialogs, `pdfrenderer` (pdfbox),
  `windowprefs`. `main.kt`: window, menu bar, shortcuts (esc → `backdispatcher`), drag and drop.
- tests: the android jvm tests (copied), `persistencetest` (restart with a temp data folder),
  `screenshottest` (`imagecomposescene` renders of the real ui to `desktop/build/renders/`),
  `iconexporttest` (builds `flux.ico` from `src/main/resources/flux.svg`).

## testing

- `core/model`, `core/parser`: JUnit 5 on the JVM. `samples/synthetic/*` fixtures; the real
  board tests (`Ipc2581RealBoardTest`) cross-check every component against the raw XML, compare
  BOM quantities with identical-part counts, and render PNG previews.
- Schematic tests (`parser/altium/`): `SchDocParserTest` (ASCII fixture `ipc-mini/mini.SchDoc` vs
  a native copy written by the test-only `CompoundFileWriter`), `SchematicNetsTest` (every board
  net of the mini board matches one schematic net), `SchematicLayoutTest` (layout +
  `SchematicPng` previews in `core/parser/build/renders/schematic-*.png`, compare with Altium's
  PDF), `Rp2040RealBoardTest` (native sheet vs ASCII copy, counts, pins vs pads; local only).
- `core/render`: JVM unit tests for pure classes (transform, picker, indexes, grid, body style).
  `BoardScene`/`BoardCanvas` need Android `Path` and are not unit-tested.
- `app`: JVM tests for pure helpers (status colours ΔE, batch rule, marquee geometry, palette
  dock, radial geometry, settings encoding, stroke encoding); `androidTest/DetailsPanelTest`
  compiles but needs a device.

## Extending

- **New file format:** add a parser in `core/parser` that returns a `Board`; teach
  `BoardImporter.classify()` to recognise it; add a fixture to `samples/synthetic/` and tests.
- **New setting:** field in `Appearance`, DataStore key, mirror in `snapshot`/`mirror`, UI in
  `SettingsContent`, read it in `BoardScreen` (merged into `DisplayOptions` for the canvas).
- **New detail row:** `DetailField` enum + a row in `DetailsPanel` behind `shows(...)`.
- **New schematic record:** handle its RECORD number in `SchDocParser` (sheet-level `when` or
  `symbol()` for owned children), draw it through `SchematicLayout`, add it to the ASCII fixture.
- **Hierarchical sheets** (sheet symbols/entries, ports by sheet) are drawn but not traced:
  connectivity joins names globally, as in a flat design.
