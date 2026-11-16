package com.pcbsolderview.ui

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaletteDockTest {
    private val w = 1200f
    private val h = 800f

    @Test
    fun nearestEdge() {
        assertEquals(PaletteDock.Edge.LEFT, PaletteDock.nearestEdge(30f, 400f, w, h))
        assertEquals(PaletteDock.Edge.TOP, PaletteDock.nearestEdge(600f, 20f, w, h))
        assertEquals(PaletteDock.Edge.BOTTOM, PaletteDock.nearestEdge(600f, 780f, w, h))
        assertEquals(PaletteDock.Edge.RIGHT, PaletteDock.nearestEdge(1190f, 400f, w, h))
    }

    @Test
    fun orientationFollowsEdgeWithHysteresis() {
        // Column dragged to the top middle -> becomes a bar.
        assertEquals(true, PaletteDock.horizontalFor(600f, 20f, w, h, current = false, hysteresis = 40f))
        // Bar dragged to the left middle -> becomes a column.
        assertEquals(false, PaletteDock.horizontalFor(20f, 400f, w, h, current = true, hysteresis = 40f))
        // Near the top-left corner both edges are about as close: keep the current orientation.
        assertEquals(false, PaletteDock.horizontalFor(30f, 50f, w, h, current = false, hysteresis = 40f))
        assertEquals(true, PaletteDock.horizontalFor(50f, 30f, w, h, current = true, hysteresis = 40f))
    }

    @Test
    fun snapsFlushToEdge() {
        val size = Offset(500f, 64f)
        assertEquals(Offset(300f, 8f), PaletteDock.snap(Offset(300f, 40f), size, w, h, PaletteDock.Edge.TOP, 8f))
        assertEquals(Offset(300f, 728f), PaletteDock.snap(Offset(300f, 600f), size, w, h, PaletteDock.Edge.BOTTOM, 8f))
        assertEquals(Offset(692f, 100f), PaletteDock.snap(Offset(650f, 100f), size, w, h, PaletteDock.Edge.RIGHT, 8f))
    }
}
