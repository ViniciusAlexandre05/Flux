package com.pcbsolderview.update

import java.io.File
import java.util.zip.ZipInputStream

// Desktop only (docs/PLAN-updates.md, U3): how this copy of Flux was started, and the PowerShell
// helpers that replace it after Flux has closed (a running program can't overwrite itself).

/** How Flux runs: decides which file an update needs and how it is installed. */
enum class RunKind { DEV, MSI, PORTABLE }

data class RunMode(val kind: RunKind, val exe: File?) {
    /** The folder holding Flux.exe (the MSI's install folder or the unzipped portable folder). */
    val dir: File? get() = exe?.absoluteFile?.parentFile
    val canInstall: Boolean get() = kind != RunKind.DEV && exe != null

    /** The file of [m] this copy installs: the MSI or the portable zip. */
    fun assetOf(m: ReleaseManifest): Asset? = when (kind) {
        RunKind.PORTABLE -> m.desktop?.portable
        else -> m.desktop?.msi
    }

    companion object {
        /** Marker next to Flux.exe in the portable zip (scripts/release.ps1). */
        const val PORTABLE_MARKER = "portable.txt"

        fun detect(): RunMode {
            // The jpackage launcher hosts the JVM inside Flux.exe; :desktop:run uses java.exe.
            val exe = System.getProperty("jpackage.app-path")?.let(::File)
                ?: ProcessHandle.current().info().command().orElse(null)?.let(::File)
                    ?.takeIf { it.name.lowercase().let { n -> n.endsWith(".exe") && n != "java.exe" && n != "javaw.exe" } }
                ?: return RunMode(RunKind.DEV, null)
            val portable = File(exe.absoluteFile.parentFile, PORTABLE_MARKER).exists()
            return RunMode(if (portable) RunKind.PORTABLE else RunKind.MSI, exe)
        }
    }
}

/** Builds the PowerShell helper scripts. Pure text, so tests can read and run them. */
object UpdateScripts {
    /** A PowerShell single-quoted string literal. */
    fun q(s: String) = "'" + s.replace("'", "''") + "'"

    private fun header(log: File, fluxPid: Long) = """
        |${'$'}ErrorActionPreference = 'Continue'
        |${'$'}log = ${q(log.path)}
        |function Write-Log([string]${'$'}m) { Add-Content -LiteralPath ${'$'}log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + ${'$'}m) -Encoding UTF8 }
        |# Wait until Flux has closed (it starts this script, then exits).
        |Wait-Process -Id $fluxPid -Timeout 120 -ErrorAction SilentlyContinue
        |Start-Sleep -Milliseconds 500
        |""".trimMargin()

    private fun startFlux(exe: File, start: Boolean) =
        if (start) "if (Test-Path -LiteralPath ${q(exe.path)}) { Start-Process -FilePath ${q(exe.path)} }\n" else ""

    /**
     * MSI: optionally uninstall first (going back to an older version, or between a test version
     * and its release, which share the MSI's numeric version), install with msiexec, start Flux.
     * Per-user MSI, so no administrator prompt. Data in %APPDATA%\Flux is not touched.
     */
    fun msi(msi: File, installDir: File, exe: File, from: String, to: String, uninstallFirst: Boolean, log: File, fluxPid: Long, start: Boolean = true): String = buildString {
        append(header(log, fluxPid))
        append("Write-Log ${q("Updating Flux $from -> $to (MSI)")}\n")
        if (uninstallFirst) append(
            """
            |${'$'}roots = @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall')
            |${'$'}codes = @(Get-ChildItem -LiteralPath ${'$'}roots -ErrorAction SilentlyContinue | Where-Object {
            |    ${'$'}p = Get-ItemProperty -LiteralPath ${'$'}_.PSPath -ErrorAction SilentlyContinue
            |    ${'$'}_.PSChildName -like '{*}' -and ${'$'}p.DisplayName -eq 'Flux' -and ${'$'}p.Publisher -eq ${q(VENDOR)}
            |} | ForEach-Object { ${'$'}_.PSChildName })
            |foreach (${'$'}c in ${'$'}codes) {
            |    ${'$'}u = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', ${'$'}c, '/passive', '/norestart') -Wait -PassThru
            |    Write-Log ('uninstall ' + ${'$'}c + ' exit ' + ${'$'}u.ExitCode)
            |}
            |
            """.trimMargin(),
        )
        val msiLog = File(log.parentFile, "msiexec.log")
        append(
            """
            |${'$'}a = @('/i', ${q("\"${msi.path}\"")}, '/passive', '/norestart', ${q("INSTALLDIR=\"${installDir.path.trimEnd('\\')}\"")}, '/l*', ${q("\"${msiLog.path}\"")})
            |${'$'}p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${'$'}a -Wait -PassThru
            |if (${'$'}p.ExitCode -eq 0 -or ${'$'}p.ExitCode -eq 3010) { Write-Log 'OK' }
            |elseif (${'$'}p.ExitCode -eq 1602) { Write-Log 'FAILED: the installer was cancelled.' }
            |elseif (${'$'}p.ExitCode -eq 1618) { Write-Log 'FAILED: another installation was running. Try again later.' }
            |else { Write-Log ('FAILED: the Windows installer stopped with code ' + ${'$'}p.ExitCode + ' (details in msiexec.log).') }
            |
            """.trimMargin(),
        )
        append(startFlux(exe, start))
    }

    /**
     * Portable: [staged] is the unzipped new folder inside [stageRoot] (a folder Flux created next
     * to [installDir] and the only thing removed afterwards). Rename the current folder
     * to "<name>.old-<from>", move the new one into its place, start Flux; on failure put the old
     * one back. Works the same for older and newer versions. Flux deletes the .old folder on its
     * next start.
     */
    fun portable(stageRoot: File, staged: File, installDir: File, exe: File, from: String, to: String, log: File, fluxPid: Long, start: Boolean = true): String = buildString {
        val old = File(installDir.parentFile, "${installDir.name}.old-${safe(from)}")
        append(header(log, fluxPid))
        append("Write-Log ${q("Updating Flux $from -> $to (portable)")}\n")
        append(
            """
            |${'$'}dir = ${q(installDir.path)}; ${'$'}old = ${q(old.path)}; ${'$'}new = ${q(staged.path)}; ${'$'}stage = ${q(stageRoot.path)}
            |function Invoke-Retry([scriptblock]${'$'}b) {
            |    for (${'$'}i = 0; ${'$'}i -lt 40; ${'$'}i++) { try { & ${'$'}b; return ${'$'}true } catch { Start-Sleep -Milliseconds 500 } }
            |    return ${'$'}false
            |}
            |if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue }
            |if (-not (Invoke-Retry { Rename-Item -LiteralPath ${'$'}dir -NewName (Split-Path ${'$'}old -Leaf) -ErrorAction Stop })) {
            |    Write-Log 'FAILED: the Flux folder is still in use (close every Flux window and try again).'
            |} elseif (-not (Invoke-Retry { Move-Item -LiteralPath ${'$'}new -Destination ${'$'}dir -ErrorAction Stop })) {
            |    Write-Log 'FAILED: could not move the new version into place; the old one was put back.'
            |    Rename-Item -LiteralPath ${'$'}old -NewName (Split-Path ${'$'}dir -Leaf) -ErrorAction SilentlyContinue
            |} else {
            |    Write-Log 'OK'
            |}
            |if (Test-Path -LiteralPath ${'$'}stage) { Remove-Item -LiteralPath ${'$'}stage -Recurse -Force -ErrorAction SilentlyContinue }
            |
            """.trimMargin(),
        )
        append(startFlux(exe, start))
    }

    /** Version text safe for a folder name. */
    fun safe(v: String) = v.replace(Regex("[^0-9A-Za-z.-]"), "_")

    /** Matches `vendor` in desktop/build.gradle.kts (the MSI's Publisher). */
    const val VENDOR = "Vinicius Alexandre Gomes"
}

/** Unzips [zip] into [dest] (refusing paths that leave it) and returns the folder that holds [exeName]. */
fun unzipPortable(zip: File, dest: File, exeName: String): File {
    dest.deleteRecursively()
    dest.mkdirs()
    val root = dest.canonicalFile
    ZipInputStream(zip.inputStream().buffered()).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            val out = File(root, e.name.replace('\\', '/')).canonicalFile
            if (!out.path.startsWith(root.path + File.separator)) throw UpdateException(Failure.BAD_MANIFEST, "unsafe path in zip: ${e.name}")
            if (e.isDirectory) out.mkdirs() else { out.parentFile.mkdirs(); out.outputStream().use { z.copyTo(it) } }
        }
    }
    return root.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.equals(exeName, ignoreCase = true) }?.parentFile
        ?: throw UpdateException(Failure.BAD_MANIFEST, "$exeName is not in the zip")
}
