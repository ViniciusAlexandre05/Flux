package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.SchematicConnectivity
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.ipc2581.BoardPng
import com.pcbsolderview.parser.testXml
import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The user's RP2040 board (private, local only): the native .PcbDoc must give the same parts, pads
 * and nets as Altium's own IPC-2581 export of it. Differences are written to
 * core/parser/build/reports/pcbdoc-vs-ipc-RP2040.txt before asserting.
 */
class Rp2040PcbDocTest {
    private val dir = "real/RPI_Module"

    private fun boards(): Pair<Board, Board> {
        assumeTrue(File(Samples.dir, "$dir/RP2040-Module.PcbDoc").exists(), "Real RP2040 sample not present; skipping")
        val importer = BoardImporter(testXml)
        val native = importer.import(listOf(Samples.input("$dir/RP2040-Module.PcbDoc"))).board
        val ipc = importer.import(listOf(Samples.input("$dir/RP2040-Module.cvg"))).board
        return native to ipc
    }

    private fun near(a: Long, b: Long, tol: Long = 1) = abs(a - b) <= tol

    /** Same rectangle, allowing width/height swapped with a 90° rotation difference. */
    private fun sameSize(a: Pad, b: Pad): Boolean {
        val dr = ((a.rotationDeg - b.rotationDeg) % 180 + 180) % 180
        return (near(a.width, b.width) && near(a.height, b.height) && (dr < 0.01 || dr > 179.99)) ||
            (near(a.width, b.height) && near(a.height, b.width) && abs(dr - 90) < 0.01) ||
            (a.width == a.height && near(a.width, b.width) && near(a.height, b.height))
    }

    /**
     * Texts6: each part's designator is printed on the Top Overlay; the values (COMMENTON=FALSE)
     * and the `.Designator` templates on Mechanical 7 are not. Strokes land on the overlay layer.
     */
    @Test
    fun silkscreenTextsFollowAltiumVisibility() {
        val (native, _) = boards()
        val texts = native.texts
        assertEquals(17, texts.size, texts.map { it.text }.toString())
        assertEquals(setOf("Top Overlay"), texts.map { it.layer }.toSet())
        assertTrue(texts.none { it.text == "100nF" || it.text.startsWith(".") })
        assertTrue(texts.any { it.text == "FLASH" } && texts.count { it.text == "RP2040" } == 2)
        // Value still comes from the (hidden) comment text.
        assertEquals("100nF", native.component("C1")!!.value)
        assertTrue(native.tracks.count { it.layer == "Top Overlay" && it.net == null } > 300)
    }

    @Test
    fun sameBoardAsIpc2581Export() {
        val (native, ipc) = boards()
        val diff = StringBuilder()
        fun note(s: String) { diff.append(s).append('\n') }

        note("parts native=${native.components.size} ipc=${ipc.components.size}")
        note("pads native=${native.pads.size} ipc=${ipc.pads.size}")
        note("tracks native=${native.tracks.size} ipc=${ipc.tracks.size}; vias ${native.vias.size}/${ipc.vias.size}; holes ${native.holes.size}/${ipc.holes.size}; fills ${native.fills.size}/${ipc.fills.size}")
        note("layers native=${native.layers.map { "${it.name}:${it.type}" }}")
        note("layers ipc   =${ipc.layers.map { "${it.name}:${it.type}" }}")
        note("outline native=${native.outline.map { it.bounds }} ipc=${ipc.outline.map { it.bounds }}")

        var mismatches = 0
        for (ic in ipc.components) {
            // Both RP2040 parts are named "RP2040" on the board; Altium's IPC export renamed the chip "RP",
            // the PcbDoc reader makes the second one "RP2040_2".
            val nc = native.component(ALIAS[ic.refDes] ?: ic.refDes)
            if (nc == null) { note("MISSING part ${ic.refDes}"); mismatches++; continue }
            if (nc.side != ic.side || !near(nc.position.x, ic.position.x) || !near(nc.position.y, ic.position.y) || abs(nc.rotationDeg - ic.rotationDeg) > 0.01) {
                note("PART ${ic.refDes}: native ${nc.side} ${nc.position} ${nc.rotationDeg}° vs ipc ${ic.side} ${ic.position} ${ic.rotationDeg}°"); mismatches++
            }
            if (nc.footprint != ic.footprint || nc.value != ic.value || nc.description != ic.description) {
                note("  info ${ic.refDes}: footprint ${nc.footprint}/${ic.footprint} value ${nc.value}/${ic.value} desc ${nc.description}/${ic.description} height ${nc.heightUm}/${ic.heightUm}")
            }
            val np = native.padsOf(nc).groupBy { it.name to it.layer }
            val ip = ipc.padsOf(ic).groupBy { it.name to it.layer }
            if (np.keys != ip.keys) {
                note("PADS ${ic.refDes}: only native ${np.keys - ip.keys}, only ipc ${ip.keys - np.keys}"); mismatches++
            }
            for ((k, list) in ip) {
                val a = np[k]?.firstOrNull() ?: continue
                val b = list.first()
                val problems = buildList {
                    if (!near(a.position.x, b.position.x) || !near(a.position.y, b.position.y)) add("pos ${a.position} vs ${b.position}")
                    // Altium's IPC export writes rounded pads as polygons: compare the rotated bounds then
                    // (tessellation adds up to ~10 µm).
                    val ipcPolygon = b.shape == PadShape.POLYGON && a.shape != PadShape.POLYGON
                    if (ipcPolygon) {
                        val ra = a.bounds; val rb = b.bounds
                        if (!near(ra.minX, rb.minX, 12) || !near(ra.minY, rb.minY, 12) || !near(ra.maxX, rb.maxX, 12) || !near(ra.maxY, rb.maxY, 12)) add("bounds $ra vs $rb")
                    } else if (!sameSize(a, b)) add("size ${a.width}x${a.height}@${a.rotationDeg} vs ${b.width}x${b.height}@${b.rotationDeg}")
                    if (b.net != null && a.net != b.net) add("net ${a.net} vs ${b.net}")
                    // The IPC export can keep a pad's hole apart from the pad (castellated module pads).
                    val ipcHoleHere = ipc.holes.any { h -> near(h.position.x, a.position.x) && near(h.position.y, a.position.y) && a.drill?.let { near(h.diameter, it) } == true }
                    if (a.drill != b.drill && !(a.drill != null && b.drill != null && near(a.drill!!, b.drill!!)) && !(b.drill == null && ipcHoleHere)) add("drill ${a.drill} vs ${b.drill}")
                    if (!ipcPolygon && a.shape != b.shape) add("shape ${a.shape}/r${a.cornerRadius} vs ${b.shape}/r${b.cornerRadius}")
                    else if (!ipcPolygon && !near(a.cornerRadius, b.cornerRadius, 2)) add("corner ${a.cornerRadius} vs ${b.cornerRadius}")
                }
                if (problems.isNotEmpty()) { note("PAD ${ic.refDes}.${k.first} ${k.second}: ${problems.joinToString("; ")}"); mismatches++ }
            }
        }
        val out = File(Samples.dir.parentFile, "core/parser/build/reports/pcbdoc-vs-ipc-RP2040.txt")
        out.parentFile.mkdirs()
        out.writeText(diff.toString())
        println(diff)

        assertEquals(ipc.components.map { ALIAS[it.refDes] ?: it.refDes }.toSet(), native.components.map { it.refDes }.toSet())
        assertEquals(0, mismatches, "see ${out.path}")
        assertEquals(ipc.vias.size, native.vias.size)
    }

    private companion object {
        val ALIAS = mapOf("RP" to "RP2040_2")
    }

    @Test
    fun renderPreview() {
        val (native, _) = boards()
        val out = File(Samples.dir.parentFile, "core/parser/build/renders/RP2040-Module-pcbdoc.png")
        BoardPng.render(native, out)
        BoardPng.render(boards().second, File(out.parentFile, "RP2040-Module-ipc.png"))
        assertTrue(out.length() > 0)
    }

    @Test
    fun schematicMatchingWorksWithThePcbDoc() {
        val (native, ipc) = boards()
        val sch = BoardImporter(testXml).import(listOf(Samples.input("$dir/RP2040-Module.SchDoc"))).schematic!!
        val c = SchematicConnectivity(sch)
        val fromNative = c.matchBoard(native)
        val fromIpc = c.matchBoard(ipc)
        assertEquals(fromIpc.size, fromNative.size)
        assertEquals(fromIpc.mapValues { it.value.name }, fromNative.mapValues { it.value.name })
    }

    @Test
    fun opensFast() {
        assumeTrue(File(Samples.dir, "$dir/RP2040-Module.PcbDoc").exists(), "Real RP2040 sample not present; skipping")
        val bytes = Samples.file("$dir/RP2040-Module.PcbDoc").readBytes()
        repeat(3) { PcbDocParser.parse(bytes, "RP2040-Module.PcbDoc") } // warm-up
        val t0 = System.nanoTime()
        PcbDocParser.parse(bytes, "RP2040-Module.PcbDoc")
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(ms < 500, "took $ms ms")
    }
}
