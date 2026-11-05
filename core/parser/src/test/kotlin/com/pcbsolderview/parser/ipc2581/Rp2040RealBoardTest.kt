package com.pcbsolderview.parser.ipc2581

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.FileKind
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.testXml
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

    /**
     * Designators in the schematic (designator records, RECORD=34) vs parts on the board. Printed
     * for S3/S4 planning; the schematic may hold parts that are not placed (and `*` is unannotated).
     */
    @Test
    fun designatorsAgainstSchematic() {
        assumeTrue(File(Samples.dir, schPath).exists())
        val sch = String(Samples.file(schPath).readBytes(), Charsets.ISO_8859_1)
        val schRefs = Regex("""\|RECORD=34\|[^\u0000]*?\|TEXT=([^|\u0000]+)""", RegexOption.IGNORE_CASE).findAll(sch).map { it.groupValues[1].trim() }.toSortedSet()
        val boardRefs = board.components.map { it.refDes }.toSortedSet()
        println("RP2040: board ${boardRefs.size} parts, schematic ${schRefs.size} designators")
        println("  only on board: ${boardRefs - schRefs}")
        println("  only in schematic: ${schRefs - boardRefs}")
        assertTrue(boardRefs.intersect(schRefs).size >= boardRefs.size / 2, "most board parts appear in the schematic")
    }
}
