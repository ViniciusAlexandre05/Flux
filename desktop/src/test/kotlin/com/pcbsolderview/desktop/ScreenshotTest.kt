package com.pcbsolderview.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import com.pcbsolderview.BoardViewModel
import com.pcbsolderview.ViewLayout
import com.pcbsolderview.desktop.DesktopTestSupport.await
import com.pcbsolderview.desktop.DesktopTestSupport.awaitBoard
import com.pcbsolderview.ui.BoardScreen
import com.pcbsolderview.ui.PcbTheme
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Off-screen renders of the real desktop UI (the same composables as the window) into
 * desktop/build/renders/ui-*.png. Read them as images to check a change; compare the board ones
 * with the parser previews in core/parser/build/renders/.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {

    private fun render(name: String, vm: BoardViewModel, width: Int = 1600, height: Int = 1000, frames: Int = 40, mouseAt: Offset? = null): Image {
        val scene = ImageComposeScene(width, height, Density(1f)) { App(vm) }
        var image: Image? = null
        try {
            var t = 0L
            repeat(frames) { i ->
                if (mouseAt != null && i == frames / 2) scene.sendPointerEvent(PointerEventType.Move, mouseAt, type = PointerType.Mouse)
                image = scene.render(t)
                t += 50_000_000L
                Thread.sleep(25)
            }
        } finally {
            scene.close()
        }
        val img = image!!
        val out = File(DesktopTestSupport.renders, "ui-$name.png")
        out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return img
    }

    @Composable
    private fun App(vm: BoardViewModel) {
        val state by vm.state.collectAsState()
        val appearance by vm.appearance.collectAsState()
        PcbTheme(appearance) { BoardScreen(state = state, vm = vm, openFiles = {}) }
    }

    /** The picture is not a single flat colour (something was drawn). */
    private fun assertDrawn(img: Image) {
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
        val colors = HashSet<Int>()
        for (y in 0 until img.height step 23) for (x in 0 until img.width step 23) colors += bmp.getColor(x, y)
        assertTrue(colors.size > 20, "only ${colors.size} colours: blank render?")
    }

    @Test
    fun startScreen() {
        val dirs = DesktopTestSupport.tempDirs()
        val vm = DesktopTestSupport.vm(dirs)
        DesktopTestSupport.onVm { vm.open(listOf(DesktopTestSupport.sample("synthetic/double-sided"))) }
        vm.awaitBoard()
        DesktopTestSupport.onVm { vm.closeBoard() }
        vm.await { it.scene == null && it.recents.isNotEmpty() }
        assertDrawn(render("start", vm))
        vm.close()
    }

    @Test
    fun demoBoardAndSchematic() {
        val vm = DesktopTestSupport.vm()
        DesktopTestSupport.onVm { vm.openDemo("ipc-mini") }
        val s = vm.awaitBoard()
        assertTrue(s.viewLayout == ViewLayout.SPLIT, "a board with a schematic opens split")
        val part = s.scene!!.board.components.first().refDes
        DesktopTestSupport.onVm { vm.selectRef(part) }
        vm.await { it.selectedRef == part }
        assertDrawn(render("demo-split", vm))
        vm.close()
    }

    @Test
    fun realBoardIepe() {
        val f = DesktopTestSupport.sample("real/double-sided/IEPE_CONDITIONER_V1.cvg")
        assumeTrue(f.exists(), "samples/real is local only")
        val vm = DesktopTestSupport.vm()
        DesktopTestSupport.onVm { vm.open(listOf(f)) }
        vm.awaitBoard()
        assertDrawn(render("IEPE_CONDITIONER_V1", vm))
        // Mouse over U201 (centre of the board's lower half in this layout): hover outline + label.
        assertDrawn(render("IEPE-hover", vm, mouseAt = Offset(868f, 772f)))
        vm.close()
    }

    /** Clicking a part opens the side panel; the board must stay where it was on screen. */
    @Test
    fun sidePanelDoesNotMoveBoard() {
        val f = DesktopTestSupport.sample("real/double-sided/IEPE_CONDITIONER_V1.cvg")
        assumeTrue(f.exists(), "samples/real is local only")
        val vm = DesktopTestSupport.vm()
        DesktopTestSupport.onVm { vm.open(listOf(f)) }
        vm.awaitBoard()
        val scene = ImageComposeScene(1600, 1000, Density(1f)) { App(vm) }
        try {
            var t = 0L
            fun frames(n: Int): Image { var img: Image? = null; repeat(n) { img = scene.render(t); t += 50_000_000L; Thread.sleep(25) }; return img!! }
            val before = org.jetbrains.skia.Bitmap.makeFromImage(frames(30))
            val u201 = Offset(868f, 772f)
            scene.sendPointerEvent(PointerEventType.Press, u201, type = PointerType.Mouse, buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, u201, type = PointerType.Mouse, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            vm.await { it.selected?.refDes == "U201" }
            val afterImg = frames(30)
            File(DesktopTestSupport.renders, "ui-IEPE-selected.png").writeBytes(afterImg.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            val after = org.jetbrains.skia.Bitmap.makeFromImage(afterImg)
            // Top-left quarter of the board, far from U201 and from the panel: identical pixels.
            var diff = 0; var total = 0
            for (y in 100 until 500 step 7) for (x in 480 until 720 step 7) { total++; if (before.getColor(x, y) != after.getColor(x, y)) diff++ }
            // A shift moves most pixels; a few differ from anti-aliasing when the canvas is smaller.
            assertTrue(diff * 100 < total, "$diff pixels moved when the side panel opened")
        } finally {
            scene.close()
            vm.close()
        }
    }

    /** Flip changes the side only; Mirror mirrors either side and shows a label. */
    @Test
    fun bottomViewMirrorToggle() {
        val vm = DesktopTestSupport.vm()
        DesktopTestSupport.onVm { vm.openDemo("double-sided") }
        vm.awaitBoard()
        val scene = ImageComposeScene(1600, 1000, Density(1f)) { App(vm) }
        try {
            var t = 0L
            fun frames(n: Int): Image { var img: Image? = null; repeat(n) { img = scene.render(t); t += 50_000_000L; Thread.sleep(25) }; return img!! }
            fun save(img: Image, name: String) = File(DesktopTestSupport.renders, "ui-$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            val top = frames(20)
            // Flip only changes the side: the bottom is seen through the board, not mirrored.
            DesktopTestSupport.onVm { com.pcbsolderview.ui.DesktopCommands.flip!!.invoke() }
            val through = frames(20).also { save(it, "bottom-through") }
            // Mirror works on either side and shows the "MIRRORED" label.
            DesktopTestSupport.onVm { com.pcbsolderview.ui.DesktopCommands.mirror!!.invoke() }
            vm.await { it.mirrored }
            val mirrored = frames(20).also { save(it, "bottom-mirrored") }
            DesktopTestSupport.onVm { com.pcbsolderview.ui.DesktopCommands.flip!!.invoke() }
            val topMirrored = frames(20).also { save(it, "top-mirrored") }
            DesktopTestSupport.onVm { com.pcbsolderview.ui.DesktopCommands.mirror!!.invoke() }
            vm.await { !it.mirrored }
            val topAgain = frames(20)
            fun px(i: Image) = org.jetbrains.skia.Bitmap.makeFromImage(i).let { b -> (0 until 40).map { k -> b.getColor(200 + k * 30, 300 + k * 15) } }
            assertTrue(px(mirrored) != px(through), "mirror toggle changes the view")
            assertTrue(px(topMirrored) != px(top), "the top side can be mirrored too")
            assertTrue(px(top) == px(topAgain), "unmirrored top is back to the start")
        } finally {
            scene.close()
            vm.close()
        }
    }

    @Test
    fun realRp2040Split() {
        val dir = DesktopTestSupport.sample("real/RPI_Module")
        assumeTrue(dir.isDirectory, "samples/real is local only")
        val vm = DesktopTestSupport.vm()
        DesktopTestSupport.onVm { vm.open(listOf(File(dir, "RP2040-Module.cvg"), File(dir, "RP2040-Module.SchDoc"))) }
        val s = vm.awaitBoard()
        assertTrue(s.schematic != null && s.viewLayout == ViewLayout.SPLIT)
        val part = s.scene!!.board.components.firstOrNull { it.refDes.startsWith("C") }?.refDes ?: s.scene!!.board.components.first().refDes
        DesktopTestSupport.onVm { vm.selectRef(part) }
        vm.await { it.selectedRef == part }
        assertDrawn(render("RP2040-split", vm))
        vm.close()
    }

    @Test
    fun realRp2040Pdf() {
        val dir = DesktopTestSupport.sample("real/RPI_Module")
        assumeTrue(dir.isDirectory, "samples/real is local only")
        val vm = DesktopTestSupport.vm()
        DesktopTestSupport.onVm { vm.open(listOf(File(dir, "RP2040-Module.cvg"), File(dir, "RPI_Module.pdf"))) }
        val s = vm.awaitBoard()
        assertTrue(s.schematicPdf != null)
        DesktopTestSupport.onVm { vm.setViewLayout(ViewLayout.SCHEMATIC) }
        vm.await { it.viewLayout == ViewLayout.SCHEMATIC }
        assertDrawn(render("RP2040-pdf", vm, frames = 80))
        vm.close()
    }
}
