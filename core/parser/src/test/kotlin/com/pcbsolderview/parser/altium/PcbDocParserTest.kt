package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.model.signedArea
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.InputFile
import com.pcbsolderview.parser.mil
import com.pcbsolderview.parser.testXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Synthetic .PcbDoc built with [PcbDocWriter] + [CompoundFileWriter]: every decoded value. */
class PcbDocParserTest {

    private fun textStream(vararg records: String) = CompoundFileWriter.recordStream(records.toList())

    private val boardRecord =
        "|RECORD=Board|ORIGINX=0mil|ORIGINY=0mil" +
            // 1000 × 800 mil with a rounded corner (90° arc of radius 100 mil) at the top right.
            "|KIND0=0|VX0=0mil|VY0=0mil|KIND1=0|VX1=1000mil|VY1=0mil" +
            "|KIND2=1|VX2=1000mil|VY2=700mil|CX2=900mil|CY2=700mil|R2=100mil|SA2= 0.00000000000000E+0000|EA2= 9.00000000000000E+0001" +
            "|KIND3=0|VX3=900mil|VY3=800mil|KIND4=0|VX4=0mil|VY4=800mil" +
            "|V9_STACK_LAYER0_NAME=Top Overlay|V9_STACK_LAYER0_LAYERID=16973830" +
            "|V9_STACK_LAYER1_NAME=Top Layer|V9_STACK_LAYER1_LAYERID=16777217" +
            "|V9_STACK_LAYER2_NAME=Dielectric 1|V9_STACK_LAYER2_LAYERID=17039361" +
            "|V9_STACK_LAYER3_NAME=Bottom Layer|V9_STACK_LAYER3_LAYERID=16842751"

    private fun build(
        pads: PcbDocWriter.() -> Unit = {
            // R1 (top): SMD rectangle pad 1, rounded-rect pad 2 (25 %), rotated 90°.
            pad("1", 1, 200.0, 300.0, 40.0, 30.0, shape = 2, rotation = 90.0, net = 0, component = 0)
            pad("2", 1, 260.0, 300.0, 40.0, 30.0, shape = 1, rotation = 90.0, net = 1, component = 0, roundPct = 25)
            // J1 (bottom): through-hole round pad and an octagon, on the multi-layer.
            pad("1", 74, 600.0, 400.0, 60.0, 60.0, shape = 1, hole = 35.0, net = 1, component = 1)
            pad("2", 74, 700.0, 400.0, 60.0, 60.0, shape = 3, hole = 35.0, component = 1)
        },
        tracks: PcbDocWriter.() -> Unit = {
            track(1, 200.0, 300.0, 600.0, 400.0, 10.0, net = 1)
            track(56, 0.0, 0.0, 100.0, 0.0, 10.0, keepout = true)
            track(33, 150.0, 250.0, 310.0, 250.0, 5.0, component = 0)
        },
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val arcs = PcbDocWriter().apply { arc(33, 650.0, 400.0, 50.0, 0.0, 360.0, 6.0); arc(32, 500.0, 500.0, 20.0, 0.0, 90.0, 8.0, net = 0) }
        val vias = PcbDocWriter().apply { via(400.0, 350.0, 20.0, 10.0, net = 1) }
        val regions = PcbDocWriter().apply {
            // GND pour on the bottom: net from Polygons6 (record 0), with one hole.
            region(
                32, "V7_LAYER=BOTTOM|KIND=0", listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 800.0, 0.0 to 800.0, 0.0 to 0.0),
                holes = listOf(listOf(100.0 to 100.0, 200.0 to 100.0, 200.0 to 200.0, 100.0 to 200.0)), polygon = 0,
            )
            region(1, "V7_LAYER=TOP|KIND=1", listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0)) // polygon cut-out: skipped
        }
        val bodies = PcbDocWriter().apply {
            region(69, "V7_LAYER=MECHANICAL13|OVERALLHEIGHT=23.622mil", listOf(170.0 to 280.0, 290.0 to 280.0, 290.0 to 320.0, 170.0 to 320.0), component = 0, type = 12)
        }
        return CompoundFileWriter.write(
            linkedMapOf(
                "Board6/Data" to textStream(boardRecord),
                "Nets6/Data" to textStream("|NAME=GND", "|NAME=VCC"),
                "Components6/Data" to textStream(
                    "|LAYER=TOP|X=230mil|Y=300mil|PATTERN=R0603|ROTATION= 9.00000000000000E+0001|SOURCEDESIGNATOR=R1|SOURCELIBREFERENCE=RES 10K OHM 1%|SOURCECOMPONENTLIBRARY=Passives.SchLib|SOURCEDESCRIPTION=Resistor",
                    "|LAYER=BOTTOM|X=650mil|Y=400mil|PATTERN=HDR2|ROTATION= 1.80000000000000E+0002|SOURCEDESIGNATOR=J1|SOURCELIBREFERENCE=Header 2|FOOTPRINTDESCRIPTION=Pin header",
                ),
                "Polygons6/Data" to textStream("|LAYER=BOTTOM|NET=0|POLYGONTYPE=Polygon"),
                "Pads6/Data" to PcbDocWriter().apply(pads).bytes(),
                "Tracks6/Data" to PcbDocWriter().apply(tracks).bytes(),
                "Arcs6/Data" to arcs.bytes(),
                "Vias6/Data" to vias.bytes(),
                "Regions6/Data" to regions.bytes(),
                "ComponentBodies6/Data" to bodies.bytes(),
            ) + extra,
        )
    }

    private fun parse(bytes: ByteArray, notices: MutableList<String> = mutableListOf()) =
        PcbDocParser.parse(bytes, "Synthetic.PcbDoc", onNotice = { notices += it })

    @Test
    fun boardOutlineLayersAndNets() {
        val b = parse(build())
        assertEquals("Synthetic", b.name)
        assertEquals(SourceFormat.PCBDOC, b.source)
        val outline = b.outline.single()
        assertEquals(mil(1000.0), outline.bounds.maxX)
        assertEquals(mil(800.0), outline.bounds.maxY)
        assertTrue(signedArea(outline.points) > 0, "outline is CCW")
        // The rounded corner is tessellated: points between (1000,700) and (900,800) on the arc.
        assertTrue(outline.points.size > 6)
        val c = Point(mil(900.0), mil(700.0))
        assertTrue(outline.points.filter { it.x > c.x && it.y > c.y }.all { Math.abs(Math.hypot((it.x - c.x).toDouble(), (it.y - c.y).toDouble()) - mil(100.0)) < 2 })

        // Component bodies (Mechanical 13) are not a drawn layer.
        assertEquals(listOf("Top Overlay", "Top Layer", "Bottom Layer"), b.layers.map { it.name })
        assertEquals(LayerType.SILKSCREEN, b.layers[0].type)
        assertEquals(Side.BOTTOM, b.layers[2].side)
        assertEquals(setOf("GND", "VCC"), b.nets.keys)
    }

    @Test
    fun componentsAndPads() {
        val b = parse(build())
        val r1 = b.component("R1")!!
        assertEquals(Side.TOP, r1.side)
        assertEquals(Point(mil(230.0), mil(300.0)), r1.position)
        assertEquals(90.0, r1.rotationDeg)
        assertEquals("R0603", r1.footprint)
        assertEquals("10kΩ", r1.value)
        assertEquals("Resistor", r1.description)
        assertEquals("Passives.SchLib : RES 10K OHM 1%", r1.libraryRef)
        assertEquals(mil(23.622), r1.heightUm)
        assertEquals(4, r1.outline!!.size)

        val (p1, p2) = b.padsOf(r1)
        assertEquals("1", p1.name)
        assertEquals(Point(mil(200.0), mil(300.0)), p1.position)
        assertEquals(PadShape.RECT, p1.shape)
        assertEquals(mil(40.0), p1.width); assertEquals(mil(30.0), p1.height)
        assertEquals(90.0, p1.rotationDeg)
        assertNull(p1.drill)
        assertEquals("GND", p1.net)
        assertEquals("Top Layer", p1.layer)
        assertEquals("R1", p1.componentRef)
        assertEquals(PadShape.ROUNDED_RECT, p2.shape)
        assertEquals(Math.round(mil(30.0) / 2.0 * 0.25), p2.cornerRadius)
        assertEquals("VCC", p2.net)

        // Through-hole pads appear once per copper layer, like the IPC-2581 parser.
        val j1 = b.component("J1")!!
        assertEquals(Side.BOTTOM, j1.side)
        assertEquals(180.0, j1.rotationDeg)
        assertEquals("Header 2", j1.value)
        assertEquals("Pin header", j1.description)
        val jPads = b.padsOf(j1)
        assertEquals(listOf("1" to "Top Layer", "1" to "Bottom Layer", "2" to "Top Layer", "2" to "Bottom Layer"), jPads.map { it.name to it.layer })
        assertEquals(listOf(Side.TOP, Side.BOTTOM), jPads.take(2).map { it.side })
        assertEquals(mil(35.0), jPads[0].drill)
        assertEquals(PadShape.ROUND, jPads[0].shape)
        assertEquals(PadShape.POLYGON, jPads[2].shape)
        assertEquals(8, jPads[2].polygon!!.size)
        assertNull(jPads[2].net)
        // Bounds cover the body outline and the pads.
        assertTrue(r1.bounds.minX <= mil(170.0) && r1.bounds.maxX >= mil(290.0))
    }

    @Test
    fun tracksArcsViasHolesAndFills() {
        val b = parse(build())
        // The keep-out track is dropped.
        assertEquals(2 + 2, b.tracks.size)
        val t = b.tracks.first()
        assertEquals("Top Layer", t.layer)
        assertEquals(Point(mil(200.0), mil(300.0)), t.start)
        assertEquals(Point(mil(600.0), mil(400.0)), t.end)
        assertEquals(mil(10.0), t.width)
        assertEquals("VCC", t.net)

        val circle = b.tracks.first { it.arcCenter != null && it.layer == "Top Overlay" }
        assertEquals(circle.start, circle.end) // full circle
        assertEquals(Point(mil(700.0), mil(400.0)), circle.start)
        val quarter = b.tracks.first { it.arcCenter != null && it.layer == "Bottom Layer" }
        assertEquals(Point(mil(520.0), mil(500.0)), quarter.start)
        assertEquals(Point(mil(500.0), mil(520.0)), quarter.end)
        assertEquals(false, quarter.clockwise)
        assertEquals("GND", quarter.net)

        val via = b.vias.single()
        assertEquals(Point(mil(400.0), mil(350.0)), via.position)
        assertEquals(mil(20.0), via.diameter); assertEquals(mil(10.0), via.drill)
        assertEquals("VCC", via.net)
        // Holes: the via and the two through-hole pads, once each.
        assertEquals(3, b.holes.size)

        val pour = b.fills.single()
        assertEquals("Bottom Layer", pour.layer)
        assertEquals("GND", pour.net)
        assertEquals(4, pour.polygon.points.size)
        assertTrue(signedArea(pour.polygon.points) > 0)
        assertTrue(signedArea(pour.polygon.holes.single()) < 0)
    }

    @Test
    fun unknownRecordTypesAreSkippedWithANotice() {
        val tracks: PcbDocWriter.() -> Unit = {
            track(1, 0.0, 0.0, 10.0, 0.0, 1.0)
            record(99, PcbDocWriter.Sub().zeros(17))
            track(1, 0.0, 0.0, 0.0, 10.0, 1.0)
        }
        val notices = mutableListOf<String>()
        val b = parse(build(tracks = tracks), notices)
        assertEquals(2, b.tracks.count { it.layer == "Top Layer" && it.arcCenter == null })
        assertEquals(1, notices.size)
        assertTrue(notices[0].contains("1 damaged or unknown item was skipped"), notices[0])
    }

    @Test
    fun truncatedStreamGivesAPartialBoard() {
        val full = PcbDocWriter().apply {
            track(1, 0.0, 0.0, 10.0, 0.0, 1.0)
            track(1, 0.0, 0.0, 0.0, 10.0, 1.0)
        }.bytes()
        val notices = mutableListOf<String>()
        val b = parse(build(extra = mapOf("Tracks6/Data" to full.copyOf(full.size - 7))), notices)
        assertEquals(1, b.tracks.count { it.layer == "Top Layer" && it.arcCenter == null })
        assertEquals(2, b.components.size)
        assertEquals(1, notices.size)
    }

    @Test
    fun damagedPrimitiveInsideItsRecordIsSkipped() {
        // A track whose sub-record is too short for its fields: framing is fine, the track is skipped.
        val tracks: PcbDocWriter.() -> Unit = {
            record(4, PcbDocWriter.Sub().header(1).i32(0))
            track(1, 0.0, 0.0, 0.0, 10.0, 1.0)
        }
        val notices = mutableListOf<String>()
        val b = parse(build(tracks = tracks), notices)
        assertEquals(1, b.tracks.count { it.arcCenter == null })
        assertEquals(1, notices.size)
    }

    @Test
    fun notAPcbDoc() {
        val e = assertThrows<BoardParseException> { parse(CompoundFileWriter.write(linkedMapOf("Other" to ByteArray(10)))) }
        assertTrue(e.message!!.contains("no board data"), e.message)
    }

    @Test
    fun importerOpensPcbDocWithBom() {
        val bom = "Designator,Manufacturer Part Number,Manufacturer\nR1,RC0603FR-0710KL,Yageo\n".toByteArray()
        val r = BoardImporter(testXml).import(listOf(InputFile("Synthetic.PcbDoc", build()), InputFile("BOM.csv", bom)))
        assertEquals(SourceFormat.PCBDOC, r.board.source)
        assertEquals("RC0603FR-0710KL", r.board.component("R1")!!.partNumber)
        assertEquals("Yageo", r.board.component("R1")!!.manufacturer)
    }
}
