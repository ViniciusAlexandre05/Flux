package com.pcbsolderview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Board and Schematic are two independent on/off buttons; at least one view stays on. */
class ViewLayoutTest {
    @Test
    fun buttonsTurnViewsOnAndOff() {
        assertEquals(ViewLayout.SCHEMATIC, ViewLayout.SPLIT.toggled(board = true))
        assertEquals(ViewLayout.BOARD, ViewLayout.SPLIT.toggled(board = false))
        assertEquals(ViewLayout.SPLIT, ViewLayout.BOARD.toggled(board = false))
        assertEquals(ViewLayout.SPLIT, ViewLayout.SCHEMATIC.toggled(board = true))
    }

    @Test
    fun theLastViewCannotBeHidden() {
        assertNull(ViewLayout.BOARD.toggled(board = true))
        assertNull(ViewLayout.SCHEMATIC.toggled(board = false))
    }
}
