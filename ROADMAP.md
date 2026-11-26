# Flux roadmap

## v0.0 — released

Board viewer for the soldering bench: IPC-2581 and Pick & Place/BOM import, CAD and realistic
views, parts/nets inspection, status painting, drawing with the S Pen, recent boards, settings.

## v0.0.1 — released

Richer part details (part number, height, mounting, library) checked against the file and
selectable in Settings; batch status on highlighted parts; box selection; vias in the
realistic view. See [CHANGELOG.md](CHANGELOG.md).

## v0.0.2 — released

More distinct status colours with symbols; stronger selected-part highlight; tap/hold on
"highlight identical"; All / SMD / PTH filter and search in the highlight chip; "Select all"
in search results.

## v0.1 — released 2026-11-16 (schematic sync, native .PcbDoc, Windows version)

Released before the tablet test, at the user's request; the open points below still apply.

Tracked in the [v0.1 Schematic sync milestone](https://github.com/ViniciusAlexandre05/Flux/milestone/1).

**Status 2026-11-05:** S0, S5, S1, S2, S3, S4 and S6 are implemented and tested on the JVM
(parser, layout, connectivity, colours). The UI has not been tested on the tablet yet. Still open:
- **Tablet test** of everything below (gestures moved in S0; split view, follow selection, PDF).
- **S2 "done when"**: compare a sheet on the tablet with Altium. The JVM preview
  (`core/parser/build/renders/schematic-RP2040-Module.png`) already reads like Altium's PDF.
- **S3 timing** (highlight on the other side within 100 ms) needs a measurement on the device.
- **S5b** (native PcbDoc) done on the JVM (2026-11-16): the RP2040 .PcbDoc gives the same parts,
  pads, nets and vias as its IPC-2581 export. Silkscreen text (Texts6) is drawn since 2026-11-17.
  Boards opened from .PcbDoc and from IPC-2581 have different annotation keys (source is in the key).
- Hierarchical designs: sheet symbols are drawn, but nets join by name across all sheets.
- The RP2040 sheet has two symbols designated `RP2040` (the chip and the module header). Fix
  the annotation in Altium, otherwise the board's `RP2040` (module footprint, 38 pads) and the
  chip (57 pins) stay mixed up. 45 of 47 board nets line up with the schematic.

Show the Altium schematic next to the board (split screen) and keep both in step: selecting a
part, pin or net on one side highlights it on the other.

**Input:** `.SchDoc` saved as ASCII first, then native `.SchDoc`; Smart PDF only as a view-only
fallback. Parts link by designator (`U201A`/`U201B` → `U201`), pins by designator + pin number,
nets by the pins they connect (so unnamed nets line up too).

**S0 — preparation (from the 2026-11-05 code review), before S1. Done 2026-11-05** (awaiting
the tablet re-test of gestures): `ImportResult(board, schematic?)` + placeholder `Schematic`
model, `UiState.schematic`; `Modifier.viewGestures` / `ViewGestureState` (`ViewGestures.kt`);
`BoardViewState` + `BoardPane` (`ui/BoardPane.kt`); `GridIndex<T>` under `ComponentIndex`.
The original plan, kept for reference: four places assumed there is only a board:

1. **Import result.** `BoardImporter.import()` returns a `Board`. Native `.PcbDoc`/`.SchDoc`
   are already recognised (`FileKind.ALTIUM_PCBDOC`/`ALTIUM_SCHDOC`, OLE magic + extension or
   record text) and reported through `onNotice` / a specific `UnsupportedFormatException`.
   Return an import result that can also carry a schematic (`Board` + `Schematic?`); add a
   `FileKind` for ASCII `.SchDoc` if ever needed. `BoardStore` already keeps every opened file,
   so recents will reopen schematics for free.
2. **Reusable gestures.** Pan, pinch, guarded rotation with snapping, long-press, S Pen drawing
   and taps live inside `BoardCanvas`'s `pointerInput`. Move them into a shared gesture modifier
   that both `BoardCanvas` and the future `SchematicCanvas` use. Behaviour must stay identical
   (see the pitfalls in `CLAUDE.md`); the user re-tests on the tablet afterwards.
3. **Board pane.** `BoardContent` (`ui/BoardScreen.kt`) mixes the board area, its overlays and
   the side panel. Extract a `BoardPane` (canvas + overlays) so the split screen can place a
   `SchematicPane` beside it, sharing `UiState` selection.
4. **Generic hit index.** `ComponentIndex` is tied to `Component`. Generalise it to a grid index
   over any items with a bounding box, for schematic symbols, pins and wires.

| Phase | Work | Estimate | Done when |
|---|---|---|---|
| [S1](https://github.com/ViniciusAlexandre05/Flux/issues/1) | Schematic model + ASCII SchDoc reader | 2–3 days | Parts and pins in the sample sheets match the board's parts and pads one for one (test) |
| [S2](https://github.com/ViniciusAlexandre05/Flux/issues/2) | Schematic viewer (pan, zoom, snap-rotation, sheet tabs, themed colours) | 2 days | A sheet reads like Altium's view, compared with a screenshot |
| [S3](https://github.com/ViniciusAlexandre05/Flux/issues/3) | Split screen + shared selection (Board / Split / Schematic, draggable divider, follow selection) | 2 days | Tapping any part on either side highlights it on the other within 100 ms |
| [S4](https://github.com/ViniciusAlexandre05/Flux/issues/4) | Net tracing across views (wires, junctions, labels, power ports) | 1–2 days | Every board net in the samples has exactly one matching schematic net |
| [S5](https://github.com/ViniciusAlexandre05/Flux/issues/5) | Native SchDoc reader (binary container; also unlocks `.PcbDoc` later) | 1–2 days | A native file and its ASCII copy produce the same model |
| [S5b](https://github.com/ViniciusAlexandre05/Flux/issues/7) | Native PcbDoc reader (same container; Altium's binary primitive streams) | 2–3 days | The RP2040 board opened as `.PcbDoc` and as its IPC-2581 export gives the same parts, pads and positions within 1 µm |
| [S6](https://github.com/ViniciusAlexandre05/Flux/issues/6) | Projects and polish (open board + schematic together, store with recents, PDF fallback) | 1–2 days | A recent board reopens with its schematic, annotations and status intact |

Total: about 11–16 days of work (S5b can move after S6 if time is short).

**Samples (local only, never pushed):** `samples/real/RPI_Module/` holds an Altium project for an
RP2040 board: `RP2040-Module.SchDoc` and `RP2040-Module.PcbDoc` (both **native binary**, OLE
compound files), **`RP2040-Module.cvg` (IPC-2581 export of the board, added 2026-11-05)**, a
2-page Altium PDF, and project/output files. The schematic contains 43
components, 195 pins, 159 wires, 96 net labels (46 names), 38 power ports (1V1, 3V3, GND, VSYS,
VUSB) and 21 junctions; the RP2040 symbol is multi-part (`PartCount=2`); some designators are
names ("RP2040", "FLASH", "USB-C", "LED") and one is unannotated (`*`).

**Order change:** because the samples are native binary, do **S5 (container reader) right after
S0**, then S1 parses the records (same record format as the ASCII export, so no ASCII files are
needed). Full order: **S0 → S5 → S1 → S2 → S3 → S4 → S6**; S5b (PcbDoc) any time after S5.

**Board vs schematic (RP2040, `Rp2040RealBoardTest`):** the `.cvg` imports cleanly with 17
parts (every part has pads); the schematic has 42 designators. 16 board parts match by
designator. Only on the board: `RP`. Only in the schematic: 26 (`*`, C13, C16, C17, D1, L1,
LED, Q1, R1–R4, R7, R10, R11, R14–R18, TP1–TP3, TP5, U1, USB-C). So the layout holds only part of
the schematic (unfinished layout, or exported from an older/partial PcbDoc; Altium's report says
"Project [Free Documents]", i.e. the PcbDoc was exported outside the project). S3/S4 must
handle schematic parts that are not on the board (show them greyed, no crash). Open question
for the user: is the layout really incomplete, and what is `RP`?

**Risks:** SchDoc has no public specification (KiCad's Altium importer is a structural reference
only; it is GPL, so it is studied, not copied); hierarchical sheets; fonts differ from Altium's;
parts in the schematic that are not placed on the board.

## Plans for agents

- `docs/PLAN-android-next.md`: v0.1 release after the tablet test, S5b native .PcbDoc reader
  (format facts verified on the RP2040 file), follow-ups.
- `docs/PLAN-updates.md`: in-app updates from GitHub for Android and PC (decided 2026-11-18: Flux goes public, debug key kept, "Check now" only, test versions behind a switch).
- `docs/PLAN-desktop.md`: Windows version (Compose Multiplatform desktop executable), synced
  with Android only on request. D1–D5 done 2026-11-16 (desktop 0.1.0, unreleased); next: user test on PC.

## 0.1.3 — released 2026-11-26 (Android in-app updates; PC version number only)

Settings › Updates on Android (Check now, install, all versions, test versions) and the release
script with `flux-release.json` (docs/PLAN-updates.md U0–U2). Test pre-release 0.1.4-test1 (same
app) to try the update from inside the app. Still open: that tablet test.

**U3 (PC updater) implemented 2026-11-26 (unreleased):** Settings › Updates and Help › Check for
updates… on the PC; MSI and portable folder, newer and older versions. Needs a release after it
(installed by hand once) and a test on a PC that allows unsigned builds.

## 0.1.2 — released 2026-11-23 (Android and PC)

Board and Schematic on/off buttons (instead of the three-way switch) and schematic appearance
settings (paper, colours, traced net, grid, text size) under Settings › Appearance › Schematic view.

## 0.1.1 — released 2026-11-18 (Android and PC)

The three follow-ups below, highlights limited to the side being viewed, and the PC synced with
Android plus Ctrl+drag / Ctrl+click selection. Released before the tablet test.

## 0.1.x (Android, 2026-11-17)

Follow-ups from `docs/PLAN-android-next.md` Phase C, in order:
- Duplicate designators in a schematic: notice on open, warning in the part panel. Done.
- Soldering checklist grouped by kind, keep the screen on while a board is open. Done.
- Silkscreen text strings from native .PcbDoc (Texts6), the last missing piece of S5b. Done.

## Later (from the original plan)

- Signed release APK (needs a keystore the user creates and keeps)
