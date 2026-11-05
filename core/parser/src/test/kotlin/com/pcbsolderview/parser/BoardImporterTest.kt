package com.pcbsolderview.parser

import com.pcbsolderview.model.SourceFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoardImporterTest {
    private val importer = BoardImporter(testXml)

    @Test
    fun classifiesFiles() {
        assertEquals(FileKind.PICK_AND_PLACE, importer.classify(Samples.input("synthetic/simple/Pick Place for Simple.csv")))
        assertEquals(FileKind.PICK_AND_PLACE, importer.classify(Samples.input("synthetic/double-sided/Pick Place for DoubleSided.txt")))
        assertEquals(FileKind.BOM, importer.classify(Samples.input("synthetic/simple/Simple BOM.csv")))
        assertEquals(FileKind.BOM, importer.classify(Samples.input("synthetic/double-sided/DoubleSided BOM.csv")))
        assertEquals(FileKind.UNKNOWN, importer.classify(InputFile("x.txt", "just some notes".toByteArray())))
    }

    @Test
    fun mergesBomIntoPickPlace() {
        val board = importer.import(listOf(
            Samples.input("synthetic/simple/Simple BOM.csv"),
            Samples.input("synthetic/simple/Pick Place for Simple.csv"),
        )).board
        assertEquals(SourceFormat.PICK_AND_PLACE, board.source)
        assertEquals("Pick Place for Simple", board.name)
        assertEquals(10, board.components.size)
        val u1 = board.component("U1")!!
        assertEquals("Timer IC", u1.description) // P&P value kept
        assertEquals("NE555DR", u1.partNumber)
        assertEquals("Texas Instruments", u1.manufacturer)
        // Every designator has a BOM match.
        board.components.filter { it.refDes != "J1" }.forEach { assertEquals(true, it.partNumber != null, it.refDes) }
    }

    @Test
    fun bomFillsDescriptionWhenPickPlaceHasNone() {
        val board = importer.import(listOf(
            Samples.input("synthetic/double-sided/Pick Place for DoubleSided.txt"),
            Samples.input("synthetic/double-sided/DoubleSided BOM.csv"),
        )).board
        assertEquals("ARM Cortex-M0 MCU, 16KB Flash", board.component("U2")!!.description)
        assertEquals("SRN4018-4R7M", board.component("L1")!!.partNumber)
    }

    @Test
    fun importsZipBundle() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (p in listOf("synthetic/simple/Pick Place for Simple.csv", "synthetic/simple/Simple BOM.csv")) {
                z.putNextEntry(ZipEntry("export/" + Samples.file(p).name))
                z.write(Samples.file(p).readBytes())
            }
        }
        val board = importer.import(listOf(InputFile("simple.zip", out.toByteArray()))).board
        assertEquals(10, board.components.size)
        assertEquals("Murata", board.component("C1")!!.manufacturer)
    }

    @Test
    fun boardOnlyImportHasNoSchematic() {
        val result = importer.import(listOf(Samples.input("synthetic/simple/Pick Place for Simple.csv")))
        assertEquals(10, result.board.components.size)
        assertEquals(null, result.schematic)
    }

    @Test
    fun bomAloneIsNotABoard() {
        val e = assertThrows<UnsupportedFormatException> { importer.import(listOf(Samples.input("synthetic/simple/Simple BOM.csv"))) }
        assertEquals(true, e.message!!.contains("IPC-2581"))
    }
}
