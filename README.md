# Flux

Android viewer for printed circuit boards at the soldering bench. Open an Altium export on a
tablet, find any part, follow its nets, and mark what is soldered, checked or faulty.

Built for the Samsung Galaxy Tab S6 Lite (Android 9+), works offline, needs no permissions
beyond the files you open.

## What it does

- Opens Altium boards directly (**`.PcbDoc`**) or as **IPC-2581** exports (`.cvg` / `.xml`) with full board geometry, or a
  **Pick & Place** file with its **BOM** when only component positions are available.
- Shows the board as **CAD layers** (Altium colours) or as a **realistic** assembled board.
- **Parts mode:** tap a part for its value, description, part number, footprint, height,
  pins and nets. **Nets mode:** tap a track, via, pad or plane to light up the whole net.
- **Status painting** (OK / Problem / Check / Info), drawings with the **S Pen**, highlight of
  identical parts, box selection, and batch status, all saved per board.

For developers and coding agents: [CLAUDE.md](CLAUDE.md) (environment, commands, rules) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (how the code fits together).

See [CHANGELOG.md](CHANGELOG.md) for the full list and [ROADMAP.md](ROADMAP.md) for what's next
(schematic view synchronised with the board).

## Flux for Windows

A PC version of the same app lives in `desktop/` (Compose for Desktop). It opens the same files
(also a whole Altium project folder, or files dropped on the window), and adds mouse and keyboard
control: wheel zoom, drag to pan, Shift+drag or Ctrl+wheel to rotate, double click to fit,
right click on a part for the status ring, hover to highlight a part, Ctrl+F search, Ctrl+Z undo,
Esc back, F fit, 1/2/3 board / split / schematic (Help › Mouse and keys). Its data lives in
`%APPDATA%lux` and is separate from the tablet's.

```powershell
.dev pc; .dev run      # start it (or: .gradlew.bat -pdesktop :desktop:run)
.dev build              # tests + desktopuildmposeinariesmainppluxlux.exe
.gradlew.bat -pdesktop :desktop:packagemsi   # installer (unsigned: smartscreen warns)
```

## exporting from altium

| What | Altium menu | Notes |
|---|---|---|
| Native board (`.PcbDoc`) | nothing to export: open the `.PcbDoc` itself | Silkscreen text strings are not drawn yet |
| IPC-2581 | File › Fabrication Outputs › IPC-2581 Files | Include copper, silkscreen and the board-outline layer |
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

## License

Flux is free to use, study and change for **non-commercial purposes** (personal use, study,
research, hobby projects, schools and other non-profit organisations) under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md). Commercial use is not allowed without
the author's written permission.

**Credit is required:** anyone who shares Flux or any part of it, changed or not, must include
the license and this notice:

> Required Notice: Copyright (c) 2026 Vinicius Alexandre Gomes (https://github.com/ViniciusAlexandre05/Flux)
