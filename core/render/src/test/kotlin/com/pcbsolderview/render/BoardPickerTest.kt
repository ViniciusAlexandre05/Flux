package com.pcbsolderview.render

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Fill
import com.pcbsolderview.model.Layer
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Polygon
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.model.Track
import com.pcbsolderview.model.Via
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BoardPickerTest {
    //  R1 at (5,5) mm with pads at x=4.5 / 5.5; a VCC track from R1.2 to x=20 on top;
    //  a GND track on bottom crossing under; a GND pour on bottom covering the right half.
    private val pads = listOf(
        Pad(0, "1", Point(4_500, 5_000), PadShape.RECT, 600, 800, net = "SIG", layer = "TOP", side = Side.TOP, componentRef = "R1"),
        Pad(1, "2", Point(5_500, 5_000), PadShape.RECT, 600, 800, net = "VCC", layer = "TOP", side = Side.TOP, componentRef = "R1"),
    )
    private val board = Board(
        name = "t", source = SourceFormat.IPC2581,
        layers = listOf(Layer("TOP", LayerType.COPPER, Side.TOP, 0), Layer("BOT", LayerType.COPPER, Side.BOTTOM, 1)),
        pads = pads,
        tracks = listOf(
            Track("TOP", Point(5_500, 5_000), Point(20_000, 5_000), 250, "VCC"),
            Track("BOT", Point(12_000, 0), Point(12_000, 10_000), 250, "GND"),
        ),
        vias = listOf(Via(Point(18_000, 8_000), 600, 300, "GND")),
        fills = listOf(Fill("BOT", Polygon(listOf(Point(15_000, 0), Point(30_000, 0), Point(30_000, 10_000), Point(15_000, 10_000))), "GND")),
        components = listOf(Component("R1", side = Side.TOP, position = Point(5_000, 5_000), bounds = Rect(4_100, 4_500, 5_900, 5_500), padIds = listOf(0, 1))),
    )
    private val picker = BoardPicker(board)

    private fun pick(x: Double, y: Double, tol: Double = 300.0, visible: (String?) -> Boolean = { true }) =
        picker.pick(x, y, tol, Side.TOP, visible)

    @Test
    fun padSelectsComponent() {
        assertEquals("R1", (pick(4_500.0, 5_000.0) as BoardHit.OnComponent).component.refDes)
    }

    @Test
    fun trackSelectsNetAndPrefersVisibleSide() {
        assertEquals(BoardHit.OnNet("VCC", "TOP"), pick(10_000.0, 5_050.0))
        // At the crossing both tracks are under the finger; the top one wins in a top view.
        assertEquals(BoardHit.OnNet("VCC", "TOP"), pick(12_000.0, 5_000.0))
        assertEquals(BoardHit.OnNet("GND", "BOT"), pick(12_000.0, 2_000.0))
        // Near miss within tolerance still finds the track.
        assertEquals(BoardHit.OnNet("VCC", "TOP"), pick(10_000.0, 5_300.0))
    }

    @Test
    fun viaAndPour() {
        assertEquals(BoardHit.OnNet("GND", null), pick(18_100.0, 8_000.0))
        assertEquals(BoardHit.OnNet("GND", "BOT"), pick(25_000.0, 2_000.0))
        assertNull(pick(1_000.0, 9_000.0))
    }

    @Test
    fun farSideComponentsCannotBePicked() {
        // Viewing from the bottom: R1 (top) is not selectable, neither by pad nor by body.
        val fromBottom = picker.pick(4_500.0, 5_000.0, 300.0, Side.BOTTOM)
        assertEquals(false, fromBottom is BoardHit.OnComponent)
        assertEquals(false, picker.pick(5_000.0, 5_300.0, 300.0, Side.BOTTOM) is BoardHit.OnComponent)
    }

    @Test
    fun hiddenLayersAreNotHit() {
        val onlyTop: (String?) -> Boolean = { it == "TOP" }
        assertNull(pick(25_000.0, 2_000.0, visible = onlyTop))
        assertNull(pick(12_000.0, 2_000.0, visible = onlyTop))
    }

    @Test
    fun polylineDistance() {
        assertEquals(1.0, CopperIndex.distToPolyline(1.0, 1.0, listOf(0.0 to 0.0, 2.0 to 0.0)), 1e-9)
        assertEquals(true, CopperIndex.pointInRing(1.0, 1.0, listOf(Point(0, 0), Point(2, 0), Point(2, 2), Point(0, 2))))
    }
}
