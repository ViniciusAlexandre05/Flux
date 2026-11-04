package com.pcbsolderview.parser.ipc2581

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.model.identicalTo
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.FileKind
import com.pcbsolderview.parser.InputFile
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.mm
import com.pcbsolderview.parser.testXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.math.abs

/** Real Altium export (revision B). Expected counts are computed independently from the raw text. */
class Ipc2581RealBoardTest {
    private val path = "real/double-sided/IEPE_CONDITIONER_V1.cvg"
    private val text by lazy { Samples.text(path) }
    private val board: Board by lazy {
        BoardImporter(testXml).import(listOf(Samples.input(path)))
    }

    private fun count(regex: String) = Regex(regex).findAll(text).count()

    @Test
    fun classifiedAsIpc2581() {
        assertEquals(FileKind.IPC2581, BoardImporter(testXml).classify(Samples.input(path)))
        assertEquals(SourceFormat.IPC2581, board.source)
        assertEquals("IEPE_CONDITIONER_V1", board.name)
    }

    @Test
    fun componentsMatchFile() {
        assertEquals(count("<Component refDes="), board.components.size)
        assertEquals(48, board.components.size)
        val r205 = board.component("R205")!!
        assertEquals(Point(mm(55.030774), mm(59.992583)), r205.position)
        assertEquals(270.0, r205.rotationDeg)
        assertEquals(Side.TOP, r205.side)
        assertEquals("CR1005-0402", r205.footprint)
        assertEquals("100kΩ", r205.value)
        assertTrue(r205.bounds.contains(r205.position.x, r205.position.y))
        // Altium's "_1" de-duplication suffix is removed from the footprint name.
        assertEquals("CR1005-0402", board.component("R301")!!.footprint)
        // Body outline from the package, rotated 270° with the part: 1.0 x 0.6 mm becomes 0.6 x 1.0.
        val body = Rect.of(r205.outline!!)
        assertTrue(abs(body.width - 600) <= 2 && abs(body.height - 1000) <= 2, "$body")
        assertTrue(body.contains(r205.position.x, r205.position.y))
        assertEquals("4.7kΩ", board.component("R301")!!.value)
    }

    @Test
    fun everyComponentHasBomData() {
        val bomRefs = Regex("""<RefDes name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(board.components.map { it.refDes }.toSet(), bomRefs)
        board.components.forEach { c ->
            assertNotNull(c.description, c.refDes)
            assertNotNull(c.value, c.refDes)
        }
        val values = board.components.associate { it.refDes to it.value }
        assertEquals("1uF", values["C206"])
        assertEquals("330Ω", board.components.first { it.description!!.startsWith("RES 330") }.value)
        assertEquals("0.1uF", board.components.first { it.description!!.startsWith("CAP CER 0.1UF") }.value)
        assertEquals("820pF", board.components.first { it.description!!.startsWith("CAP CER 820PF") }.value)
        assertEquals("L7812CV", board.components.first { it.description == "Integrated Circuit" && it.footprint!!.startsWith("TO254") }.value)
    }

    @Test
    fun identicalPartsMatchBomQuantities() {
        // Every BOM line's quantity equals the number of parts found identical to its first designator.
        val items = Regex("""<BomItem [^>]*quantity="(\d+)"[^>]*>\s*<RefDes name="([^"]+)"""").findAll(text).toList()
        assertTrue(items.isNotEmpty())
        val mismatches = items.mapNotNull { m ->
            val qty = m.groupValues[1].toInt()
            val ref = m.groupValues[2]
            val found = board.identicalTo(board.component(ref)!!).size
            if (found == qty) null else "$ref: BOM $qty, found $found"
        }
        assertEquals(emptyList<String>(), mismatches)
        assertEquals(8, board.identicalTo(board.component("R301")!!).size)
    }

    @Test
    fun padsBelongToComponentsWithNets() {
        board.components.filter { it.description != "LOGO LESC" }.forEach { assertTrue(it.padIds.isNotEmpty(), it.refDes) }
        val c100 = board.component("C100")!!
        val pin2 = board.padsOf(c100).filter { it.name == "2" }
        assertTrue(pin2.isNotEmpty())
        pin2.forEach { assertEquals("VCC24V", it.net) }
        assertEquals(setOf("1", "2"), board.padsOf(board.component("R205")!!).map { it.name }.toSet())
        assertTrue(board.pads.all { p -> board.layers.first { it.name == p.layer }.type == LayerType.COPPER })
        assertTrue(board.nets.containsKey("GND"))
        assertTrue(board.nets.keys.none { it.equals("No Net", true) })
    }

    @Test
    fun viasHolesTracksFills() {
        assertEquals(count("""<LayerHole [^>]*platingStatus="VIA""""), board.vias.size)
        val holeKeys = Regex("""<(?:LayerHole|Hole) [^>]*diameter="([^"]+)"[^>]*x="([^"]+)" y="([^"]+)"""").findAll(text)
            .map { m -> Triple(mm(m.groupValues[2].toDouble()), mm(m.groupValues[3].toDouble()), mm(m.groupValues[1].toDouble())) }.toSet()
        assertEquals(holeKeys.size, board.holes.size)
        assertEquals(count("<Line ") + count("<Arc "), board.tracks.size)
        assertEquals(count("<Contour>"), board.fills.size)
        assertEquals(count("<Cutout>"), board.fills.sumOf { it.polygon.holes.size })
        assertTrue(board.vias.all { it.drill in 1..it.diameter })
    }

    @Test
    fun outlineAndLayers() {
        assertEquals(1, board.outline.size)
        val b = board.bounds
        assertTrue(abs(b.minX - 34_417) <= 1 && abs(b.maxX - 82_804) <= 1, "$b")
        assertTrue(abs(b.minY - 36_830) <= 1 && abs(b.maxY - 101_346) <= 1, "$b")
        assertEquals(count("<Layer name="), board.layers.size)
        val top = board.layers.first { it.name == "Top Layer" }
        assertEquals(LayerType.COPPER, top.type); assertEquals(Side.TOP, top.side); assertNotNull(top.rgb)
        assertEquals(LayerType.SILKSCREEN, board.layers.first { it.name == "Bottom Overlay" }.type)
        assertTrue(board.layers.first { it.name == "Top Paste" }.order < board.layers.first { it.name == "Bottom Paste" }.order)
    }

    @Test
    fun progressIsReported() {
        val seen = ArrayList<Float>()
        BoardImporter(testXml).import(listOf(Samples.input(path))) { seen += it }
        assertTrue(seen.size > 2); assertEquals(1f, seen.last()); assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun renderPreview() {
        val out = File(Samples.dir.parentFile, "core/parser/build/renders/IEPE_CONDITIONER_V1.png")
        BoardPng.render(board, out)
        assertTrue(out.length() > 0)
        val realistic = File(out.parentFile, "IEPE_CONDITIONER_V1-realistic.png")
        BoardPng.renderRealistic(board, realistic)
        assertTrue(realistic.length() > 0)
    }
}

class Ipc2581SyntheticTest {
    private val board by lazy { BoardImporter(testXml).import(listOf(Samples.input("synthetic/ipc-mini/mini.xml"))) }

    @Test
    fun outlineWithCurveAndCutout() {
        val o = board.outline.single()
        assertEquals(1, o.holes.size)
        assertEquals(25_000L, o.bounds.maxX) // semicircle bulge from PolyStepCurve
        assertEquals(10_000L, o.bounds.maxY)
    }

    @Test
    fun dictionaryUnitsDifferFromCadUnits() {
        val r1Pads = board.padsOf(board.component("R1")!!)
        assertEquals(2, r1Pads.size)
        r1Pads.forEach { assertEquals(1000L, it.width); assertEquals(2000L, it.height); assertEquals(PadShape.RECT, it.shape) }
    }

    @Test
    fun padsSynthesisedFromPackageAndNetsFromLogicalNet() {
        val r1 = board.component("R1")!!
        val pin1 = board.padsOf(r1).first { it.name == "1" }
        assertEquals(Point(5_000, 4_100), pin1.position) // (-0.9, 0) rotated 90° CCW at (5, 5)
        assertEquals("VCC", pin1.net)
        assertEquals("GND", board.padsOf(r1).first { it.name == "2" }.net)
        assertEquals("10kΩ", r1.value)
        assertEquals("RC0805FR-0710KL", r1.partNumber)
        assertEquals("Yageo", r1.manufacturer)
    }

    @Test
    fun bottomComponentIsMirrored() {
        val c1 = board.component("C1")!!
        assertEquals(Side.BOTTOM, c1.side)
        val pin1 = board.padsOf(c1).first { it.name == "1" }
        assertEquals(Point(12_900, 5_000), pin1.position) // mirrored: local -0.9 lands at +0.9
        assertEquals("BOTTOM", pin1.layer)
        assertEquals(PadShape.ROUNDED_RECT, pin1.shape)
        assertEquals(250L, pin1.cornerRadius)
        assertEquals("4.7uF", c1.value)
        assertEquals("P0805", c1.footprint)
    }

    @Test
    fun setPadsTracksHoles() {
        val tri = board.pads.first { it.shape == PadShape.POLYGON }
        assertEquals("VCC", tri.net); assertNull(tri.componentRef)
        assertEquals(3, tri.polygon!!.size)
        assertEquals("GND", board.pads.first { it.componentRef == "TP1" }.net)
        // 1 line + polyline (1 segment + tessellated curve) on TOP, 1 full-circle arc on SILK_B
        assertEquals(1, board.tracks.count { it.layer == "SILK_B" && it.arcCenter != null })
        assertTrue(board.tracks.count { it.layer == "TOP" } > 3)
        assertEquals(listOf(false), board.holes.map { it.plated })
        assertEquals(0xFF0000, board.layers.first { it.name == "TOP" }.rgb)
    }

    @Test
    fun rejectsDamagedXml() {
        val bad = InputFile("bad.xml", "<?xml version=\"1.0\"?><IPC-2581 revision=\"B\"><Content><Dict".toByteArray())
        assertThrows<BoardParseException> { BoardImporter(testXml).import(listOf(bad)) }
    }

    @Test
    fun deriveValue() {
        assertEquals("100kΩ", Ipc2581Parser.deriveValue("RES 100K OHM 1% 1/16W 0402", null))
        assertEquals("1MΩ", Ipc2581Parser.deriveValue("RES 1M OHM", null))
        assertEquals("2200pF", Ipc2581Parser.deriveValue("CAP CER 2200PF 50V C0G/NP0 0603", null))
        assertEquals("4.7uH", Ipc2581Parser.deriveValue("IND 4.7UH 3A", null))
        assertEquals("LT6238CGNPBF", Ipc2581Parser.deriveValue("LT6238CGNPBF", "Rail-to-Rail Output 215MHz"))
        assertNull(Ipc2581Parser.deriveValue(null, null))
    }
}
