# Plan: in-app updates from GitHub (Android and PC)

For an agent implementing update checks and in-app installation in Flux. Written 2026-11-18,
against `main` at `4eb5b9e`. Read `CLAUDE.md` first; it overrides this plan where they disagree.

## Goal

Both versions (Android and the Windows PC version) check GitHub for new releases, show what is
new, and install **any** released version from inside the app: the latest one by default, or an
older one picked from a list (to go back after a bad release). The user never has to open GitHub
or a file manager.

**Done when:** a tablet running 0.1.x with the updater sees a newer test release, downloads it
from Settings, confirms the system prompt and restarts on the new version with its annotations,
statuses, recents and settings intact; the same on the PC with the MSI install and with the
portable folder.

## 0. Facts that shape the plan (verified 2026-11-18)

| Fact | Consequence |
|---|---|
| The repository `ViniciusAlexandre05/Flux` is **private**. | The app cannot read its releases without a GitHub token, and a token inside the app could be pulled out of the APK and would expose the source. The updates must come from somewhere **public** (decision D1). |
| Release `v0.1` carries `Flux-0.1-debug.apk` (60 MB), `Flux-0.1.0-windows.msi` (101 MB), `Flux-0.1.0-windows-portable.zip` (100 MB). | Asset names already follow a pattern; Android and PC have **separate version numbers** (Android `0.1`, PC `0.1.0` / now `0.1.1`) inside one tag. The app needs a small manifest to know which file is which and what version it holds. |
| The APK is a **debug build** signed with this PC's `~/.android/debug.keystore`; `versionCode` 4, `minSdk` 28, `targetSdk` 35. | Android installs an update over the existing app **only if it is signed with the same key and has a higher `versionCode`**. Losing that keystore (or building on another PC) would force an uninstall, which deletes annotations. Back it up and use it explicitly (D2). |
| The Android manifest declares **no permissions** (offline app). | Needs `INTERNET` and `REQUEST_INSTALL_PACKAGES`, and the user must allow "Install unknown apps" for Flux once. |
| PC MSI: `upgradeUuid` is fixed, `perUserInstall = true`; data lives in `%APPDATA%\Flux`, not in the install folder. | A newer MSI upgrades in place and the data survives. Uninstalling also keeps the data, so going back to an older version is possible on the PC. |
| PC uses kotlinx-serialization (JSON) already; `core/model` and `core/parser` are shared by both versions. | The update logic that is not platform-specific (manifest, versions, downloads, checksums) can live in one shared pure-Kotlin module, so the two versions cannot drift apart there. |
| Unsigned `Flux.exe` is blocked by Windows Application Control on the user's PC; `:desktop:run` works. | The PC updater must be tested with the MSI and the portable folder on a machine that allows them; under `:desktop:run` it is disabled. |

## 1. Decisions for the user (before any code)

**Decided 2026-11-18:**
- **D1: make `Flux` public**; the app reads Flux's own releases (no separate release repo).
  History checked the same day: no `samples/real/` file, keystore, `local.properties` or secret was
  ever committed. What becomes public besides the code: the author email of every commit, and
  names and details of the user's boards in tests and docs (`IEPE_CONDITIONER_V1`,
  `RP2040-Module`, part descriptions, counts). Switch visibility only after the user confirms
  (`gh repo edit --visibility public --accept-visibility-change-consequences`).
- **D2: keep the debug key**, backed up by the user, used explicitly through `local.properties`.
- **D3: no automatic check.** The app goes online only when the user taps "Check now" (or opens
  the version list). Drop `autoCheckUpdates` / `lastUpdateCheck` and the start-screen banner;
  after a successful check, Settings shows the result and the start screen shows nothing.
- **D4: test versions behind a "Show test versions" switch** (off by default).

With D1 the release script uploads to one repo only; read "the public channel" below as `Flux`.

**D1 — where the app reads releases.** (Needed: the repo is private.)
- **(Recommended) A public release-only repository**, e.g. `ViniciusAlexandre05/Flux-releases`:
  no source code, only GitHub releases with the APK, MSI, portable zip and a manifest. The source
  stays private. The release procedure uploads to both repos (one script, see U0).
- Make `Flux` public. Simplest, but the whole source becomes public.
  (`samples/real/` is gitignored and is not in the repo.)
- Not offered: a token built into the app (anyone can extract it).

**D2 — Android signing key.** In-place updates need the same key forever.
- **(Recommended) Keep the current debug key as the release key:** back up
  `~/.android/debug.keystore` somewhere safe (password manager or cloud drive) and point the build
  at the backup through `local.properties` (not committed). No reinstall is needed, so nothing is lost.
- Or create a proper release keystore now (ROADMAP "Signed release APK"). Switching keys
  needs **one uninstall** on the tablet, which deletes annotations. Do it before the updater
  ships, never after.

**D3 — automatic check.** The app is offline today.
- **(Recommended)** Check at most once a day when the start screen opens, quietly (no error when
  offline), with a Settings switch to turn it off; "Check now" is always there.
- Or check only when the user taps "Check now".

**D4 — test versions.** A "Show test versions" switch (off by default) lists GitHub
*pre-releases* too, so a fix can be tried on the tablet before it becomes the real release.
Recommended: yes.

## 2. How it works

### Release channel and manifest

Every GitHub release (in the public channel, D1) gets one extra asset, **`flux-release.json`**,
written by the release script:

```json
{
  "schema": 1,
  "tag": "v0.1.2",
  "date": "2026-11-20",
  "notes": "Short text shown in the app (from CHANGELOG)",
  "android": { "versionCode": 6, "versionName": "0.1.2", "minSdk": 28,
               "asset": "Flux-0.1.2.apk", "size": 59712205, "sha256": "…" },
  "desktop": { "version": "0.1.2",
               "msi":      { "asset": "Flux-0.1.2-windows.msi", "size": 101302336, "sha256": "…" },
               "portable": { "asset": "Flux-0.1.2-windows-portable.zip", "size": 100245687, "sha256": "…" } }
}
```

- A release may hold only `android` or only `desktop` (they are released separately at times).
- **Latest version:** `https://github.com/<owner>/<repo>/releases/latest/download/flux-release.json`.
  This is a plain download (redirect to the asset), not the REST API, so it has no rate limit.
- **All versions** (for the version list): `GET https://api.github.com/repos/<owner>/<repo>/releases?per_page=50`
  (unauthenticated: 60 requests/hour per IP, so it is fine on demand). Read each release's
  `prerelease` flag, `body` and the `browser_download_url` of `flux-release.json`, then fetch those
  manifests lazily, when the list is opened.
- Releases made before the updater existed (v0.0–v0.1) have no manifest and are **not listed**
  (they cannot install over the updater versions on Android anyway, see "Older versions").

### Shared module `core/update` (pure Kotlin, used by both versions)

New Gradle module next to `core/model` and `core/parser`; JVM only, no Android imports;
kotlinx-serialization-json (already in the version catalog for the desktop).

- `ReleaseManifest` (+ `AndroidRelease`, `DesktopRelease`, `Asset`): serializable, `ignoreUnknownKeys`.
- `Version`: parses `0.1`, `0.1.2`, `0.1.2-test1`; compares numerically (`0.1.10` > `0.1.9`);
  Android compares `versionCode` (the only thing Android itself checks).
- `ReleaseSource` interface with `latest()` and `all()`; `GitHubReleaseSource(owner, repo, http)`.
  `Http` is a tiny interface over `java.net.HttpURLConnection` (works on Android and the JVM), so
  tests use a fake.
- `Downloader`: streams to a `.part` file with progress (bytes / total), resumes with `Range` when
  the server allows it, checks **size and SHA-256** before renaming to the final name, and
  deletes the file on mismatch. Cancellable (coroutines).
- `UpdateChecker`: given the current version and settings (test versions on/off), returns
  `UpdateState` = `UpToDate | Available(manifest) | Failed(reason)`, plus `lastChecked` handling
  for the once-a-day rule.
- Tests (JVM, `core/update/src/test`): manifest parsing incl. missing parts and unknown keys,
  version ordering, the "latest" choice with and without pre-releases, checksum failure, resume,
  a fake HTTP server (`com.sun.net.httpserver.HttpServer`) for redirects and 404/403 rate-limit replies.
  All user-facing texts for failures live here (`UpdateMessages`), like `NativeFileMessages`.

### Android

- **Manifest:** `INTERNET`, `REQUEST_INSTALL_PACKAGES`. Replace the "No permissions" comment.
- **Install path:** download into `cacheDir/updates/` (no storage permission), then install with
  the **`PackageInstaller` session API** (no `FileProvider` needed): `createSession` →
  write the APK → `commit(IntentSender)` to a `BroadcastReceiver` (`UpdateInstallReceiver`).
  On `STATUS_PENDING_USER_ACTION` start the confirmation intent it carries; report
  `STATUS_SUCCESS` / failure codes as plain messages (e.g. signature mismatch → explain D2).
- **Unknown sources:** before installing, `packageManager.canRequestPackageInstalls()`; if false,
  explain in one sentence and open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Flux, then
  continue when the user comes back (`ON_RESUME`).
- **Before installing:** check `versionCode` > installed and `minSdk` ≤ device; save pending
  state (the DB is written immediately already; just make sure no drawing is in progress).
  After the update Android restarts the app; the start screen shows "Updated to 0.1.2 · what's new".
- **State:** `UpdateViewModel` (or a section of `BoardViewModel`'s app-level state if simpler),
  settings in `Appearance` (`autoCheckUpdates`, `showTestVersions`, `lastUpdateCheck`) with the
  DataStore key **and** the SharedPreferences mirror (CLAUDE.md rule).
- **UI:**
  - Start screen: a slim banner "Flux 0.1.2 is available · See what's new · Update", dismissible
    per version.
  - Settings › **Updates**: current version, "Check now", status line ("Up to date · checked 2 h
    ago", "Offline"), auto-check and test-version switches, and **All versions**: a list of
    releases (version, date, notes, "test" tag) with Install / Download buttons and a progress
    bar with Cancel. Big touch targets (56 dp), as elsewhere.
- **Older versions on Android:** Android refuses to install a lower `versionCode` over a newer
  one. The list still shows them, marked "Older: needs uninstalling Flux first, which deletes its
  annotations". The button saves the APK to Downloads (MediaStore) and explains the steps. A
  follow-up (not in this plan) is annotation export/import, which would make going back lossless.

### PC (Windows)

- **Where it runs from:** decide once at start-up:
  - `System.getProperty("jpackage.app-path") == null` → development run (`:desktop:run`):
    updater shows "Updates are off in development builds".
  - A marker file `portable.txt` next to `Flux.exe` (added to the zip by the build) → portable.
  - Otherwise → MSI install.
- **MSI (newer version):** download to `%LOCALAPPDATA%\Flux\updates\`, write a small PowerShell
  helper there that waits for the Flux process to exit, runs `msiexec /i <msi> /passive` (per-user
  install, so no admin prompt) and starts `Flux.exe` again; launch it detached and exit the app.
  Log to `updates\update.log`; on the next start, show the log's error if the version did not change.
- **MSI (older version):** MSI blocks downgrades (verify with jpackage's MSI; if it does), so the
  helper first uninstalls (`msiexec /x {ProductCode}`, read from the registry uninstall key
  under the `upgradeUuid`), then installs the older MSI. Data in `%APPDATA%\Flux` stays. Before
  that, warn: "Settings or annotations saved by a newer version may not open in an older one".
  The desktop JSON storage must use `ignoreUnknownKeys = true` (verify in `data/`).
- **Portable:** download the zip, extract next to the current folder as `Flux-<version>`,
  helper waits for exit, renames the old folder to `Flux-<old>.old`, moves the new one in place,
  starts it; any version (older or newer) works the same way. Remove `.old` folders on the next
  successful start.
- **UI:** same as Android (shared Compose code is copied into `desktop/ui` as usual), plus
  **Help › Check for updates…** in the menu bar.

### Release procedure (replaces the manual steps in CLAUDE.md)

`scripts/release.ps1 <version> [-Test] [-Android] [-Desktop]`:
1. Checks the version bumps (`versionCode` +1, `versionName`; `fluxVersion` for the PC).
2. Builds and runs the gate, builds the APK (signed with the D2 key) and, with `-Desktop`,
   `-Pdesktop :desktop:packageMsi` and the portable zip (with `portable.txt`).
3. Computes sizes and SHA-256, writes `flux-release.json` (notes from the CHANGELOG section).
4. Tags, pushes, creates the release in the private repo (as today) **and** in the public
   channel (D1) with the same assets; `-Test` marks both as pre-release.
Asset name change: `Flux-x.y.z-debug.apk` → `Flux-x.y.z.apk` (the manifest names the file, so
either works; keep "debug" only if the build stays a debug build, D2).

## 3. Phases

| Phase | Work | Estimate | Done when |
|---|---|---|---|
| U0 | Decisions D1–D4; public channel repo; keystore backup and `signingConfig`; `release.ps1` with the manifest | 0.5 day | A test pre-release in the public channel has all assets and a valid `flux-release.json` |
| U1 | `core/update` module + JVM tests | 1 day | Tests cover manifest, versions, latest/all, checksum, resume, fake server |
| U2 | Android: permissions, settings, check, banner, Settings › Updates, download, PackageInstaller install, older-version handling | 1.5–2 days | The gate is green; the user installs 0.1.x (with the updater) by hand once, then updates to a test release from inside the app on the tablet |
| U3 | PC: run-mode detection, helpers for MSI / portable, menu item, same UI (port per `docs/PLAN-desktop.md` section 6, SYNC.md entry) | 1–1.5 days | Update and roll back work for the MSI and the portable folder on a PC that allows unsigned builds |
| U4 | Docs: CLAUDE.md release procedure, ARCHITECTURE (new module), CHANGELOG, ROADMAP | 0.25 day | Docs describe the new release flow |

Total ≈ 4–5 days. **Android first** (U0 → U1 → U2); U3 only when the user asks for the PC
version, as usual.

**Bootstrap:** the first version with the updater (Android and PC) must be installed by hand,
as today. Every later one installs itself.

## 4. Verifying without a device

- `core/update` is fully testable on the JVM (fake server; real GitHub only in a manual,
  skipped-by-default test).
- Android install flow cannot run here: release a **test pre-release** (D4) and ask the user to
  update to it from the tablet; check `versionCode` and annotations afterwards.
- PC: `:desktop:test` renders the Updates UI off-screen (`desktop/build/renders/ui-*.png`); the
  helper scripts are plain PowerShell and can be dry-run with `-WhatIf`-style logging.

## 5. Risks

| Risk | Handling |
|---|---|
| The signing key is lost | D2: backup now. Without the key, in-place updates are impossible for every installed copy. |
| An update breaks the app on the tablet | "All versions" lists older ones; on the PC rollback is in-app; on Android it needs an uninstall (so test with pre-releases first). |
| Room/JSON written by a newer version, opened by an older one | Android: impossible without uninstall anyway. PC: `ignoreUnknownKeys`, warn before rolling back. |
| Large downloads (60–100 MB) on a slow network | Progress, cancel, resume with `Range`; check `size` before starting; never auto-download, only auto-check. |
| GitHub API limit (60/h) | Latest check uses the release download URL (no limit); the API is used only when the user opens the version list. |
| Tampered file | HTTPS + SHA-256 from the manifest; Android also refuses an APK signed with another key. |
| Windows SmartScreen / Application Control blocks the new build | Downloads made by the app carry no "Mark of the Web", so SmartScreen usually stays quiet; Application Control (as on the user's PC) blocks unsigned builds whatever the updater does. Code signing for the PC is a separate decision. |
| Privacy: the app starts talking to the internet | Only GitHub, no data sent besides the request itself; auto-check can be turned off (D3). |
