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

## v0.1 — Schematic sync (next)

Tracked in the [v0.1 Schematic sync milestone](https://github.com/ViniciusAlexandre05/Flux/milestone/1).

Show the Altium schematic next to the board (split screen) and keep both in step: selecting a
part, pin or net on one side highlights it on the other.

**Input:** `.SchDoc` saved as ASCII first, then native `.SchDoc`; Smart PDF only as a view-only
fallback. Parts link by designator (`U201A`/`U201B` → `U201`), pins by designator + pin number,
nets by the pins they connect (so unnamed nets line up too).

**S0 — preparation (from the 2026-11-05 code review), before S1.** The code is clean (no
compiler warnings, lint clean, tests green) but four places assume there is only a board:

1. **Import result.** `BoardImporter.import()` returns a `Board` and silently ignores unknown
   files. Return an import result that can also carry a schematic (`Board` + `Schematic?`), and
   add `FileKind`s for `.SchDoc` (ASCII and binary) so schematic files are recognised.
   `BoardStore` already keeps every opened file, so recents will reopen schematics for free.
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
| [S6](https://github.com/ViniciusAlexandre05/Flux/issues/6) | Projects and polish (open board + schematic together, store with recents, PDF fallback) | 1–2 days | A recent board reopens with its schematic, annotations and status intact |

Total: about 9–13 days of work.

**Blocked on:** the `IEPE_CONDITIONER` schematic sheets, as original `.SchDoc` files and saved
as ASCII, placed in `samples/real/double-sided/` (kept local, never pushed). A Smart PDF of the
schematic helps compare the drawing.

**Risks:** SchDoc has no public specification (KiCad's Altium importer is a structural reference
only; it is GPL, so it is studied, not copied); hierarchical sheets; fonts differ from Altium's;
parts in the schematic that are not placed on the board.

## Later (from the original plan)

- Soldering checklist grouped by value, keep screen on while open
- Signed release APK
- Opening `.PcbDoc` directly (shares the container reader from S5)
