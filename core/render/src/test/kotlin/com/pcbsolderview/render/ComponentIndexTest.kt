package com.pcbsolderview.render

import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ComponentIndexTest {
    private fun comp(ref: String, r: Rect, side: Side = Side.TOP) =
        Component(refDes = ref, side = side, position = r.center, bounds = r)

    @Test
    fun prefersVisibleSideThenSmallest() {
        val big = comp("U1", Rect(0, 0, 10_000, 10_000))
        val small = comp("C1", Rect(4_000, 4_000, 5_000, 5_000))
        val under = comp("C99", Rect(4_200, 4_200, 4_600, 4_600), Side.BOTTOM)
        val idx = ComponentIndex(listOf(big, small, under))
        assertEquals("C1", idx.hitTest(4_500.0, 4_500.0, Side.TOP)?.refDes)
        assertEquals("C99", idx.hitTest(4_400.0, 4_400.0, Side.BOTTOM)?.refDes)
        assertEquals("C99", idx.hitTest(4_500.0, 4_500.0, Side.BOTTOM)?.refDes)
        assertEquals("U1", idx.hitTest(1_000.0, 1_000.0, Side.TOP)?.refDes)
        assertNull(idx.hitTest(1_000.0, 1_000.0, Side.BOTTOM)) // top part can't be picked from below
        assertEquals("U1", idx.hitTest(1_000.0, 1_000.0, Side.BOTTOM, strictSide = false)?.refDes)
        assertNull(idx.hitTest(20_000.0, 20_000.0, Side.TOP))
    }

    @Test
    fun toleranceCatchesNearMissesButContainingWins() {
        val a = comp("R1", Rect(0, 0, 1_000, 500))
        val b = comp("R2", Rect(1_200, 0, 2_200, 500))
        val idx = ComponentIndex(listOf(a, b))
        assertEquals("R1", idx.hitTest(1_050.0, 250.0, Side.TOP, tolerance = 300.0)?.refDes)
        assertEquals("R2", idx.hitTest(1_150.0, 250.0, Side.TOP, tolerance = 300.0)?.refDes)
        assertNull(idx.hitTest(1_100.0, 250.0, Side.TOP, tolerance = 50.0))
    }

    @Test
    fun matchesBruteForceOnRandomBoard() {
        val rnd = Random(42)
        val comps = List(600) { i ->
            val x = rnd.nextLong(0, 200_000); val y = rnd.nextLong(0, 150_000)
            val w = rnd.nextLong(400, 8_000); val h = rnd.nextLong(200, 8_000)
            comp("X$i", Rect(x, y, x + w, y + h), if (rnd.nextBoolean()) Side.TOP else Side.BOTTOM)
        }
        val idx = ComponentIndex(comps)
        repeat(2_000) {
            val x = rnd.nextDouble(-1_000.0, 210_000.0); val y = rnd.nextDouble(-1_000.0, 160_000.0)
            val expected = comps.filter { ComponentIndex.distanceToBox(it.bounds, x, y) <= 500.0 }.map { it.refDes }.toSet()
            assertEquals(expected, idx.candidates(x, y, 500.0).map { it.refDes }.toSet())
        }
    }
}
