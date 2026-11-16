package com.pcbsolderview.desktop

import com.pcbsolderview.Status
import com.pcbsolderview.data.AppDatabase
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.AppearancePrefs
import com.pcbsolderview.data.DetailField
import com.pcbsolderview.data.ThemeMode
import com.pcbsolderview.desktop.DesktopTestSupport.await
import com.pcbsolderview.desktop.DesktopTestSupport.awaitBoard
import com.pcbsolderview.platform.SupportedFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D3: annotations, recents and settings survive a restart (fresh process state, same data folder). */
class PersistenceTest {

    @Test
    fun `recent board reopens with its status paint and drawings`() {
        val dirs = DesktopTestSupport.tempDirs()
        val folder = DesktopTestSupport.sample("synthetic/simple")

        val vm = DesktopTestSupport.vm(dirs)
        // A folder opens every supported file in it (Pick & Place + BOM here).
        DesktopTestSupport.onVm { vm.open(listOf(folder)) }
        val opened = vm.awaitBoard()
        val parts = opened.scene!!.board.components.map { it.refDes }
        DesktopTestSupport.onVm { vm.setMark(parts[0], Status.OK.argb) }
        DesktopTestSupport.onVm { vm.setMark(parts[1], Status.PROBLEM.argb) }
        DesktopTestSupport.onVm { vm.addStroke(listOf(1000.0 to 1000.0, 5000.0 to 4000.0, 9000.0 to 2000.0), 300f) }
        vm.await { it.marks.size == 2 && it.strokes.size == 1 }
        vm.await { it.recents.size == 1 }
        vm.close()

        // "Restart": drop the in-memory store so everything is read back from disk.
        AppDatabase.forget(dirs)
        val vm2 = DesktopTestSupport.vm(dirs)
        val recents = vm2.await { it.recents.isNotEmpty() }.recents
        assertEquals(1, recents.size)
        val item = recents.single()
        assertEquals(1, item.ok)
        assertEquals(1, item.problems)
        assertEquals(1, item.drawings)
        assertTrue(item.thumbnail?.let { File(it).length() > 100 } == true, "thumbnail written")

        DesktopTestSupport.onVm { vm2.openRecent(item.board) }
        vm2.awaitBoard()
        val s = vm2.await { it.marks.size == 2 && it.strokes.size == 1 }
        assertEquals(Status.OK.argb, s.marks[parts[0]]?.argb)
        assertEquals(Status.PROBLEM.argb, s.marks[parts[1]]?.argb)
        assertEquals(3, s.strokes.single().points.size)

        // Undo after reopening works on the stored data.
        DesktopTestSupport.onVm { vm2.setMark(parts[2], Status.CHECK.argb) }
        vm2.await { it.marks.size == 3 }
        DesktopTestSupport.onVm { vm2.undo() }
        vm2.await { it.marks.size == 2 }
        vm2.close()
    }

    @Test
    fun `rename and remove recent`() {
        val dirs = DesktopTestSupport.tempDirs()
        val vm = DesktopTestSupport.vm(dirs)
        DesktopTestSupport.onVm { vm.open(listOf(DesktopTestSupport.sample("synthetic/double-sided"))) }
        vm.awaitBoard()
        val r = vm.await { it.recents.size == 1 }.recents.single().board
        DesktopTestSupport.onVm { vm.renameRecent(r, "Bench board") }
        vm.await { it.recents.singleOrNull()?.board?.customName == "Bench board" }
        AppDatabase.forget(dirs)
        val vm2 = DesktopTestSupport.vm(dirs)
        val again = vm2.await { it.recents.isNotEmpty() }.recents.single().board
        assertEquals("Bench board", again.displayName)
        DesktopTestSupport.onVm { vm2.removeRecent(again) }
        vm2.await { it.recents.isEmpty() }
        vm.close(); vm2.close()
    }

    @Test
    fun `settings round trip`() {
        val dirs = DesktopTestSupport.tempDirs()
        val a = Appearance(mode = ThemeMode.LIGHT, accent = Appearance.ACCENTS[2].second, showFps = true, hiddenDetails = setOf(DetailField.HEIGHT), followSelection = false)
        runBlocking { AppearancePrefs(dirs).save(a) }
        assertEquals(a, AppearancePrefs.snapshot(dirs))
        // Missing or broken file: defaults, never a crash.
        dirs.settings.writeText("{ not json")
        assertEquals(Appearance(), AppearancePrefs.snapshot(dirs))
    }

    @Test
    fun `folder expansion keeps supported files only`() {
        val rpi = DesktopTestSupport.sample("real/RPI_Module")
        val list = SupportedFiles.expand(listOf(DesktopTestSupport.sample("synthetic/ipc-mini")))
        assertEquals(listOf("mini.SchDoc", "mini.xml"), list.map { it.name })
        if (rpi.isDirectory) {
            val names = SupportedFiles.expand(listOf(rpi)).map { it.name }
            assertTrue("RP2040-Module.SchDoc" in names && "RP2040-Module.cvg" in names && "RPI_Module.pdf" in names)
            assertTrue(names.none { it.endsWith(".PrjPcb") || it.endsWith(".OutJob") })
        }
    }
}
