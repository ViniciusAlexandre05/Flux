package com.pcbsolderview.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** .PcbDoc / .SchDoc are recognised; damaged ones are explained to the user instead of a generic error. */
class NativeAltiumFilesTest {
    private val importer = BoardImporter(testXml)

    /** A compound-file header followed by some record text, like Altium's native files. */
    private fun ole(name: String, body: String = "") = InputFile(
        name,
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) +
            ByteArray(504) + body.toByteArray(Charsets.ISO_8859_1),
    )

    @Test
    fun classifiesByExtension() {
        assertEquals(FileKind.ALTIUM_PCBDOC, importer.classify(ole("Board.PcbDoc")))
        assertEquals(FileKind.ALTIUM_SCHDOC, importer.classify(ole("Sheet1.SchDoc")))
    }

    @Test
    fun classifiesRenamedFilesByContent() {
        assertEquals(FileKind.ALTIUM_PCBDOC, importer.classify(ole("board.bin", "|RECORD=Board|LAYER=1")))
        assertEquals(FileKind.ALTIUM_SCHDOC, importer.classify(ole("sheet", "|HEADER=Protel for Windows - Schematic Capture Binary File Version 5.0")))
        assertEquals(FileKind.UNKNOWN, importer.classify(ole("report.doc", "WordDocument")))
    }

    @Test
    fun damagedPcbDocAloneSuggestsIpc2581() {
        val e = assertThrows<BoardParseException> { importer.import(listOf(ole("Board.PcbDoc"))) }
        assertTrue(e.message!!.startsWith("Could not read Board.PcbDoc"), e.message)
        assertTrue(e.message!!.contains("IPC-2581"))
    }

    @Test
    fun pcbDocNextToIpc2581IsNotNeeded() {
        val notices = mutableListOf<String>()
        val board = importer.import(listOf(ole("Board.PcbDoc"), Samples.input("synthetic/ipc-mini/mini.xml")), onNotice = { notices += it }).board
        assertEquals(com.pcbsolderview.model.SourceFormat.IPC2581, board.source)
        assertEquals(listOf(NativeFileMessages.pcbDocSkipped("mini.xml")), notices)
    }

    @Test
    fun damagedSchDocAloneIsAnError() {
        val e = assertThrows<BoardParseException> { importer.import(listOf(ole("Sheet1.SchDoc"))) }
        assertTrue(e.message!!.contains("compound file"), e.message)
    }

    @Test
    fun damagedPcbDocFallsBackToPickAndPlace() {
        val notices = mutableListOf<String>()
        val board = importer.import(
            listOf(ole("Board.PcbDoc"), ole("Sheet1.SchDoc"), Samples.input("synthetic/simple/Pick Place for Simple.csv")),
            onNotice = { notices += it },
        ).board
        assertEquals(10, board.components.size)
        assertEquals(2, notices.size)
        assertEquals(NativeFileMessages.schDocSkipped("Sheet1.SchDoc", "Damaged compound file (sector size)."), notices[0])
        assertTrue(notices[1].startsWith("Could not read Board.PcbDoc"), notices[1])
    }

    @Test
    fun noNoticeForSupportedFilesOnly() {
        val notices = mutableListOf<String>()
        importer.import(listOf(Samples.input("synthetic/simple/Pick Place for Simple.csv")), onNotice = { notices += it })
        assertEquals(emptyList<String>(), notices)
    }

    /** The user's RP2040 project (private, local only). */
    @Test
    fun realRp2040NativeFiles() {
        val dir = "real/RPI_Module"
        assumeTrue(File(Samples.dir, "$dir/RP2040-Module.PcbDoc").exists(), "Real RP2040 sample not present; skipping")
        assertEquals(FileKind.ALTIUM_PCBDOC, importer.classify(Samples.input("$dir/RP2040-Module.PcbDoc")))
        assertEquals(FileKind.ALTIUM_SCHDOC, importer.classify(Samples.input("$dir/RP2040-Module.SchDoc")))
        // Content detection also works without the extension.
        assertEquals(FileKind.ALTIUM_PCBDOC, importer.classify(InputFile("x", Samples.file("$dir/RP2040-Module.PcbDoc").readBytes())))
        assertEquals(FileKind.ALTIUM_SCHDOC, importer.classify(InputFile("x", Samples.file("$dir/RP2040-Module.SchDoc").readBytes())))
    }
}
