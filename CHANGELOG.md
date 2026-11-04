# Changelog

All notable changes to Flux. Dates are in YYYY-MM-DD.

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
