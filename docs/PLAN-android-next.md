# Plan: next Android work (v0.1 release, native .PcbDoc reader, follow-ups)

For an agent implementing the next Android steps of Flux. Android is the **main** version of the
app; this plan never touches the desktop version (see `docs/PLAN-desktop.md`, which follows Android
only when the user asks).

Written 2026-11-16, against `main` at `d60f979`.

## 0. Read first

1. `CLAUDE.md`: environment (JDK, SDK, git and gh paths), the gate, rules that are easy to break,
   pitfalls, how this user works. It overrides anything here if they disagree.
2. `docs/ARCHITECTURE.md`: modules, data flow, where things live.
3. `ROADMAP.md` (v0.1 section) and `CHANGELOG.md` (Unreleased).

Facts to keep in mind:

- No device or emulator is attached. You cannot run the app; the user tests on a Samsung Galaxy
  Tab S6 Lite and reports back in short messages. Verify through JVM tests and PNG previews.
- **Gate before every commit:** `./gradlew test lint assembleDebug` must be green. In Bash, set
  `JAVA_HOME` (path in CLAUDE.md) and redirect stdin (`< /dev/null`). If lint fails with "file is
  being used by another process", run `./gradlew --stop` and retry.
- `samples/real/` holds the user's private designs. It is gitignored and **must never be committed
  or pushed**. Tests that use it must skip when it is missing (`assumeTrue`).
- Commit when the user asks. Push, tag, release, close GitHub issues only when asked. End commit
  messages with the attribution line from the system reminder.
- The user writes English or Portuguese; answer in the language of their message.
- State of `main` at the time of writing: 8 commits ahead of `origin/main` (not pushed). v0.1
  schematic sync (S0, S5, S1–S4, S6) is implemented but **not yet tested on the tablet**.

## Phase A — tablet feedback and v0.1 release (needs the user)

Nothing to build until the user reports. When they do:

1. For each problem: restate your interpretation (their wording is often short or ambiguous) and
   offer the alternative, fix it, add a JVM test where the logic allows, run the gate, commit.
2. Things most likely to need attention (from the review on 2026-11-05):
   - Gestures were moved into `core/render/.../ViewGestures.kt` (S0). Any regression in pan,
     pinch, rotation snapping, long-press, S Pen drawing/erasing, hover: compare with the code in
     commit `ee2a86c` (`BoardCanvas.kt` before the move). Keep the pitfall in mind: measure
     movement before `consume()`.
   - Split view: divider dragging (`ui/SplitPanes.kt`), panes keeping their centre on resize
     (`BoardViewState.viewport` / `SchematicViewState.viewport` setters).
   - Follow selection (`withSchematicFollow`, `schFocusRequest`, `SchematicPane` focus effect).
   - Schematic text sizes and placement on a real screen (`SchematicLayout`, `SchematicCanvas.drawTexts`).
   - S Pen on the schematic: a tap selects, a drag does nothing (by design; no ink on schematics).
     Do not add an automatic mode switch unless the user asks.
   - PDF fallback memory (`ui/PdfPane.kt` renders pages at 3200 px).
3. Release v0.1 **only when the user asks**, following the release procedure in CLAUDE.md:
   `versionCode` 3 → 4, `versionName` "0.1" (tags so far: v0.0, v0.0.1, v0.0.2 → this one is
   `v0.1`), move CHANGELOG "Unreleased" into a "0.1 — <date>" section, a line in ROADMAP.md,
   clean build + tests, commit "Version 0.1", annotated tag, push main and tag, `gh release create`
   with the APK renamed `Flux-0.1-debug.apk` and `--notes-file`. Ask before closing issues #1–#6.

Phase B does not depend on Phase A; it can start before the tablet test, but keep its commits
separate so a v0.1 release can be cut without it if the user wants.

## Phase B — S5b: native .PcbDoc reader (GitHub issue #7)

### Goal

Open Altium's native `.PcbDoc` directly and produce the same `Board` model (µm, Y up) as the
IPC-2581 parser, so a board can be opened without exporting IPC-2581.

**Done when:** the RP2040 sample opened as `samples/real/RPI_Module/RP2040-Module.PcbDoc` and as its
IPC-2581 export `RP2040-Module.cvg` gives the same parts, pads and positions within 1 µm (test,
local sample only), plus synthetic unit tests that run everywhere.

### What already exists (reuse it)

- `core/parser/.../altium/CompoundFile.kt`: MS-CFB container reader. `CompoundFile(bytes).read("Pads6/Data")`
  returns a stream; `streams` lists every path. Works on the RP2040 PcbDoc (verified).
- `core/parser/.../altium/AltiumRecords.kt`: `AltiumRecords.fromStream(bytes)` reads streams of
  "u32 length + `|KEY=value|…` text" records, keys upper-cased, `AltiumRecord.int/double/bool`.
  It already reads `Board6/Data`, `Components6/Data`, `Nets6/Data`, `Polygons6/Data`,
  `Classes6/Data` correctly (verified: 1, 17, 47, 4, 15 records).
- `core/parser/src/test/.../altium/CompoundFileWriter.kt` (test code): writes compound files and
  text record streams. Extend it with a helper that writes binary primitive records, for
  synthetic tests.
- `BoardImporter.classify()` already returns `FileKind.ALTIUM_PCBDOC`; `SourceFormat.PCBDOC`
  already exists in the model; `NativeFileMessages` (`ParseErrors.kt`) holds the user texts.
- `Ipc2581Parser` shows how the app expects a `Board`: layer list and types, pads with
  `componentRef` and `name` (= pin number), `Component.padIds`, `bounds`, `outline`, holes,
  nets, fills. Mirror its conventions; the renderer and the schematic matching depend on them.
- Reference for the format: KiCad's Altium importer (`pcbnew/pcb_io/altium/altium_parser_pcb.cpp`
  and `altium_pcb.cpp`; the path may have moved). It is GPL: **study it, do not copy code**.
  Everything below was verified on the RP2040 file on 2026-11-16; treat anything marked
  "verify" as unconfirmed.

### Format facts (verified on RP2040-Module.PcbDoc unless marked)

Streams: each object kind has `<Kind>/Header` (u32 record count, e.g. Tracks6 670, Pads6 134,
Vias6 39, Components6 17) and `<Kind>/Data`. Relevant ones: `Board6`, `Components6`, `Nets6`,
`Polygons6` (text records); `Pads6`, `Vias6`, `Tracks6`, `Arcs6`, `Fills6`, `Regions6`,
`ShapeBasedRegions6`, `Texts6`, `ComponentBodies6`, `WideStrings6` (binary). `FileHeader` holds
"PCB 5.0 Binary File" (UTF-16LE). Ignore the rest (Rules6, Classes6, Models, …).

**Units.** Binary coordinates and sizes are little-endian `i32` in 1/10000 mil:
`µm = value × 0.00254` (round to Long at the end). Example: track start x = 41247007 → 4124.7007 mil
→ 104.769 mm, matching the board outline. Text records use strings with units: `4488.189mil`,
sometimes `mm`; parse number + unit. Angles are `f64` degrees (binary) or strings like
` 2.70000000000000E+0002` (text; trim before parsing).

**Coordinates.** Absolute, Y up, same frame as the IPC-2581 export (outline corner
4124.7881 mil, 1691.3386 mil = 104.7696 mm, 42.96 mm in both). `Board6` has `ORIGINX/ORIGINY`
(0 here). If a file has a non-zero origin, check against its IPC export whether the export is
relative to it (verify; do not guess silently — add a test when a sample exists).

**Indices.** The component index in binary records is the 0-based order of `Components6`
records; the net index is the 0-based order of `Nets6` records; `0xFFFF` means none.

**Layer ids** (u8 in binary records; Altium's legacy numbering):

| id | layer name (use exactly these strings) | LayerType | side |
|---|---|---|---|
| 1 | Top Layer | COPPER | TOP |
| 2–31 | Mid-Layer 1…30 (use the stack names when present, e.g. "Int1 (GND)") | COPPER | null |
| 32 | Bottom Layer | COPPER | BOTTOM |
| 33 / 34 | Top Overlay / Bottom Overlay | SILKSCREEN | TOP / BOTTOM |
| 35 / 36 | Top Paste / Bottom Paste | PASTE | TOP / BOTTOM |
| 37 / 38 | Top Solder / Bottom Solder | SOLDER_MASK | TOP / BOTTOM |
| 39–54 | Internal Plane 1…16 | COPPER | null |
| 55 | Drill Guide | DRILL | null |
| 56 | Keep-Out Layer | OTHER | null |
| 57–72 | Mechanical 1…16 | OTHER (Mechanical 1 usually holds the outline drawing) | null |
| 73 | Drill Drawing | DRILL | null |
| 74 | Multi-Layer (through-hole pads, vias) | — (not a drawable layer; pads/vias on all copper) | — |

Layer names must match what the IPC-2581 parser produces, because the renderer's default hidden
layers and colours key on them: the RP2040 `.cvg` uses "Top Layer", "Bottom Layer",
"Top Overlay", "Bottom Overlay", "Top Paste", "Bottom Paste", "Top Solder", "Bottom Solder",
"Mechanical 1", "Mechanical 13", "Keep-Out Layer", "Drill Guide (…)", "Drill Drawing (…)".
`Board6` carries the stack: `V9_STACK_LAYER<n>_NAME` / `_LAYERID` / `_USEDBYPRIMS` (and the
older `LAYER<n>NAME`). Build the layer list from the stack (copper layers in stack order) plus
the fixed non-copper layers that primitives actually use.

**Binary record framing.** Each record: `u8 type`, then one or more subrecords, each `u32 length`
+ payload. Always advance by the declared lengths (unknown trailing bytes are normal and differ
between Altium versions). Types: 1 arc, 2 pad, 3 via, 4 track, 5 text, 6 fill, 11 region,
12 component body.

**Common primitive header** (start of the main subrecord of arcs, tracks, vias, fills, texts,
and pad subrecord 5): `u8 layer, u8 flags1, u8 flags2, u16 net, u16 polygon, u16 component,
4 bytes (0xFFFFFFFF)`. Then, per type:

- **Track (4)**, subrecord length 49: start x, y (i32), end x, y (i32), width (i32), then fields
  to skip. The first RP2040 track is on layer 57 (Mechanical 1) with width 0: an outline drawing.
- **Arc (1)**, subrecord length 60: centre x, y (i32), radius (i32), start angle (f64),
  end angle (f64), width (i32). Angles in degrees CCW; 0→360 is a full circle. Example: radius
  39370 (0.1 mm), width 100000 (10 mil), on layer 33 (Top Overlay).
- **Via (3)**, subrecord length 321: position x, y (i32), diameter (i32), hole (i32), start layer
  (u8), end layer (u8), then per-layer sizes and more (skip). Example: diameter 177165 (0.45 mm),
  hole 98425 (0.25 mm), layers 1→32.
- **Pad (2)**, six subrecords: (1) name: u8 length + ASCII ("1", "2", …); (2) 1 byte; (3) a short
  string like `|&|0`; (4) 1 byte; (5) geometry, 186 bytes here: common header, position x, y,
  size top x/y, size mid x/y, size bottom x/y (i32 each), hole size (i32, 0 = SMD), shape top /
  mid / bottom (u8: 1 round, 2 rectangle, 3 octagonal; verify the rounded-rectangle code, likely
  9, and the corner radius in subrecord 6), rotation (f64, e.g. 180.0), plated flag, … ;
  (6) optional per-layer sizes / hole shape / slot / corner radius (present only in some pads;
  verify its layout before using it, otherwise ignore). Example: pad "1" of component 13, net 44,
  0.3 × 0.27 mm, rectangle, rotated 180°.
- **Fill (6)**: rectangle x1, y1, x2, y2 (i32) and rotation (f64) after the header (verify;
  `Fills6` is empty in the RP2040 file).
- **Region (11)**, in `Regions6` and `ShapeBasedRegions6`: header, then `u32 length` + a property
  string (`V7_LAYER=BOTTOMOVERLAY|KIND=0|SUBPOLYINDEX=-1|…|ISSHAPEBASED=FALSE|…`), then
  `u32 vertex count` and the outline. In `Regions6` vertices are pairs of `f64` in internal units
  (e.g. 4.39e7 → 4390 mil); in `ShapeBasedRegions6` each vertex is 37 bytes (u8 is-arc flag,
  x, y, centre x, y, radius as i32, start and end angle as f64; verify), followed by hole
  counts and holes. Copper pours appear as shape-based regions with a net and a polygon index.
  Use `KIND` to skip keepouts / board cutouts (verify the values) and `V7_LAYER` for the layer.
- **Text (5)**: header, position, height, font data, and the text itself either inline or via
  `WideStrings6` (index → UTF-16 string; `WideStrings6/Data` is a list of u32 index + u32 byte
  length + UTF-16LE text). Low priority: the app shows designators from components, not
  silkscreen text. Read it only for `Board.texts` if cheap.
- **Component body (12)**, in `ComponentBodies6`: header (component index), property string with
  `V7_LAYER=MECHANICAL13`, `OVERALLHEIGHT=11.811mil`, `STANDOFFHEIGHT=…`, `BODYCOLOR3D=…`, then the
  body outline (same vertex format as regions; verify). Use it for `Component.outline` and
  `heightUm` (the realistic view draws bodies from `outline`).

**Text record streams:**

- `Components6`: one record per component, in index order. `LAYER` (TOP/BOTTOM), `X`, `Y`,
  `ROTATION`, `PATTERN` (footprint), `SOURCEDESIGNATOR` (designator), `SOURCEDESCRIPTION`,
  `SOURCELIBREFERENCE`, `SOURCEFOOTPRINTLIBRARY`, `FOOTPRINTDESCRIPTION`, `UNIQUEID`. If
  `SOURCEDESIGNATOR` is missing, use the component's designator text (Texts6 records with the
  "Designator" string for that component; verify).
- `Nets6`: `NAME` per net, in index order.
- `Board6`: outline as numbered vertices `KIND<i>` (0 line, 1 arc), `VX<i>`, `VY<i>`, and for arcs
  `CX<i>`, `CY<i>`, `R<i>`, `SA<i>`, `EA<i>`; layer stack (see above); `ORIGINX/Y`.
- `Polygons6`: pour definitions (outline, `NET` index, layer). The poured copper itself is in the
  region streams; use Polygons6 only to name/net pours if regions lack it.

### Implementation steps

1. **Binary reader helper** `core/parser/.../altium/BinaryReader.kt`: little-endian cursor over a
   ByteArray with `u8/u16/i32/f64`, `skip`, `subrecord { }` that bounds reads to the declared
   length and always leaves the cursor at its end, and safe failure (throw
   `BoardParseException` with the stream name and offset, never `IndexOutOfBounds`).
2. **`PcbDocParser`** (`core/parser/.../altium/PcbDocParser.kt`, pure Kotlin):
   `parse(bytes, name, onProgress): Board`. Read text streams first (nets, components, board
   outline, layer stack), then the binary streams. Build `Board(name, SourceFormat.PCBDOC, …)`:
   - outline polygon from Board6 (tessellate arcs with `arcPoints` from `core/model/Geometry.kt`);
   - layers (table above);
   - pads with `componentRef`, `name`, `side` (layer 32 or bottom component → BOTTOM), shape,
     size (top size for SMD on top, bottom size for bottom, top size for through-hole), drill,
     rotation, net, `layer`; set `Component.padIds`;
   - vias, tracks (segments) and arcs (as `Track` with `arcCenter`; see how Ipc2581Parser builds
     arc tracks and copy its convention for `clockwise` and start/end);
   - holes (vias and drilled pads, deduplicated like Ipc2581Parser does);
   - fills from regions (copper pours with net, silkscreen/mask regions) and fills;
   - components: refDes, side, position, rotation, footprint, description, libraryRef,
     `bounds` (from the body outline if present, else the pads' bounding box inflated a little,
     same idea as IPC), `outline`, `heightUm`;
   - nets map (`Net(name, padIds)`).
   Unknown record types: skip by length. Missing data: null. Never crash on a damaged primitive:
   skip it and count it; if anything was skipped, report one notice through the importer.
3. **Importer** (`BoardImporter.import`):
   - geometry precedence: IPC-2581 > PcbDoc > Pick & Place. If IPC-2581 and PcbDoc are both
     chosen, open the IPC-2581 and give a notice that the PcbDoc was skipped (rephrase
     `pcbDocSkipped`; it no longer needs to say "not recommended", say IPC-2581 was used).
   - PcbDoc alone (or with .SchDoc / BOM): parse it. Remove `PCBDOC_ONLY` and
     `PCBDOC_WITH_SCHEMATIC`; on a parse failure the message says the file could not be read and
     suggests exporting IPC-2581 (keep the export path text from the old message).
   - BOMs still merge with `withBom` (same as IPC).
   - Update `NativeAltiumFilesTest` (its "PcbDoc alone explains it is not recommended" test
     changes meaning) and the docs that mention "recognised, not read".
4. **Tests** (`core/parser/src/test/.../altium/PcbDocParserTest.kt`):
   - Synthetic, always run: build a tiny PcbDoc with `CompoundFileWriter` (Board6 outline + one
     layer stack, Nets6 with 2 nets, Components6 with one top and one bottom part, Pads6 with an
     SMD rectangle pad and a through-hole round pad, one via, one track, one arc, one region).
     Assert every decoded value. Also a truncated-stream test (no crash, partial board +
     notice) and an unknown-record-type test (skipped).
   - Real, skip when missing (`Rp2040RealBoardTest` or a new `Rp2040PcbDocTest`): open the
     `.PcbDoc` and the `.cvg`; assert same refDes set (17), same side and rotation per part,
     position within 1 µm; per part the same pad names; per pad position and size within 1 µm,
     same net name (where the IPC net is not null). Print a short diff when something differs
     before asserting, so mismatches are easy to read. Expect float rounding at the µm level
     only (IPC uses mm with 6 decimals; 0.0001 mil = 0.00254 µm).
   - Render a PNG preview of the PcbDoc board with the test renderer `BoardPng`
     (`core/parser/src/test/.../ipc2581/BoardPng.kt`, see `Ipc2581RealBoardTest.renderPreview`) to
     `core/parser/build/renders/RP2040-Module-pcbdoc.png` and compare it visually (Read the PNG)
     with the IPC one.
   - Schematic matching still works with a PcbDoc board: `SchematicConnectivity.matchBoard` on
     the PcbDoc board gives the same 45/47 result as with the `.cvg`.
5. **App**: nothing structural. `SourceFormat.PCBDOC` → the top bar subtitle already says
   "PcbDoc"; check `StartScreen` recents label (`when (b.source)`) handles "PCBDOC". The picker
   already accepts any file. Annotations are keyed by `"${source}:${name}:${parts}"`, so a board
   opened from PcbDoc and from IPC-2581 has **different** keys (status paint does not carry
   over). Tell the user and offer the alternative (a key without the source) instead of changing
   it silently; changing keys needs a Room migration (CLAUDE.md rule).
6. **Performance**: parse in one pass per stream on `Dispatchers.Default` (the importer already
   runs there); report progress through `onProgress` (e.g. weighted by stream size). A 1.5 MB
   PcbDoc must open in well under a second on the JVM; keep allocations per primitive low.
7. **Docs and wrap-up**: ARCHITECTURE (parser section: `PcbDocParser`, `BinaryReader`, format
   notes in short), CHANGELOG (Unreleased), ROADMAP (S5b done; IPC-2581 no longer required),
   CLAUDE.md (status, any new facts learned, e.g. sub-record layouts you confirmed), README if it
   lists supported formats. Gate, commit. Close issue #7 only when asked.

### Risks and how to handle them

- Layouts differ between Altium versions (sub-record lengths grow). Always rely on declared
  lengths, read only the fields you need from the start of a sub-record, and test with the
  synthetic file plus the real one.
- Pads with per-layer shapes (sub-record 6) and slots: if unsure, use the top shape and record a
  TODO in the ROADMAP; mismatches show up in the RP2040 comparison.
- Board origin offsets: see "Coordinates" above.
- Polygon pours: regions can be huge; keep them as `Fill` polygons with holes (non-zero fill rule:
  CCW outer rings, CW holes, see `BoardScene`).

## Phase C — follow-ups (ask the user which first)

Small, independent items; each is its own commit:

1. **Duplicate designators warning.** The RP2040 schematic has two symbols named `RP2040`
   (chip and module header), which mixes their pins in net matching. Detect duplicates of
   different library parts in `Schematic` and show one notice on open (`onNotice`) and in the
   symbol panel. Test with a synthetic sheet.
2. **Hierarchical schematics.** Today nets join by name across all sheets (flat). Implement
   sheet symbols (RECORD=15), sheet entries (16) and ports (18) scope: ports connect to the
   parent sheet's entry of the same name; net labels stay local to their sheet unless the
   project uses global labels. Needs a sample from the user; ask for one before starting.
3. **Soldering checklist** (from the original plan, ROADMAP "Later"): a panel listing parts
   grouped by value/footprint with counts and status, tap a group to highlight it; keep the
   screen on while a board is open (`FLAG_KEEP_SCREEN_ON` / `Modifier.keepScreenOn()`, setting).
4. **Signed release APK** (ROADMAP "Later"): needs a keystore the user creates and keeps; do not
   generate or commit one without asking.
5. Dependency updates (lint lists newer AGP, Compose BOM, Room, …): only on request, one at a
   time, with a full gate and a tablet test.
