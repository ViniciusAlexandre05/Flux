package com.pcbsolderview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SearchTest {
    private fun comp(ref: String, value: String?, desc: String? = null) =
        Component(refDes = ref, value = value, description = desc, side = Side.TOP, position = Point(0, 0), bounds = Rect(0, 0, 1, 1))

    private val board = Board(
        name = "t", source = SourceFormat.PICK_AND_PLACE,
        components = listOf(
            comp("R10", "10k"), comp("R2", "10k"), comp("R1", "100k"), comp("C1", "100 nF", "Capacitor"),
            comp("C12", "10uF"), comp("U1", "NE555", "Timer IC"),
        ),
    )

    @Test
    fun naturalOrder() {
        assertEquals(listOf("C1", "C2", "C10", "R1", "R02", "R10"), listOf("R10", "C10", "R02", "C2", "R1", "C1").sortedWith(NaturalOrder))
    }

    @Test
    fun exactDesignatorFirst() {
        val r = board.search("r1")
        assertEquals("R1", r.exact?.refDes)
        assertEquals(listOf("R1", "R10"), r.matches.map { it.refDes })
    }

    @Test
    fun byValueIgnoringSpacesAndCase() {
        val r = board.search("10K")
        assertNull(r.exact)
        assertEquals(listOf("R2", "R10"), r.matches.map { it.refDes }) // "100k" is not a match
        assertEquals(listOf("R1", "R2", "R10"), board.search("k").matches.map { it.refDes }.filter { it.startsWith("R") })
        assertEquals(listOf("C1"), board.search("100nf").matches.map { it.refDes })
    }

    @Test
    fun identicalParts() {
        fun part(ref: String, value: String?, fp: String?, pn: String? = null, desc: String? = null) =
            Component(refDes = ref, value = value, footprint = fp, partNumber = pn, description = desc,
                side = Side.TOP, position = Point(0, 0), bounds = Rect(0, 0, 1, 1))
        val b = Board(
            name = "t", source = SourceFormat.IPC2581,
            components = listOf(
                part("R10", "10k", "0402"), part("R2", "10K", "0402"), part("R3", "10k", "0603"),
                part("C1", "100nF", "0402", pn = "GRM155"), part("C2", "0.1uF", "0402", pn = "grm155"),
                part("C3", "100nF", "0402", pn = "OTHER"),
                part("U1", null, "SOIC8", desc = "Timer"), part("U2", null, "SOIC8", desc = "timer"),
                part("X1", null, null), part("X2", null, null),
            ),
        )
        // Same value + footprint (case-insensitive), different footprint excluded.
        assertEquals(listOf("R2", "R10"), b.identicalTo(b.component("R10")!!).map { it.refDes })
        // Part number wins over the value text.
        assertEquals(listOf("C1", "C2"), b.identicalTo(b.component("C1")!!).map { it.refDes })
        // No value: description + footprint.
        assertEquals(listOf("U1", "U2"), b.identicalTo(b.component("U2")!!).map { it.refDes })
        // Unknown parts only match themselves.
        assertEquals(listOf("X1"), b.identicalTo(b.component("X1")!!).map { it.refDes })
    }

    @Test
    fun mountFilterAndCustomQuery() {
        fun pad(id: Int, ref: String, name: String, drill: Long? = null) =
            Pad(id, name, Point(0, 0), PadShape.RECT, 1, 1, drill = drill, componentRef = ref)
        fun part(ref: String, value: String, fp: String, padIds: List<Int>) =
            Component(refDes = ref, value = value, footprint = fp, side = Side.TOP, position = Point(0, 0), bounds = Rect(0, 0, 1, 1), padIds = padIds)
        val pads = listOf(
            pad(0, "C1", "1"), pad(1, "C1", "2"),                 // SMD
            pad(2, "J1", "1", 800), pad(3, "J1", "2", 800),       // through-hole
            pad(4, "U1", "1"), pad(5, "U1", "TAB", 1200),         // mixed
        )
        val parts = listOf(part("C1", "100nF", "0402", listOf(0, 1)), part("J1", "Header", "HDR1X2", listOf(2, 3)), part("U1", "LM1117", "SOT-223", listOf(4, 5)), part("TP1", "Test", "TP", emptyList()))
        val b = Board(name = "t", source = SourceFormat.IPC2581, pads = pads, components = parts)
        assertEquals(Mount.SMD, b.mountOf(parts[0])); assertEquals(Mount.MIXED, b.mountOf(parts[2])); assertNull(b.mountOf(parts[3]))
        assertEquals(listOf("C1"), b.filterByMount(parts, MountFilter.SMD).map { it.refDes })
        assertEquals(listOf("J1", "U1"), b.filterByMount(parts, MountFilter.PTH).map { it.refDes })
        assertEquals(4, b.filterByMount(parts, MountFilter.ALL).size)
        assertEquals(listOf("C1"), parts.filter { it.matchesQuery("100n 0402") }.map { it.refDes })
        assertEquals(listOf("U1"), parts.filter { it.matchesQuery("lm1117") }.map { it.refDes })
        assertEquals(4, parts.count { it.matchesQuery("  ") })
    }

    @Test
    fun byDescription() {
        assertEquals(listOf("U1"), board.search("timer").matches.map { it.refDes })
        assertEquals(0, board.search("   ").matches.size)
    }
}
