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
            └─ every BOM file → Board.withBom(bom)     (fills missing fields by designator)
       └─ BoardScene(board)                             core/render: cached Paths, indexes
       └─ remember(): recent-boards entry + private copy of the files (BoardStore)
  └─ UiState (StateFlow) → BoardScreen → BoardCanvas + panels
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

## core/parser

Pure Kotlin. Entry point: **`BoardImporter(xml: XmlParserFactory).import(files, onProgress)`**.

- `classify()`: `PK` header → XLSX (BOM) if it contains `xl/`, else ZIP; text starting with `<`
  and containing `IPC-2581`/`<Content` → IPC2581; otherwise tries a Pick & Place header, then a
  BOM header. Files it doesn't recognise are ignored (no error).
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
  (glow, pads, outline, pulse) → ink strokes → live stroke → labels → status badges → FPS.
  **Gestures** (one `awaitEachGesture`): stylus → draw/erase only, never pans; finger
  long-press → `onLongPress` (caller returns true to take over: ring menu or box selection);
  finger drag with Pen tool → draw; otherwise pan + pinch + guarded two-finger rotation with
  snapping; tap → `onTap(boardX, boardY, toleranceUm, fromStylus)`. A second `pointerInput`
  observes stylus hover. Callbacks are read through a `LatestHolder` so the gesture coroutine
  never restarts.
- Hit-testing: `ComponentIndex` (grid over component boxes; visible side only, smallest box
  wins), `CopperIndex` (pads exact outline, tracks as thick segments, vias, pours),
  `BoardPicker.pickComponent()` / `pickNet()` combine them with exact-hit-first priority.
- Other: `Annotations.kt` (`InkStroke`, `ComponentMark`, `DisplayOptions`, `ViewMode`,
  `GridStyle`, `CanvasTool`), `BodyStyle` (realistic colours by part type, copper tone, silk
  ink), `Grid` (manual mm/mil steps, density rules, auto step), `StylusInput` (side-button state
  from MotionEvents), `Thumbnail` (`BoardScene.thumbnail()` bitmap for recents).

## app

- `BoardViewModel` (AndroidViewModel) owns **all** UI state in one `UiState` StateFlow.
  Main groups of functions:
  - loading: `open(uris)`, `openDemo`, `openRecent`, `renameRecent`, `removeRecent`, `closeBoard`
  - selection & search: `select`, `selectNet`, `onBoardTap` (dispatches by `Tool`),
    `componentAt`, `search`, `pickResult`, `selectAllResults`
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
  `TopBar` (back, title, search, Parts/Nets toggle, Tools, Fit, 3D view, Flip, Layers,
  Settings) and the board area (`BoardCanvas` plus overlays: `ToolPalette`/`ToolOptions`,
  `RadialStatusMenu`, `MarqueeOverlay`, S Pen hover label, search results, `LayersPanel`,
  `SettingsPanel`, `HighlightChip`, message, bottom-view label) with the side panel
  (`DetailsPanel` or `NetPanel`): right in landscape, bottom in portrait.
- Other UI files: `Theme.kt` (`PcbTheme`, palettes, `boardColorsFor`, `LocalBoardColors`),
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
  view mode, mask colour), tool, pen colour, toolbox position.

## Testing

- `core/model`, `core/parser`: JUnit 5 on the JVM. `samples/synthetic/*` fixtures; the real
  board tests (`Ipc2581RealBoardTest`) cross-check every component against the raw XML, compare
  BOM quantities with identical-part counts, and render PNG previews.
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
- **Schematic view (v0.1):** see `ROADMAP.md`, step S0, for the refactors that make room for a
  second view: an import result that carries a schematic, reusable gestures, a self-contained
  board pane for the split screen, and a generic hit-test index. Selection is already keyed by
  designator (`Component.refDes`), which is how schematic symbols will link to board parts.
