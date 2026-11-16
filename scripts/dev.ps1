# Flux developer commands. Run through dev.cmd in the repo root:
#   .\dev android | .\dev pc     choose which version you are working on (remembered)
#   .\dev                        show the current version and repo state
#   .\dev build | run | test     build / start / test the chosen version
# The choice is stored in .flux-mode (local, not committed). Android is the main version; the
# PC version (desktop/ module) is only built when it is chosen here or asked for with -Pdesktop.
param([string]$Command = "status")

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$ModeFile = Join-Path $Root ".flux-mode"
$Jdk = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
$Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$Git = "C:\Program Files\Git\cmd\git.exe"
$Apk = "app\build\outputs\apk\debug\app-debug.apk"
$HasDesktop = Test-Path (Join-Path $Root "desktop\build.gradle.kts")

if (Test-Path $Jdk) { $env:JAVA_HOME = $Jdk }

function Get-Mode {
    if (Test-Path $ModeFile) { (Get-Content $ModeFile -Raw).Trim() } else { "android" }
}

function Invoke-Gradle([string[]]$GradleArgs) {
    Write-Host "> gradlew $($GradleArgs -join ' ')" -ForegroundColor DarkGray
    & (Join-Path $Root "gradlew.bat") @GradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)." }
}

function Assert-Desktop {
    if (-not $HasDesktop) {
        Write-Host "The PC version does not exist yet (no desktop/ module)." -ForegroundColor Yellow
        Write-Host "Ask the agent to start docs/PLAN-desktop.md, phase D1."
        exit 1
    }
}

function Show-Status {
    $mode = Get-Mode
    $label = if ($mode -eq "pc") { "PC (desktop)" } else { "Android" }
    Write-Host "Working on: $label" -ForegroundColor Cyan
    if (Test-Path $Git) {
        $branch = & $Git rev-parse --abbrev-ref HEAD
        $last = & $Git log -1 --format="%h %s"
        $sync = & $Git status -sb | Select-Object -First 1
        Write-Host "Branch:     $branch   ($sync)"
        Write-Host "Last:       $last"
    }
    $version = Select-String -Path "app\build.gradle.kts" -Pattern 'versionName = "(.*)"' | ForEach-Object { $_.Matches[0].Groups[1].Value }
    Write-Host "Android:    version $version"
    if ($HasDesktop) {
        $synced = if (Test-Path "desktop\SYNC.md") { (Get-Content "desktop\SYNC.md" | Select-Object -First 1) } else { "no desktop/SYNC.md" }
        Write-Host "PC:         $synced"
    } else {
        Write-Host "PC:         not started (docs/PLAN-desktop.md)"
    }
    Write-Host ""
    Write-Host "Commands: .\dev android | pc | build | run | test | help"
}

$mode = Get-Mode
switch ($Command.ToLower()) {
    { $_ -in "android", "pc" } {
        Set-Content -Path $ModeFile -Value $_ -NoNewline
        Write-Host ("Switched to " + $(if ($_ -eq "pc") { "the PC version." } else { "the Android version." })) -ForegroundColor Green
        if ($_ -eq "pc" -and -not $HasDesktop) { Write-Host "Note: the PC version does not exist yet (docs/PLAN-desktop.md)." -ForegroundColor Yellow }
    }
    "status" { Show-Status }
    "build" {
        if ($mode -eq "pc") {
            Assert-Desktop
            Invoke-Gradle @("-Pdesktop", ":desktop:test", ":desktop:createDistributable")
            Write-Host "PC app folder: desktop\build\compose\binaries\main\app\" -ForegroundColor Green
        } else {
            # The gate from CLAUDE.md: tests, lint and the debug APK.
            Invoke-Gradle @("test", "lint", "assembleDebug")
            Write-Host "APK: $Apk" -ForegroundColor Green
        }
    }
    "test" {
        if ($mode -eq "pc") { Assert-Desktop; Invoke-Gradle @("-Pdesktop", ":desktop:test") }
        else { Invoke-Gradle @("test") }
    }
    "run" {
        if ($mode -eq "pc") {
            Assert-Desktop
            Invoke-Gradle @("-Pdesktop", ":desktop:run")
        } else {
            Invoke-Gradle @("assembleDebug")
            $adb = Join-Path $Sdk "platform-tools\adb.exe"
            $devices = if (Test-Path $adb) { & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } } else { @() }
            if ($devices) {
                & $adb install -r $Apk
                & $adb shell monkey -p com.pcbsolderview -c android.intent.category.LAUNCHER 1 | Out-Null
                Write-Host "Installed and started on the tablet." -ForegroundColor Green
            } else {
                Write-Host "No device connected over USB (or USB debugging is off)." -ForegroundColor Yellow
                Write-Host "APK to copy to the tablet: $Apk"
            }
        }
    }
    default {
        Write-Host "Usage: .\dev [android | pc | status | build | run | test]"
        Write-Host "  android / pc   choose the version you are working on (remembered)"
        Write-Host "  build          Android: tests + lint + APK.  PC: tests + app folder with Flux.exe"
        Write-Host "  run            Android: install on a USB-connected tablet.  PC: start the app"
        Write-Host "  test           run the tests of the chosen version"
    }
}
