package com.pcbsolderview.render

import com.pcbsolderview.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The index is generic: here over plain labelled boxes, as schematic pins and wires will be. */
class GridIndexTest {
    private data class Item(val name: String, val box: Rect, val layer: Int = 0)

    @Test
    fun emptyIndexFindsNothing() {
        val idx = GridIndex(emptyList<Item>()) { it.box }
        assertEquals(emptyList<Item>(), idx.candidates(0.0, 0.0, 100.0))
        assertNull(idx.hitTest(0.0, 0.0))
    }

    @Test
    fun containingThenSmallestThenPriority() {
        val wire = Item("wire", Rect(0, 0, 10_000, 200))
        val pin = Item("pin", Rect(4_000, 0, 4_500, 200))
        val label = Item("label", Rect(3_000, -100, 6_000, 300), layer = 1)
        val idx = GridIndex(listOf(wire, pin, label)) { it.box }
        assertEquals("pin", idx.hitTest(4_200.0, 100.0)?.name)
        assertEquals("wire", idx.hitTest(1_000.0, 100.0)?.name)
        // A near miss loses to a box that contains the point, even a larger one.
        assertEquals("label", idx.hitTest(4_200.0, 250.0, tolerance = 100.0)?.name)
        // Priority wins over everything; accept filters out.
        assertEquals("label", idx.hitTest(4_200.0, 100.0, priority = { -it.layer })?.name)
        assertEquals("wire", idx.hitTest(4_200.0, 100.0, accept = { it.name != "pin" && it.layer == 0 })?.name)
        assertNull(idx.hitTest(20_000.0, 0.0, tolerance = 500.0))
    }

    @Test
    fun matchesBruteForce() {
        val rnd = Random(7)
        val items = List(800) { i ->
            val x = rnd.nextLong(-50_000, 50_000); val y = rnd.nextLong(-50_000, 50_000)
            Item("I$i", Rect(x, y, x + rnd.nextLong(0, 5_000), y + rnd.nextLong(0, 5_000)))
        }
        val idx = GridIndex(items) { it.box }
        repeat(2_000) {
            val x = rnd.nextDouble(-60_000.0, 60_000.0); val y = rnd.nextDouble(-60_000.0, 60_000.0)
            val expected = items.filter { GridIndex.distanceToBox(it.box, x, y) <= 300.0 }.toSet()
            assertEquals(expected, idx.candidates(x, y, 300.0).toSet())
        }
    }
}
