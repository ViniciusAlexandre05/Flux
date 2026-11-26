# Flux release: builds, writes flux-release.json, tags, pushes and creates the GitHub release.
#   .\scripts\release.ps1 0.1.3                 Android + PC, full release
#   .\scripts\release.ps1 0.1.4-test1 -Test     test version (GitHub pre-release; the app shows it
#                                               only with Settings > Updates > Show test versions)
#   .\scripts\release.ps1 0.1.3 -Android        Android only (-Desktop: PC only)
#   .\scripts\release.ps1 0.1.3 -DryRun         build and write build\release\v0.1.3\, no tag/push/release
#   -SkipBuild reuses the last build outputs (for -DryRun checks).
# Before running: bump versionCode (+1) and versionName in app\build.gradle.kts (and fluxVersion
# in desktop\build.gradle.kts for the PC), add a "## <version>" section to CHANGELOG.md, commit.
# See docs/PLAN-updates.md. The app reads the manifest, so keep its format in step with
# core/update ReleaseManifest (schema 1).
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Version,
    [switch]$Test,
    [switch]$Android,
    [switch]$Desktop,
    [switch]$DryRun,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Jdk = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
if (Test-Path $Jdk) { $env:JAVA_HOME = $Jdk }
$Git = "C:\Program Files\Git\cmd\git.exe"
$Gh = "C:\Program Files\GitHub CLI\gh.exe"
$BuildTools = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\35.0.0"
$Utf8 = New-Object System.Text.UTF8Encoding $false
if (-not $Android -and -not $Desktop) { $Android = $true; $Desktop = $true }
$Tag = "v$Version"

function Fail([string]$msg) { Write-Host $msg -ForegroundColor Red; exit 1 }
function Step([string]$msg) { Write-Host "== $msg" -ForegroundColor Cyan }
function Invoke-Gradle([string[]]$GradleArgs) {
    Write-Host "> gradlew $($GradleArgs -join ' ')" -ForegroundColor DarkGray
    & (Join-Path $Root "gradlew.bat") @GradleArgs
    if ($LASTEXITCODE -ne 0) { Fail "Gradle failed (exit $LASTEXITCODE)." }
}
function Read-Text([string]$path) { [System.IO.File]::ReadAllText((Join-Path $Root $path), [System.Text.Encoding]::UTF8) }
function Get-Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 $path).Hash.ToLowerInvariant() }

# ---- 1. Checks
Step "Checking $Tag"
if ($Version -notmatch '^\d+(\.\d+)+(-[0-9A-Za-z.]+)?$') { Fail "Version must look like 0.1.3 or 0.1.4-test1." }
if ($Test -and $Version -notmatch '-') { Fail "A test version needs a suffix, e.g. $Version-test1." }
$gradleApp = Read-Text "app\build.gradle.kts"
$versionName = [regex]::Match($gradleApp, 'versionName = "([^"]+)"').Groups[1].Value
if ($Android -and $versionName -ne $Version) { Fail "app\build.gradle.kts has versionName $versionName, not $Version." }
if ($Desktop) {
    $fluxVersion = [regex]::Match((Read-Text "desktop\build.gradle.kts"), 'val fluxVersion = "([^"]+)"').Groups[1].Value
    if ($fluxVersion -ne $Version) { Fail "desktop\build.gradle.kts has fluxVersion $fluxVersion, not $Version." }
}
$changelog = Read-Text "CHANGELOG.md"
$section = [regex]::Match($changelog, "(?ms)^## $([regex]::Escape($Version))\b[^\n]*\n(.*?)(?=^## |\z)")
if (-not $section.Success) { Fail "CHANGELOG.md has no '## $Version' section." }
$notes = $section.Groups[1].Value.Trim()
if (-not $DryRun) {
    if (& $Git status --porcelain) { Fail "Commit your changes first (the working tree is not clean)." }
    if (& $Git tag --list $Tag) { Fail "Tag $Tag exists already." }
}

# ---- 2. Build
$Out = Join-Path $Root "build\release\$Tag"
if (-not $SkipBuild) {
    Step "Building"
    & (Join-Path $Root "gradlew.bat") --stop | Out-Null
    if ($Android) { Invoke-Gradle @("clean", "test", "lint", "assembleDebug") }
    if ($Desktop) { Invoke-Gradle @("-Pdesktop", ":desktop:test", ":desktop:createDistributable", ":desktop:packageMsi") }
}
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force $Out | Out-Null
$manifest = [ordered]@{ schema = 1; tag = $Tag; date = (Get-Date -Format "yyyy-MM-dd"); notes = $notes }

if ($Android) {
    $apkName = "Flux-$Version-debug.apk"
    $apk = Join-Path $Out $apkName
    Copy-Item "app\build\outputs\apk\debug\app-debug.apk" $apk
    $badging = & (Join-Path $BuildTools "aapt2.exe") dump badging $apk
    $code = [int][regex]::Match(($badging -join "`n"), "versionCode='(\d+)'").Groups[1].Value
    $name = [regex]::Match(($badging -join "`n"), "versionName='([^']+)'").Groups[1].Value
    $minSdk = [int][regex]::Match(($badging -join "`n"), "(?m)^(?:sdkVersion|minSdkVersion):'(\d+)'").Groups[1].Value
    if ($name -ne $Version) { Fail "The APK says versionName $name; rebuild without -SkipBuild." }
    # The signing certificate must stay the same forever (docs/PLAN-updates.md, D2).
    $certs = & (Join-Path $BuildTools "apksigner.bat") verify --print-certs $apk
    $cert = [regex]::Match(($certs -join "`n"), "SHA-256 digest: ([0-9a-f]+)").Groups[1].Value
    Write-Host "APK: versionCode $code, minSdk $minSdk, signing certificate SHA-256 $cert"
    $manifest.android = [ordered]@{
        versionCode = $code; versionName = $name; minSdk = $minSdk
        asset = $apkName; size = (Get-Item $apk).Length; sha256 = (Get-Sha256 $apk)
    }
}
if ($Desktop) {
    $msiName = "Flux-$Version-windows.msi"
    $msiSrc = Get-ChildItem "desktop\build\compose\binaries\main\msi\*.msi" | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $msiSrc) { Fail "No MSI was built." }
    Copy-Item $msiSrc.FullName (Join-Path $Out $msiName)
    # Portable folder: portable.txt next to Flux.exe tells the app it runs unpacked (updates replace the folder).
    $app = "desktop\build\compose\binaries\main\app\Flux"
    [System.IO.File]::WriteAllText((Join-Path $Root "$app\portable.txt"), "Flux portable $Version`r`n", $Utf8)
    $zipName = "Flux-$Version-windows-portable.zip"
    Compress-Archive -Path $app -DestinationPath (Join-Path $Out $zipName)
    Remove-Item (Join-Path $Root "$app\portable.txt")
    $manifest.desktop = [ordered]@{
        version = $Version
        msi = [ordered]@{ asset = $msiName; size = (Get-Item (Join-Path $Out $msiName)).Length; sha256 = (Get-Sha256 (Join-Path $Out $msiName)) }
        portable = [ordered]@{ asset = $zipName; size = (Get-Item (Join-Path $Out $zipName)).Length; sha256 = (Get-Sha256 (Join-Path $Out $zipName)) }
    }
}

# ---- 3. Manifest and release notes
Step "Writing flux-release.json"
$manifestPath = Join-Path $Out "flux-release.json"
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 6), $Utf8)
$files = @()
if ($Android) { $files += "- ``$apkName``: Android (installs over the previous version; annotations and settings kept)." }
if ($Desktop) {
    $files += "- ``$msiName``: Windows installer (upgrades the installed version in place)."
    $files += "- ``$zipName``: Windows, no install: unzip and run ``Flux\Flux.exe``."
}
$files += "- ``flux-release.json``: read by Flux's in-app updater."
$kind = if ($Test) { "Test version: shown in the app only with Settings > Updates > Show test versions.`n`n" } else { "" }
$body = "$kind$notes`n`n### Files`n$($files -join "`n")`n`nFlux is licensed under the PolyForm Noncommercial License 1.0.0: non-commercial use only, credit required (see LICENSE.md)."
$notesPath = Join-Path $Out "release-notes.md"
[System.IO.File]::WriteAllText($notesPath, $body, $Utf8)
Get-ChildItem $Out | ForEach-Object { Write-Host ("  {0,-40} {1,12:N0} bytes" -f $_.Name, $_.Length) }

if ($DryRun) { Step "Dry run: nothing tagged, pushed or released. Files in $Out"; exit 0 }

# ---- 4. Tag, push, release
Step "Tagging and pushing $Tag"
& $Git tag -a $Tag -m "Flux $Version"; if ($LASTEXITCODE -ne 0) { Fail "git tag failed." }
& $Git push origin HEAD; if ($LASTEXITCODE -ne 0) { Fail "git push failed." }
& $Git push origin $Tag; if ($LASTEXITCODE -ne 0) { Fail "git push of the tag failed." }
Step "Creating the GitHub release"
$assets = Get-ChildItem $Out -File | Where-Object { $_.Name -ne "release-notes.md" } | ForEach-Object { $_.FullName }
$ghArgs = @("release", "create", $Tag) + $assets + @("--title", "Flux $Version", "--notes-file", $notesPath)
if ($Test) { $ghArgs += "--prerelease" } else { $ghArgs += "--latest" }
& $Gh @ghArgs
if ($LASTEXITCODE -ne 0) { Fail "gh release create failed (the tag is pushed; rerun the gh step by hand)." }
Step "Released $Tag"
