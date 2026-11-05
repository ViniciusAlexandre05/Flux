package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.PowerStyle
import com.pcbsolderview.model.SchGraphic
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.FileKind
import com.pcbsolderview.parser.InputFile
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.testXml
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchDocParserTest {
    private val path = "synthetic/ipc-mini/mini.SchDoc"
    private val ascii by lazy { Samples.file(path).readBytes() }
    private val sheet by lazy { SchDocParser.parse(ascii, "mini.SchDoc") }

    /** The ASCII fixture's records, re-packed as a native compound file. */
    private fun nativeCopy(): ByteArray {
        val lines = String(ascii, charset("windows-1252")).lines().map { it.trim() }.filter { it.startsWith("|") }
        val header = lines.first().replace("Ascii File", "Binary File")
        return CompoundFileWriter.write(
            linkedMapOf(
                "FileHeader" to CompoundFileWriter.recordStream(listOf(header) + lines.drop(1)),
                "Storage" to CompoundFileWriter.recordStream(listOf("|HEADER=Icon storage")),
            ),
        )
    }

    @Test
    fun compoundFileRoundTrip() {
        val big = ByteArray(10_000) { (it * 7).toByte() }
        val small = "hello".toByteArray()
        val cf = CompoundFile(CompoundFileWriter.write(linkedMapOf("Big" to big, "Dir/Small" to small, "Empty" to ByteArray(0))))
        assertEquals(setOf("Big", "Dir/Small", "Empty"), cf.streams.toSet())
        assertArrayEquals(big, cf.read("big"))
        assertArrayEquals(small, cf.read("DIR/small"))
        assertArrayEquals(ByteArray(0), cf.read("Empty"))
        assertNull(cf.read("missing"))
    }

    @Test
    fun recordsKeepCaseInsensitiveKeysAndUtf8Copies() {
        val r = AltiumRecord.parse("|RECORD=34|Text=C1|%UTF8%NAME=Â± 5%|NAME=? 5%\u0000")
        assertEquals(34, r.type)
        assertEquals("C1", r["TEXT"])
        assertEquals("± 5%", r["name"])
        assertEquals(254L * 100 + 127, AltiumRecord.parse("|X=100|X_FRAC=50000").coordUm("X"))
    }

    @Test
    fun nativeAndAsciiGiveTheSameModel() {
        val native = nativeCopy()
        assertEquals(FileKind.ALTIUM_SCHDOC, BoardImporter(testXml).classify(InputFile("mini.SchDoc", native)))
        assertEquals(FileKind.ALTIUM_SCHDOC, BoardImporter(testXml).classify(InputFile("mini.txt", ascii)))
        assertEquals(sheet, SchDocParser.parse(native, "mini.SchDoc"))
    }

    @Test
    fun readsSymbolsPinsAndParameters() {
        assertEquals("mini", sheet.name)
        assertEquals(1150L * 254, sheet.width) // A4
        assertEquals(listOf("R1", "C1", "U1A", "U1B"), sheet.symbols.map { it.label })
        val r1 = sheet.symbols[0]
        assertEquals("R1", r1.refDes); assertEquals("10k", r1.comment); assertEquals("RES0805", r1.footprint)
        assertEquals("Resistor", r1.description)
        // Pin 1 points left from the body: its connection point is 10 units further left.
        val p1 = r1.pins.first { it.designator == "1" }
        assertEquals(300L * 254, p1.position.x); assertEquals(310L * 254, p1.base.x)
        assertEquals(180, p1.angleDeg)
        assertEquals("100nF", sheet.symbols[1].comment) // "=Value" resolved
        assertEquals(listOf("R1", "10k"), r1.texts.map { it.text })
    }

    @Test
    fun multiPartSymbolsShareOneDesignatorAndShowOnlyTheirPart() {
        val (a, b) = sheet.symbols.filter { it.refDes == "U1" }
        assertEquals(2, a.partCount); assertEquals(1, a.partId); assertEquals(2, b.partId)
        assertEquals(listOf("1"), a.pins.map { it.designator })
        assertEquals(listOf("2"), b.pins.map { it.designator })
        assertEquals(1, a.graphics.size); assertEquals(1, b.graphics.size)
        assertTrue(a.graphics.single() is SchGraphic.Shape)
        // Overlined name "R\S\T\" → RST, every character overlined.
        val rst = b.pins.single()
        assertEquals("RST", rst.name)
        assertTrue(rst.showName)
    }

    @Test
    fun readsWiresLabelsPortsAndMarkers() {
        assertEquals(6, sheet.wires.size)
        assertEquals(listOf("VCC", "VCC"), sheet.netLabels.map { it.text })
        val gnd = sheet.powerPorts.single()
        assertEquals("GND", gnd.text); assertEquals(PowerStyle.GROUND_POWER, gnd.style); assertEquals(270, gnd.angleDeg)
        assertEquals(1, sheet.junctions.size); assertEquals(1, sheet.noErc.size)
        assertEquals(listOf("Mini test sheet"), sheet.texts.map { it.text })
        assertTrue(sheet.texts.single().bold)
    }

    @Test
    fun importerAddsTheSchematicToTheBoard() {
        val result = BoardImporter(testXml).import(listOf(Samples.input("synthetic/ipc-mini/mini.xml"), Samples.input(path)))
        assertEquals(SourceFormat.IPC2581, result.board.source)
        val sch = result.schematic!!
        assertEquals(listOf("C1", "R1", "U1"), sch.designators)
        assertEquals(2, sch.symbolsOf("u1").size)
    }

    @Test
    fun pdfIsAViewOnlyFallbackWhenThereIsNoSchDoc() {
        val pdf = InputFile("Project.pdf", "%PDF-1.7 fake".toByteArray())
        val importer = BoardImporter(testXml)
        assertEquals(FileKind.PDF, importer.classify(pdf))
        val withBoard = importer.import(listOf(Samples.input("synthetic/ipc-mini/mini.xml"), pdf))
        assertEquals("Project.pdf", withBoard.schematicPdf?.name)
        // A real schematic wins over the PDF.
        assertNull(importer.import(listOf(Samples.input("synthetic/ipc-mini/mini.xml"), pdf, Samples.input(path))).schematicPdf)
        val alone = importer.import(listOf(pdf))
        assertEquals(SourceFormat.SCHEMATIC_ONLY, alone.board.source)
        assertEquals("Project", alone.board.name)
    }

    @Test
    fun schematicAloneOpensWithAnEmptyBoard() {
        val result = BoardImporter(testXml).import(listOf(Samples.input(path)))
        assertEquals(SourceFormat.SCHEMATIC_ONLY, result.board.source)
        assertTrue(result.board.components.isEmpty())
        assertEquals("mini", result.board.name)
        assertFalse(result.schematic!!.sheets.isEmpty())
    }
}
