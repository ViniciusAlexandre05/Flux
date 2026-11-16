# Flux

Android viewer for printed circuit boards at the soldering bench. Open an Altium export on a
tablet, find any part, follow its nets, and mark what is soldered, checked or faulty.

Built for the Samsung Galaxy Tab S6 Lite (Android 9+), works offline, needs no permissions
beyond the files you open.

## What it does

- Opens **Altium IPC-2581** exports (`.cvg` / `.xml`) with full board geometry, or a
  **Pick & Place** file with its **BOM** when only component positions are available.
- Shows the board as **CAD layers** (Altium colours) or as a **realistic** assembled board.
- **Parts mode:** tap a part for its value, description, part number, footprint, height,
  pins and nets. **Nets mode:** tap a track, via, pad or plane to light up the whole net.
- **Status painting** (OK / Problem / Check / Info), drawings with the **S Pen**, highlight of
  identical parts, box selection, and batch status, all saved per board.

For developers and coding agents: [CLAUDE.md](CLAUDE.md) (environment, commands, rules) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (how the code fits together).

See [CHANGELOG.md](CHANGELOG.md) for the full list and [ROADMAP.md](ROADMAP.md) for what's next
(schematic view synchronised with the board).

## Exporting from Altium

| What | Altium menu | Notes |
|---|---|---|
| IPC-2581 (recommended) | File › Fabrication Outputs › IPC-2581 Files | Include copper, silkscreen and the board-outline layer |
| Pick & Place | File › Assembly Outputs › Generates pick and place files | CSV, metric or imperial |
| BOM | Reports › Bill of Materials | CSV or XLSX; add *Manufacturer Part Number* and *Manufacturer* columns to get exact part numbers |

Select several files at once in the app (for example the `.cvg` and the BOM) to combine them.

## Install

Download the APK from the [latest release](../../releases) on the tablet and open it.
Android asks once to allow installing apps from your browser or file manager.

## Build

Requirements: JDK 17 and the Android SDK (platform 35).

```powershell
.\gradlew.bat assembleDebug        # APK in app\build\outputs\apk\debug\
.\gradlew.bat test lint            # unit tests and lint, the gate for every change
```

Shortcut that also sets `JAVA_HOME` and switches between the Android and PC versions:

```powershell
.\dev                # which version you are working on, branch, versions
.\dev android        # work on the Android app (default)
.\dev pc             # work on the PC version (docs/PLAN-desktop.md; not built yet)
.\dev build          # Android: tests + lint + APK.  PC: tests + app folder with Flux.exe
.\dev run            # Android: install on a USB-connected tablet.  PC: start the app
.\dev test           # tests of the chosen version
```

## Project layout

| Module | Contents |
|---|---|
| `core/model` | Format-independent board model (micrometres, Y up), search |
| `core/parser` | IPC-2581, Pick & Place and BOM readers (pure Kotlin, no Android) |
| `core/render` | View transform, hit-testing, board drawing (Jetpack Compose) |
| `app` | Screens, settings, storage of annotations and recent boards (Room, DataStore) |

## Test files

`samples/synthetic/` holds small hand-written example boards used by the tests and as in-app
demos. Real board exports go in `samples/real/`, which is ignored by git: designs stay on the
machine, and the tests that need them are skipped when they are missing.
