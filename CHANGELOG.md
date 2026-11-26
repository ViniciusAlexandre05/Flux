# Changelog

All notable changes to Flux. Dates are in YYYY-MM-DD.

## Unreleased

- **In-app updates on the PC**: Settings › Updates and **Help › Check for updates…** work as on
  Android (Check now, what's new, All versions, Show test versions). **Download, install and
  restart** downloads the installer (or the portable zip, for the portable folder), closes Flux,
  installs it and starts Flux again; boards, statuses and settings in `%APPDATA%\Flux` stay. Older
  versions install the same way (**Go back**, after a warning). If an update does not finish,
  the next start says why. When Flux runs from the source code it can check but not install.

## 0.1.4-test1 — 2026-11-26

- Test version: the same app as 0.1.3. It exists only to try the in-app update: in 0.1.3, turn
  on Settings › Updates › Show test versions, tap Check now and install it. If this note appears
  after the update, it worked.

## 0.1.3 — 2026-11-26

- **In-app updates** (Android): Settings › Updates shows the installed version and a **Check now**
  button. When a newer Flux is on GitHub it shows what's new and **Download and install** (progress,
  Cancel; an interrupted download continues where it stopped, and every file is checked against
  its SHA-256 before installing). Android asks once to allow Flux to install apps, then shows its
  own update prompt; boards, statuses and settings stay. After the update a short note says what's
  new. Flux goes online only when you tap Check now or All versions.
- **All versions**: every release with in-app install, newest first, with Install for newer ones
  and **Save APK** (to Downloads) for older ones (Android only installs an older version after
  Flux is uninstalled, which deletes its annotations).
- **Show test versions** (off by default): also offers test versions, to try a fix before it is
  released.
- Flux now has the Internet and "install apps" permissions, used only for the above.
- PC: no changes except the version number (its updater comes later).
- Releases: `scripts/release.ps1` builds, checks and publishes a release with a
  `flux-release.json` file that the app reads (versions, file sizes, SHA-256, notes).

## 0.1.2 — 2026-11-23

Android 0.1.2 (versionCode 6) and PC 0.1.2. Released before the tablet test.

- The three-way Board / Split / Schematic switch is now two on/off buttons, **Board** and
  **Schematic**: both on shows them side by side, one on shows that view alone. The last view
  that is on cannot be turned off (a short message says so).
- Settings › Appearance has a **PCB view / Schematic view** switch. PCB view: board background
  and grid, as before. Schematic view (new): paper colour (theme, the file's, white, cream, dark,
  black), line and text colours (theme, Altium's own, inverted, high contrast), traced-net colour,
  a grid on the paper (lines or dots, 50 / 100 / 200 mil) and text size (80 % to 200 %). Theme
  and accent colour stay above the switch; they apply to the whole app.
- PC: both of the above. Keys **1** and **2** turn the board and the schematic on and off (key 3
  is gone), and the View menu has two check items, Board and Schematic.

## 0.1.1 — 2026-11-18

Android 0.1.1 (versionCode 5) and PC 0.1.1. Like 0.1, released before the tablet test.

- Status symbol badges (✓ ! ? i) on painted parts are now off by default; turn them on with
  **Review** in Layers & view. The status colours show as before.
- **Mirror** is now its own button next to Flip, always available: it mirrors the view left-right
  on either side. Flip only switches between the top and bottom side (which parts are in front);
  it no longer mirrors. A "MIRRORED" label shows while the view is mirrored (it replaces the old
  "BOTTOM VIEW (mirrored)" label). To see the bottom as if the board were turned over, use Flip
  and Mirror.
- Duplicate designators in a schematic (two different parts both called `RP2040`, or one part
  placed twice) are reported once when the files are opened, and the part panel shows a warning
  on those parts (their pins and nets may be mixed up until the annotation is fixed in Altium).
  The parts of one multi-part part (U1A, U1B) are not reported.
- **Soldering checklist** (checklist button in the top bar): the board's parts grouped by kind
  (part number, or value + footprint), SMD first and the biggest groups first, each line with its
  count, designators and OK progress (problems in red). All / SMD / PTH filter and "Hide
  finished". Tap a line to highlight the parts still to do, hold to highlight all of them; the
  highlight chip then sets a status on the whole group.
- Highlights (identical parts, search results, checklist lines) now show only the parts on the
  side being viewed, and a status set from the highlight chip only reaches those. The chip says
  how many more are on the other side ("3 more on the bottom side (Flip)"); Flip shows them.
- **Keep the screen on** while a board is open (Settings › Touch & S Pen, on by default).
- Boards opened from a native .PcbDoc now show their silkscreen text (designators and other
  overlay text) in the CAD and realistic views, at Altium's positions and sizes. Hidden texts stay
  hidden (values whose "Comment" is off, assembly-drawing templates).
- PC: everything above except "Keep the screen on", plus the same Review option and Mirror
  behaviour as Android. In the checklist, click a line for the parts still to do, right-click for
  all of them.
- PC: **Ctrl+drag** draws a selection box (also when it starts on a part) and **Ctrl+click** adds
  or removes a part from the selection; the highlight chip then sets a status on all of them.
- PC design review: tooltips on mouse hover, "click" wording, Settings › Mouse & pen (Rotation;
  the S Pen option is hidden), search results under the field, search on its own row in narrow
  windows, right-click to highlight all identical parts, View menu toggles for Tools, Layers & view
  and Settings.

## 0.1 — 2026-11-16

Released before the tablet test (the schematic and .PcbDoc parts were tested on the JVM only).
Windows installer and portable zip are attached to the same GitHub release.

### Native .PcbDoc (S5b)
- Altium `.PcbDoc` files open directly, without exporting IPC-2581: outline, layers, pads (round,
  rectangle, rounded, octagon, through-hole), tracks, arcs, vias, holes, copper pours, component
  bodies and heights. Open it alone or with the `.SchDoc`, a BOM or a PDF.
- Values come from the part's comment ("100nF"), better than the IPC-2581 export's library name
  ("CAP"). Duplicate designators are made unique ("RP2040", "RP2040_2").
- With an IPC-2581 file chosen too, the IPC-2581 is used. A damaged `.PcbDoc` falls back to a
  Pick & Place file or the schematic, or explains how to export IPC-2581.

### Desktop (Windows) 0.1.0
- New PC version (`desktop/` module, Compose for Desktop), matching Android at d60f979 (v0.1
  schematic sync included): board, schematic, split view, PDF fallback, search, nets, layers,
  status paint, pen/eraser, undo, recents with thumbnails, settings.
- Mouse and keyboard: wheel zoom at the cursor, drag to pan, Shift+drag / Ctrl+wheel rotate,
  double click fits, right click = status ring / box selection, menu bar, shortcuts, drag and
  drop of files, open a whole folder, files from the command line.
- Hover highlight: the part under the mouse is outlined, with its designator and value.
- Bottom view: the red "mirrored" label is gone; a Mirror button chooses between the mirrored
  view (board turned over) and seeing the bottom through the board.
- Clicking empty board no longer selects a part as big as the board (a module footprint).
- Opens Altium .PcbDoc files (shared reader).
- Selecting a part gives a short, thin highlight (instead of the tablet's long pulse), and opening
  the side panel no longer moves the board, even when the panel covers the selected part.
- Data in `%APPDATA%lux` (json), not synchronised with the tablet.

### Schematic (v0.1)
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
