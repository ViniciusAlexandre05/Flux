package com.pcbsolderview.update

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.desktop.DesktopTestSupport
import com.pcbsolderview.ui.PcbTheme
import com.pcbsolderview.ui.UpdatesSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WindowsUpdateTest {
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    private fun temp(): File = Files.createTempDirectory("flux upd'").toFile() // space and quote on purpose

    /** A process id that has already exited, so the helper does not wait. */
    private fun deadPid(): Long = ProcessBuilder("cmd", "/c", "exit").start().also { it.waitFor() }.pid()

    private fun runPs(script: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass") + args + listOf("-File", script.path))
            .redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        return out
    }

    /** PowerShell's own parser finds no syntax errors in the script. */
    private fun assertParses(text: String) {
        val dir = temp()
        val s = File(dir, "s.ps1").apply { writeText("\uFEFF" + text, Charsets.UTF_8) }
        val check = File(dir, "check.ps1").apply {
            writeText(
                "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseFile(${UpdateScripts.q(s.path)}, [ref]\$null, [ref]\$e); " +
                    "if (\$e.Count) { \$e | ForEach-Object { Write-Output \$_.Message } } else { Write-Output 'PARSED' }",
            )
        }
        val out = runPs(check)
        assertTrue(out.trim() == "PARSED", "PowerShell syntax errors:\n$out\n---\n$text")
    }

    @Test
    fun msiScriptParsesAndUninstallsOnlyWhenAsked() {
        assumeTrue(windows)
        val d = temp()
        val args = { u: Boolean -> UpdateScripts.msi(File(d, "Flux-0.2.msi"), File(d, "Flux"), File(d, "Flux\\Flux.exe"), "0.1.3", "0.2", u, File(d, "update.log"), 1) }
        assertParses(args(true))
        assertParses(args(false))
        assertTrue("/x" in args(true))
        assertFalse("/x" in args(false))
        assertTrue("INSTALLDIR=" in args(false))
    }

    @Test
    fun portableSwapReplacesTheFolderAndKeepsTheOldOne() {
        assumeTrue(windows)
        val parent = temp()
        val dir = File(parent, "Flux").apply { mkdirs() }
        File(dir, "Flux.exe").writeText("old")
        File(dir, "old.txt").writeText("old")
        val stage = File(parent, UpdateController.STAGE_PREFIX + "0.2")
        val staged = File(stage, "Flux").apply { mkdirs() }
        File(staged, "Flux.exe").writeText("new")
        File(staged, "portable.txt").writeText("Flux portable 0.2")
        val log = File(parent, "update.log")
        val script = UpdateScripts.portable(stage, staged, dir, File(dir, "Flux.exe"), "0.1.3", "0.2", log, deadPid(), start = false)
        assertParses(script)
        val ps1 = File(parent, "update.ps1").apply { writeText("\uFEFF" + script, Charsets.UTF_8) }
        runPs(ps1)

        assertEquals("new", File(dir, "Flux.exe").readText())
        assertFalse(File(dir, "old.txt").exists())
        assertEquals("old", File(parent, "Flux.old-0.1.3/old.txt").readText())
        assertFalse(stage.exists(), "staging folder removed")
        assertTrue(log.readText().contains("OK"), log.readText())
    }

    @Test
    fun portableSwapPutsTheOldFolderBackWhenTheNewOneIsMissing() {
        assumeTrue(windows)
        val parent = temp()
        val dir = File(parent, "Flux").apply { mkdirs() }
        File(dir, "Flux.exe").writeText("old")
        val stage = File(parent, UpdateController.STAGE_PREFIX + "0.2")
        val log = File(parent, "update.log")
        val script = UpdateScripts.portable(stage, File(stage, "Flux"), dir, File(dir, "Flux.exe"), "0.1.3", "0.2", log, deadPid(), start = false)
        runPs(File(parent, "update.ps1").apply { writeText("\uFEFF" + script, Charsets.UTF_8) })
        assertEquals("old", File(dir, "Flux.exe").readText())
        assertTrue(log.readText().contains("FAILED"), log.readText())
    }

    private fun zip(file: File, vararg entries: Pair<String, String>) = ZipOutputStream(file.outputStream()).use { z ->
        entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
    }

    @Test
    fun unzipFindsTheFluxFolderAndRefusesUnsafePaths() {
        val d = temp()
        val z = File(d, "p.zip").also { zip(it, "Flux/Flux.exe" to "x", "Flux/app/a.jar" to "j", "Flux/portable.txt" to "p") }
        val root = unzipPortable(z, File(d, "stage"), "Flux.exe")
        assertEquals("Flux", root.name)
        assertTrue(File(root, "app/a.jar").exists())

        val bad = File(d, "bad.zip").also { zip(it, "../evil.txt" to "x", "Flux/Flux.exe" to "x") }
        assertThrows<UpdateException> { unzipPortable(bad, File(d, "stage2"), "Flux.exe") }
        assertFalse(File(d, "evil.txt").exists())
    }

    private fun manifest(desktop: String, tag: String = "v$desktop", pre: Boolean = false) = ReleaseManifest(
        1, tag, "2026-11-26", "- **New**: something.\n- Another line.", null,
        DesktopRelease(desktop, Asset("Flux-$desktop-windows.msi", 101_000_000, "a".repeat(64)), Asset("Flux-$desktop-windows-portable.zip", 100_000_000, "b".repeat(64))),
        pre,
    )

    private class FakeSource(private val releases: List<ReleaseManifest>) : ReleaseSource {
        override fun latest() = releases.first { !it.prerelease }
        override fun all() = releases.map { ReleaseEntry(it.tag, it.tag, it.prerelease, null, it.notes, "x/${it.tag}") }
        override fun manifest(entry: ReleaseEntry) = releases.first { it.tag == entry.tag }
        override fun assetUrl(tag: String, asset: Asset) = "http://localhost/$tag/${asset.name}"
    }

    private fun controller(dirs: com.pcbsolderview.platform.AppDirs, installed: String, mode: RunMode, source: ReleaseSource = FakeSource(listOf(manifest("0.1.3")))) =
        DesktopTestSupport.onVm {
            UpdateController(dirs, mode, installed, source, Downloader(UrlConnectionHttp()), File(dirs.root, "updates"), CoroutineScope(SupervisorJob() + DesktopTestSupport.main))
        }

    @Test
    fun afterAnUpdateShowsWhatsNewOrWhyItFailed() {
        val dirs = DesktopTestSupport.tempDirs()
        File(dirs.root, "update.json").writeText("""{"last_version":"0.1.3","pending_to":"0.2","pending_notes":"- **Faster**"}""")
        val ok = controller(dirs, "0.2", RunMode(RunKind.MSI, File(dirs.root, "Flux/Flux.exe")))
        assertEquals("0.2" to "- Faster", ok.ui.value.updatedTo)
        // Shown once only.
        assertEquals(null, controller(dirs, "0.2", RunMode(RunKind.MSI, null)).ui.value.updatedTo)

        File(dirs.root, "update.json").writeText("""{"last_version":"0.1.3","pending_to":"0.2"}""")
        File(dirs.root, "updates").mkdirs()
        File(dirs.root, "updates/update.log").writeText("2026-11-26 10:00:00 FAILED: the installer was cancelled.\n")
        val failed = controller(dirs, "0.1.3", RunMode(RunKind.MSI, null))
        assertNotNull(failed.ui.value.updateFailed)
        assertTrue("cancelled" in failed.ui.value.updateFailed!!, failed.ui.value.updateFailed)
    }

    @Test
    fun checkOffersTheNewerDesktopVersionAndDevRunsDontInstall() {
        val dirs = DesktopTestSupport.tempDirs()
        val c = controller(dirs, "0.1.2", RunMode(RunKind.DEV, null), FakeSource(listOf(manifest("0.1.4-test1", pre = true), manifest("0.1.3"), manifest("0.1.2"))))
        DesktopTestSupport.onVm { c.check(includeTest = false) }
        val s = runBlocking { withTimeout(10_000) { c.ui.first { !it.checking && (it.available != null || it.error != null || it.status != null) } } }
        assertEquals("0.1.3", s.available?.desktop?.version)
        DesktopTestSupport.onVm { c.check(includeTest = true) }
        val t = runBlocking { withTimeout(10_000) { c.ui.first { !it.checking && it.available?.desktop?.version == "0.1.4-test1" } } }
        assertTrue(t.available!!.prerelease)
        DesktopTestSupport.onVm { c.install(t.available!!) }
        assertTrue(c.ui.value.error!!.contains("off"), c.ui.value.error)
    }

    /** desktop/build/renders/ui-updates.png: the Updates section with a version available. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderUpdatesSection() {
        val dirs = DesktopTestSupport.tempDirs()
        val c = controller(dirs, "0.1.3", RunMode(RunKind.PORTABLE, File("C:\\Tools\\Flux\\Flux.exe")), FakeSource(listOf(manifest("0.1.4"), manifest("0.1.3"))))
        DesktopTestSupport.onVm { c.check(false) }
        runBlocking { withTimeout(10_000) { c.ui.first { it.available != null } } }
        val scene = ImageComposeScene(560, 560, Density(1f)) {
            PcbTheme(Appearance()) {
                Surface { Column(Modifier.width(520.dp).padding(16.dp)) { UpdatesSection(showTest = false, onShowTest = {}, vm = c) } }
            }
        }
        val img = try { var i = scene.render(0); repeat(5) { k -> i = scene.render((k + 1) * 50_000_000L) }; i } finally { scene.close() }
        File(DesktopTestSupport.renders, "ui-updates.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
