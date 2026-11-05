package com.pcbsolderview.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** .PcbDoc / .SchDoc are recognised and explained to the user instead of a generic error. */
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
    fun pcbDocAloneExplainsItIsNotRecommended() {
        val e = assertThrows<UnsupportedFormatException> { importer.import(listOf(ole("Board.PcbDoc"))) }
        assertEquals(NativeFileMessages.PCBDOC_ONLY, e.message)
        assertTrue(e.message!!.contains("IPC-2581"))
    }

    @Test
    fun schDocAloneExplainsSchematicIsPlanned() {
        val e = assertThrows<UnsupportedFormatException> { importer.import(listOf(ole("Sheet1.SchDoc"))) }
        assertEquals(NativeFileMessages.SCHDOC_ONLY, e.message)
    }

    @Test
    fun pcbDocNextToABoardFileIsSkippedWithANotice() {
        val notices = mutableListOf<String>()
        val board = importer.import(
            listOf(ole("Board.PcbDoc"), ole("Sheet1.SchDoc"), Samples.input("synthetic/simple/Pick Place for Simple.csv")),
            onNotice = { notices += it },
        )
        assertEquals(10, board.components.size)
        assertEquals(listOf(NativeFileMessages.pcbDocSkipped("Pick Place for Simple.csv"), NativeFileMessages.SCHDOC_SKIPPED), notices)
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
