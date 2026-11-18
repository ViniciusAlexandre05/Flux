package com.pcbsolderview.parser.ipc2581

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.FileKind
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.testXml
import com.pcbsolderview.parser.altium.AltiumRecords
import com.pcbsolderview.parser.altium.CompoundFile
import com.pcbsolderview.parser.altium.SchDocParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The user's RP2040 module (private, local only): the one design with both board (IPC-2581) and
 * schematic (native .SchDoc), used for schematic sync (S3/S4).
 */
class Rp2040RealBoardTest {
    private val path = "real/RPI_Module/RP2040-Module.cvg"
    private val schPath = "real/RPI_Module/RP2040-Module.SchDoc"

    @BeforeEach
    fun requireRealSample() = assumeTrue(File(Samples.dir, path).exists(), "Real sample $path not present; skipping")

    private val text by lazy { Samples.text(path) }
    private val board: Board by lazy { BoardImporter(testXml).import(listOf(Samples.input(path))).board }

    @Test
    fun importsAsIpc2581() {
        assertEquals(FileKind.IPC2581, BoardImporter(testXml).classify(Samples.input(path)))
        assertEquals(SourceFormat.IPC2581, board.source)
        assertEquals(Regex("<Component refDes=").findAll(text).count(), board.components.size)
        assertTrue(board.nets.isNotEmpty())
        assertTrue(board.components.all { board.padsOf(it).isNotEmpty() }, "every part has pads")
    }

    private val sheet by lazy { SchDocParser.parse(Samples.file(schPath).readBytes(), "RP2040-Module.SchDoc") }

    /** S5: the native sheet and an ASCII copy of its records give the same model. */
    @Test
    fun nativeSchematicReadsAndMatchesItsAsciiCopy() {
        assumeTrue(File(Samples.dir, schPath).exists())
        val records = AltiumRecords.fromStream(CompoundFile(Samples.file(schPath).readBytes()).read("FileHeader")!!)
        val ascii = records.joinToString("\n") { it.toString() }
        val copy = SchDocParser.parseRecords(AltiumRecords.fromText(ascii), "RP2040-Module.SchDoc")
        assertEquals(sheet, copy)
        // Counts from the file (see ROADMAP.md): 43 components, 195 pins, 159 wires, 96 labels, 38 ports, 21 junctions.
        assertEquals(43, sheet.symbols.size)
        assertEquals(195, sheet.symbols.sumOf { it.pins.size })
        assertEquals(159, sheet.wires.size)
        assertEquals(96, sheet.netLabels.size)
        assertEquals(38, sheet.powerPorts.size)
        assertEquals(21, sheet.junctions.size)
        assertEquals(setOf("1V1", "3V3", "GND", "VSYS", "VUSB"), sheet.powerPorts.map { it.text }.toSet())
    }

    /**
     * S1: every board part found in the schematic has the same pins as its pads, one for one.
     * Printed: parts on only one side (the layout holds only part of the schematic).
     */
    @Test
    fun schematicPartsAndPinsMatchTheBoard() {
        assumeTrue(File(Samples.dir, schPath).exists())
        val schRefs = sheet.symbols.map { it.refDes }.toSortedSet()
        val boardRefs = board.components.map { it.refDes }.toSortedSet()
        println("RP2040: board ${boardRefs.size} parts, schematic ${schRefs.size} designators")
        println("  only on board: ${boardRefs - schRefs}")
        println("  only in schematic: ${schRefs - boardRefs}")
        assertTrue(boardRefs.intersect(schRefs).size >= boardRefs.size / 2, "most board parts appear in the schematic")
        // The board places the RP2040 with a module footprint (38 pads) where the schematic has the
        // 57-pin chip, so exact equality is checked for the others and "board pads are schematic pins" for all.
        val differ = ArrayList<String>()
        for (ref in boardRefs.intersect(schRefs)) {
            val pads = board.padsOf(board.component(ref)!!).map { it.name }.toSortedSet()
            val pins = sheet.symbols.filter { it.refDes == ref }.flatMap { it.pins }.map { it.designator }.toSortedSet()
            assertTrue(pins.containsAll(pads), "$ref: board pads ${pads - pins} missing from the schematic")
            if (pads != pins) differ += ref.also { println("  $ref: ${pads.size} pads vs ${pins.size} pins (footprint ${board.component(ref)!!.footprint})") }
        }
        assertEquals(listOf("RP2040"), differ)
    }

    /** The chip and the module header are both "RP2040": flagged once, nothing else is. */
    @Test
    fun duplicateDesignatorIsReported() {
        assumeTrue(File(Samples.dir, schPath).exists())
        val notices = ArrayList<String>()
        val sch = BoardImporter(testXml).import(listOf(Samples.input(path), Samples.input(schPath)), onNotice = { notices += it }).schematic!!
        println("RP2040 duplicates: " + sch.duplicateDesignators.mapValues { (_, s) -> s.map { "${it.label}/${it.libRef}" } })
        assertEquals(listOf("RP2040"), sch.duplicateDesignators.keys.toList())
        assertEquals(1, notices.count { "RP2040" in it && "more than one part" in it })
    }
}
