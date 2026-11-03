package com.pcbsolderview.parser.pnp

import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.Units
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.mil
import com.pcbsolderview.parser.mm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PickPlaceParserTest {

    private data class Expected(val ref: String, val x: Long, val y: Long, val rot: Double, val side: Side, val value: String)

    @Test
    fun simpleCsvMillimetres() {
        val board = PickPlaceParser.parse(Samples.text("synthetic/simple/Pick Place for Simple.csv"), "Simple")
        val expected = listOf(
            Expected("C1", mm(10.0), mm(5.0), 90.0, Side.TOP, "100nF"),
            Expected("C2", mm(14.5), mm(5.0), 90.0, Side.TOP, "100nF"),
            Expected("C3", mm(20.25), mm(12.75), 0.0, Side.TOP, "10uF"),
            Expected("R1", mm(8.0), mm(20.0), 180.0, Side.TOP, "10k"),
            Expected("R2", mm(11.0), mm(20.0), 180.0, Side.TOP, "10k"),
            Expected("R3", mm(30.0), mm(20.0), 270.0, Side.TOP, "4k7"),
            Expected("D1", mm(35.0), mm(5.0), 0.0, Side.TOP, "LED Red"),
            Expected("U1", mm(22.0), mm(25.0), 0.0, Side.TOP, "NE555"),
            Expected("J1", mm(3.0), mm(15.0), 90.0, Side.TOP, "Header 2"),
            Expected("Q1", mm(28.5), mm(32.125), 45.0, Side.TOP, "BC847"),
        )
        assertEquals(expected.size, board.components.size)
        assertEquals(Units.MILLIMETER, board.units)
        for (e in expected) {
            val c = board.component(e.ref)!!
            assertEquals(Point(e.x, e.y), c.position, e.ref)
            assertEquals(e.rot, c.rotationDeg, 1e-9, e.ref)
            assertEquals(e.side, c.side, e.ref)
            assertEquals(e.value, c.value, e.ref)
            assertTrue(c.bounds.contains(c.position.x, c.position.y), e.ref)
        }
        assertEquals("Capacitor, ceramic", board.component("C1")!!.description)
        assertEquals("C0603", board.component("C1")!!.footprint)
    }

    @Test
    fun spaceAlignedTxtMilsBothSides() {
        val board = PickPlaceParser.parse(Samples.text("synthetic/double-sided/Pick Place for DoubleSided.txt"))
        assertEquals(8, board.components.size)
        assertEquals(Units.MIL, board.units)

        val c1 = board.component("C1")!!
        assertEquals(Point(mil(1325.0), mil(1300.0)), c1.position)
        assertEquals(Side.TOP, c1.side)
        assertEquals(180.0, c1.rotationDeg)

        val c2 = board.component("C2")!!
        assertEquals(Side.BOTTOM, c2.side)

        val l1 = board.component("L1")!!
        assertEquals(Point(mil(250.5), mil(1750.25)), l1.position)
        assertEquals("4.7uH shielded", l1.value) // trailing column with spaces is kept whole

        assertEquals("ESP32 module", board.component("U3")!!.value)
        assertEquals(4, board.components.count { it.side == Side.BOTTOM })
        assertNull(board.component("U2")!!.description)
    }

    @Test
    fun midXWinsOverRefAndPad() {
        val board = PickPlaceParser.parse(Samples.text("synthetic/double-sided/Pick Place for DoubleSided.txt"))
        // U2: Mid 1500/800, Pad 1300/700
        assertEquals(Point(mil(1500.0), mil(800.0)), board.component("U2")!!.position)
    }

    @Test
    fun columnUnitOverridesDefault() {
        val csv = "Designator,Mid X(mil),Mid Y(mil),Layer\nR1,100,200,Bottom\n"
        val c = PickPlaceParser.parse(csv).components.single()
        assertEquals(Point(mil(100.0), mil(200.0)), c.position)
        assertEquals(Side.BOTTOM, c.side)
    }

    @Test
    fun missingOptionalColumnsBecomeNull() {
        val c = PickPlaceParser.parse("Designator;X;Y\nTP1;1,5;2,5\n").components.single()
        assertEquals(Point(mm(1.5), mm(2.5)), c.position)
        assertNull(c.value); assertNull(c.footprint); assertNull(c.description)
        assertEquals(Side.TOP, c.side)
    }

    @Test
    fun rejectsNonPickPlace() {
        assertThrows<BoardParseException> { PickPlaceParser.parse("hello,world\n1,2\n") }
    }

    @Test
    fun footprintSizeGuesses() {
        assertEquals(1600L to 800L, FootprintSizeGuesser.guess("C0603"))
        assertEquals(1600L to 800L, FootprintSizeGuesser.guess("RESC1608X55N")) // metric 1608 = 0603
        assertEquals(3000L to 2500L, FootprintSizeGuesser.guess("SOT-23"))
        assertEquals(FootprintSizeGuesser.DEFAULT_SIZE to FootprintSizeGuesser.DEFAULT_SIZE, FootprintSizeGuesser.guess(null))
    }
}
