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
    fun byDescription() {
        assertEquals(listOf("U1"), board.search("timer").matches.map { it.refDes })
        assertEquals(0, board.search("   ").matches.size)
    }
}
