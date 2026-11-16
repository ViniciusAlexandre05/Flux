package com.pcbsolderview.render

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Polygon
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Desktop: a part as big as the board (module footprint) is not picked on empty board, only on its pads. */
class BoardSizedPickTest {
    private val board = Board(
        name = "b", source = SourceFormat.IPC2581,
        outline = listOf(Polygon(listOf(Point(0, 0), Point(10_000, 0), Point(10_000, 8_000), Point(0, 8_000)))),
        pads = listOf(
            Pad(0, "1", Point(500, 500), PadShape.RECT, 400, 400, componentRef = "MOD", layer = "Top Layer"),
            Pad(1, "1", Point(5_000, 4_000), PadShape.RECT, 300, 300, componentRef = "R1", layer = "Top Layer"),
        ),
        components = listOf(
            Component("MOD", side = Side.TOP, position = Point(5_000, 4_000), bounds = Rect(0, 0, 10_000, 8_000), padIds = listOf(0)),
            Component("R1", side = Side.TOP, position = Point(5_000, 4_000), bounds = Rect(4_700, 3_700, 5_300, 4_300), padIds = listOf(1)),
        ),
    )
    private val picker = BoardPicker(board)

    @Test
    fun emptyBoardSelectsNothing() {
        assertEquals(setOf("MOD"), picker.boardSized)
        assertNull(picker.pickComponent(8_000.0, 6_000.0, 50.0, Side.TOP))
    }

    @Test
    fun padsStillSelectTheModuleAndSmallPartsWork() {
        assertEquals("MOD", picker.pickComponent(500.0, 500.0, 50.0, Side.TOP)?.refDes)
        assertEquals("R1", picker.pickComponent(4_800.0, 3_800.0, 50.0, Side.TOP)?.refDes)
    }
}
