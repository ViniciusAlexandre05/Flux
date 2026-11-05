# Changelog

All notable changes to Flux. Dates are in YYYY-MM-DD.

## Unreleased

### Schematic (v0.1, not released; untested on the tablet)
- Open Altium schematics (`.SchDoc`, native or saved as ASCII) together with the board, or on
  their own. Each .SchDoc is a sheet; parts, pins, wires, net labels, power ports, junctions,
  graphics, text frames and title-block fields are drawn. Multi-part parts show as U1A, U1B.
- **Board / Split / Schematic** switch in the top bar. Split view: side by side in landscape,
  stacked in portrait, with a draggable divider (double-tap: 50 % / 70 %).
- Schematic colours follow the theme: Altium's colours on paper in Light, inverted lightness on
  dark paper in Dark, bright ink on black in High contrast. Sheet tabs for several sheets.
- **One selection for both views:** tap a part or net on either side and it shows on the other.
  "Follow selection" (Settings › Schematic, on by default) pans the other view and opens the
  right sheet. Parts that are not on the board are faded; they can still be selected and given
  a status. Status paint and highlight groups show on schematic symbols; long-press a symbol for
  the status ring.
- **Net tracing:** schematic nets are built from wires, junctions, labels and power ports and
  matched to board nets by the pins they connect, so unnamed nets line up too. The panel of a
  schematic-only part lists its pins with their nets.
- A PDF (Altium Smart PDF) chosen without a .SchDoc is shown view-only in the schematic pane.
- New demo: "Board + schematic". Recents reopen with their schematic in split view.
- Internal: pure-Kotlin compound-file (OLE) reader, also the base for reading .PcbDoc (S5b).

- Altium native files are recognised: choosing a `.PcbDoc` explains that it can't be opened
  yet, that it is not the recommended format, and how to export IPC-2581 instead; a `.SchDoc`
  says the schematic view is planned for 0.1. When one is picked together with a supported board
  file (multi-select or a .zip), the board opens and a notice says which file was skipped.
- Longer on-screen messages stay up longer (up to 9 s).

### Internal (v0.1 preparation, S0) — no visible change intended
- The importer returns an `ImportResult` (board + optional schematic); a placeholder
  `Schematic` model and `UiState.schematic` are in place for S1.
- Board gestures (pan, pinch, guarded rotation with snapping, long-press, S Pen drawing/erasing,
  taps, hover) moved unchanged out of `BoardCanvas` into a shared `Modifier.viewGestures`, for
  the future schematic view. Needs a re-test on the tablet.
- The board area is a self-contained `BoardPane` with its own view state (`BoardViewState`),
  ready for a schematic pane beside it.
- Hit-testing runs on a generic `GridIndex` (any item with a bounding box); `ComponentIndex`
  is built on it.

## 0.0.2 — 2026-11-05

### Status colours
- New, more distinct palette: OK green, Problem red, Check **amber** (was yellow), Info blue.
  A test keeps every pair at least ΔE 35 apart (CIELAB), also when painted over the board,
  the green mask and the light/dark backgrounds. OK vs Check over the board: ΔE 43 → 84.
- Status paint is nearly opaque (85%) with a dark edge, so the board no longer tints it.
- Each painted part shows a symbol badge: ✓ OK, ! Problem, ? Check, i Info.
- Existing marks are converted to the new colours on update.

### Selection and highlighting
- The selected part stands out: glow, thick outline, accent-coloured pads and a short pulse.
  It is no longer tinted, so its status colour stays visible.
- **Highlight identical:** tap highlights only the identical parts without a status yet;
  press and hold highlights all of them, and a status then applies to every one
  (overwriting existing statuses).
- **Highlight chip:** clear-status dot; tap the label for an **All / SMD / PTH** switch (with
  counts) and a search that narrows the highlight within the group. Batch status applies to
  what is shown.
- **Search › Select all:** the first search result highlights every match as one group.

## 0.0.1 — 2026-11-04

### Part details
- New fields in the part details panel: **part number**, **library reference**, **height**,
  and **mounting & pins** ("SMD · 16 pins", "Through-hole · 3 pins").
- Part number source, in order of preference:
  1. a BOM CSV/XLSX opened together with the board (columns such as *Manufacturer Part Number*),
  2. part-number characteristics inside the IPC-2581 file,
  3. the library name, when it is a part number (e.g. `L7812CV`); shown with the note
     "from the library name".
- **Settings › Part details** chooses which rows the panel shows (Library is hidden by default).
- Text that Altium exported with a broken encoding is repaired when the file is read
  (e.g. `1.1nV/âˆšHz` becomes `1.1nV/√Hz`).
- A test checks every component of the real sample board against the raw XML (position,
  rotation, side, footprint, height, description, pin count) and writes
  `core/parser/build/reports/components-<board>.csv`.

### Status painting
- **Batch status:** while parts are highlighted, setting a status on one of them (details panel,
  long-press ring, or the new status dots on the highlight chip) sets it on all highlighted parts.
  Parts that already have a different status keep it; the part acted on always changes; clearing
  applies to all. One undo step, with a short confirmation message.
- Highlighted parts keep their status colour above the focus fade.

### Selection
- **Box selection** in Parts mode: long-press on an empty spot and drag. On release, choose
  **Select** (highlights the visible-side parts whose centre is inside the box) or **Annotate**
  (keeps the box as a rectangle drawing). Works on rotated and bottom views.

### Realistic view
- Vias are shown as gold rings, and every hole is punched (vias, through-hole pins, mounting holes).

## 0.0 — 2026-11-04

First version, for the Samsung Galaxy Tab S6 Lite.

- **Import:** Altium IPC-2581 (`.cvg`/`.xml`, revisions B and C), Pick & Place (CSV or
  space-aligned TXT, mm or mil) with a BOM (CSV or XLSX), or a `.zip` of these.
- **Views:** CAD layers in Altium's colours with per-layer visibility; a realistic view
  (solder mask in a chosen colour, copper showing through, gold pads, silkscreen, part bodies
  from the package outlines with shadows); top/bottom flip; background grid (lines or dots,
  fixed mm/mil spacing or automatic).
- **Navigation:** pan, pinch-zoom, two-finger rotation that locks near 0°/90°/180°/270°.
- **Inspection:** Parts mode (designator, value, footprint, pins and nets) and Nets mode
  (tap a track, via, pad or plane to see the net and everything it connects); search by
  designator or value; highlight identical parts with the rest of the board faded.
- **Soldering progress:** status painting (OK, Problem, Check, Info) with a paint bucket or a
  long-press ring; freehand drawing and eraser; S Pen support (the pen never pans, the side
  button erases, hovering shows the part under the tip); undo; saved per board.
- **Tool palette:** floating, draggable, docks to any edge (column at the sides, bar at the
  top or bottom).
- **Start screen:** recent boards with previews and progress; rename; demos.
- **Settings:** Dark / Light / High-contrast themes, accent colour, board background, touch and
  S Pen options, FPS meter.
