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
    fun byDescription() {
        assertEquals(listOf("U1"), board.search("timer").matches.map { it.refDes })
        assertEquals(0, board.search("   ").matches.size)
    }
}
