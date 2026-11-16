# Flux — agent guide

Flux is an Android app (Kotlin, Jetpack Compose) for viewing Altium PCB exports at a soldering
bench on a Samsung Galaxy Tab S6 Lite (2000×1200, S Pen). Package/applicationId is still
`com.pcbsolderview` (the app was renamed to Flux; the id stays so installs update in place).

Read `docs/ARCHITECTURE.md` before changing anything non-trivial. Implementation plans for agents:
`docs/PLAN-android-next.md` (v0.1 release, native .PcbDoc reader, follow-ups) and `docs/PLAN-desktop.md`
(Windows version; Android is the main version, the PC version follows only when the user asks).
`ROADMAP.md` says what is next,
`CHANGELOG.md` what shipped. The original product brief is the PDF in the repo root
("Plano — App Android visualizador de PCB…").

## Environment (Windows 11, no Android Studio)

- JDK 17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot`. The tool shells may not see
  JAVA_HOME: PowerShell `$env:JAVA_HOME="…"`, Bash `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.21.9-hotspot"`.
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (platform 35, build-tools 35.0.0). `local.properties` points to it (not committed).
- git: `C:\Program Files\Git\cmd\git.exe` (may not be on PATH in PowerShell; Bash has it).
- GitHub CLI: `C:\Program Files\GitHub CLI\gh.exe`, signed in as ViniciusAlexandre05.
  Repo: https://github.com/ViniciusAlexandre05/Flux (private).
- No emulator or device is attached. You cannot run the app; the user tests on the tablet.

## Commands

```bash
./gradlew test lint assembleDebug          # the gate: run before every commit; must be green
./gradlew :core:parser:test                # parser tests only (fast)
./gradlew --stop                           # if lint fails with "file is being used by another process"
```
- APK: `app/build/outputs/apk/debug/app-debug.apk`. Version check:
  `"$LOCALAPPDATA/Android/Sdk/build-tools/35.0.0/aapt2.exe" dump badging <apk> | grep ^package:`
- In Bash, redirect stdin for long commands (`< /dev/null`); a stray `cat` with no input hangs the tool.
- The user's switch: `dev.cmd` (PowerShell `.\dev`, script in `scripts/dev.ps1`): `dev android` /
  `dev pc` chooses the version they work on (stored in the gitignored `.flux-mode`, default
  android); `dev build | run | test | status` act on it (Android build = the gate; `run` installs
  on a USB tablet if one is connected). When the user says "build/test/run it" without saying
  which version, read `.flux-mode`. The PC module is included only with `-Pdesktop`
  (settings.gradle.kts), never by the Android gate.
- PC version (`desktop/`): `./gradlew -Pdesktop :desktop:test` (JVM tests + off-screen UI renders in
  `desktop/build/renders/ui-*.png`, read them as images), `:desktop:run` (`-PfluxArgs="a|b"` opens
  files, `-PfluxHome=<dir>` uses another data folder), `:desktop:createDistributable` (folder with
  `Flux.exe` in `desktop/build/compose/binaries/main/app/Flux/`), `:desktop:packageMsi`.
  Unlike Android, the PC app can be run and screenshotted here. Unsigned `Flux.exe` is blocked by
  Windows Application Control on this machine; `:desktop:run` (signed java.exe) works.
- **Android first.** The PC version follows only when the user asks ("update the PC version"):
  procedure in `docs/PLAN-desktop.md` section 6, baseline commit in `desktop/SYNC.md`. Never edit
  Android code for the desktop; the Android gate must not include `:desktop`.

## Module map

| Module | What lives there |
|---|---|
| `core/model` (pure Kotlin) | `Board` model in **micrometres (Long), Y up**; `Component`, `Pad`, `Track`…; search, identical parts, mount filter; `Schematic` model, `SchematicLayout`, `SchematicConnectivity` |
| `core/parser` (pure Kotlin) | `BoardImporter` (entry point, file sniffing, .zip), IPC-2581, Pick & Place, BOM (CSV/XLSX), `altium/` (compound file, records, SchDoc) |
| `core/render` (Android lib) | `ViewTransform` (the only Y flip), `ViewGestures` (all gestures), `BoardScene`/`BoardCanvas`, `SchematicScene`/`SchematicCanvas`/`SchematicColors`, hit-testing (`GridIndex`), grid, realistic view, thumbnails |
| `app` | `BoardViewModel` (all UI state), screens in `ui/` (`BoardPane`, `SchematicPane`, `SplitPanes`, `PdfPane`), Room DB + DataStore in `data/` |
| `desktop` (Compose Desktop, only with -Pdesktop) | Windows version: shared `core/model` + `core/parser`; ported copies of `core/render` and `app` (same packages), `platform/` (folders, dialogs, PDFBox), JSON storage in `%APPDATA%lux`; `desktop/sync.md` lists every change from android |

## Rules that are easy to break

- Coordinates are µm `Long`, Y up, everywhere outside drawing. Convert to screen only through
  `ViewTransform` (`toScreenX/Y`, `toBoard`). Never flip Y anywhere else.
- Parsers stay pure Kotlin (no Android imports); XML goes through `XmlParserFactory`
  (kXML in tests, `Xml.newPullParser()` in the app).
- Missing data is `null` in the model and shows as "—" in the UI. Never crash on a missing field.
- `samples/real/` holds the user's real designs: `double-sided/IEPE_CONDITIONER_V1.cvg` (IPC-2581 board)
  and `RPI_Module/` (native `.SchDoc` + `.PcbDoc`, `RP2040-Module.cvg` IPC-2581 export of the same
  board, Altium PDF, project files). It is **gitignored and
  must never be committed or pushed**. Tests that need it skip when it is missing. Synthetic
  fixtures in `samples/synthetic/` are committed and double as in-app demos.
- Room schema changes need a version bump **and** a `Migration` in `AnnotationDb.kt` that keeps
  user data (current version 4). Compare your SQL with the generated
  `app/build/generated/ksp/debug/java/.../AppDatabase_Impl.java`.
- Status colours live in `Status` (`BoardViewModel.kt`). `StatusColorsTest` enforces ΔE ≥ 35
  between them (raw and painted over the board). Changing them needs a DB migration (see 3→4).
- New settings that must survive restarts go in `Appearance` (`data/AppearancePrefs.kt`):
  DataStore key **and** the SharedPreferences mirror (`snapshot`), used before first frame.

## Known pitfalls

- Gestures (`ViewGestures.kt`, shared by the board, schematic and PDF views): measure movement **before** `consume()` (use
  `positionChangeIgnoreConsumed()`); `positionChange()` returns zero for consumed changes and
  once turned every S Pen stroke into a tap.
- S Pen side button: Compose hides it (`PointerEvent.motionEvent` is internal). `MainActivity`
  forwards raw `MotionEvent`s to `StylusInput`. Don't add automatic mode switches the user did not ask for.
- Line endings: `core.autocrlf=true`; checked-out files may be CRLF or mixed. Multi-line
  `perl`/`sed` patterns need `\r?\n` or they silently change nothing; verify with grep.
- Windows PowerShell 5.1 mangles double quotes in native arguments: use `gh … --notes-file`.
- `Canvas` doesn't clip by default: keep `clipToBounds()` on board views.

## Verifying without a device

- Parser output: `Ipc2581RealBoardTest.renderPreview` writes
  `core/parser/build/renders/IEPE_CONDITIONER_V1.png` (CAD) and `…-realistic.png`; read them as images.
  The preview renderer (`BoardPng`, test code) mirrors the app's drawing rules; keep it in step.
- Schematic output: `SchematicLayoutTest.renderPreviews` writes `core/parser/build/renders/schematic-*.png`
  (`SchematicPng` draws `SchematicLayout`, the same geometry the app draws; keep them in step).
- Part data report: `core/parser/build/reports/components-IEPE_CONDITIONER_V1.csv`.
- Vector icons: convert to SVG and screenshot with headless Edge
  (`"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --headless=new --screenshot=… file.html`).

## Working with this user

- The user writes English or Portuguese; answer in the language of their message.
- Commit when they ask (they often ask "commit"/"push"); end commit messages with the
  attribution line from the system reminder. Push or release only when asked.
- Releases: bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, add a section to
  `CHANGELOG.md`, a line to `ROADMAP.md`, clean build + tests, commit "Version x.y.z", annotated
  tag `vx.y.z`, push main and the tag, `gh release create vx.y.z <apk renamed Flux-x.y.z-debug.apk> --notes-file …`.
  0.0.x = incremental releases; **v0.1 is reserved for schematic sync**.
- They test on the tablet and report problems in short messages; ambiguous wording is common,
  so state your interpretation and offer the alternative.

## Status (2026-11-16)

Released: v0.0, v0.0.1, v0.0.2, **v0.1** (latest, versionCode 4, 2026-11-16): schematic sync (S0,
S5, S1–S4, S6), native .PcbDoc reader (S5b, `PcbDocParser`) and the Windows version (desktop
0.1.0; the GitHub release carries the APK, the MSI and a portable zip). Released at the user's
request **before the tablet test**: schematic and .PcbDoc work is tested on the JVM only. Next:
the user's tablet test, then fixes (0.1.x).

Schematic facts learned from the RP2040 sample (`samples/real/RPI_Module/`):
- Record keys are mixed case (`|RECORD=34|…|Text=C1`): `AltiumRecord` upper-cases keys.
- `PARTCOUNT` is the number of parts + 1; pins hold absolute positions; the connection point is
  LOCATION + PINLENGTH along `PINCONGLOMERATE and 3`; omitted booleans are false, omitted
  ELECTRICAL is input (0).
- Descriptions may contain line breaks in native files (normalised to spaces).
- Two symbols share the designator `RP2040` (chip and module header): never key pins by
  designator inside connectivity (`SchematicConnectivity` uses per-symbol ids).
- The board has 17 parts vs 42 schematic designators (layout incomplete); the board's RP2040 is
  the 38-pad module footprint. 45/47 board nets match.
- Check rendering with `SchematicLayoutTest.renderPreviews` →
  `core/parser/build/renders/schematic-RP2040-Module.png` (compare with `RPI_Module.pdf`).
