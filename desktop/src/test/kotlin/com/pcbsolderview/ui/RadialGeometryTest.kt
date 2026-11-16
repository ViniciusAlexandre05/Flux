package com.pcbsolderview.ui

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RadialGeometryTest {
    private val c = Offset(500f, 500f)

    @Test
    fun wedgesClockwiseFromTop() {
        assertNull(RadialGeometry.itemAt(c, Offset(510f, 505f), innerPx = 40f)) // centre hole
        assertEquals(0, RadialGeometry.itemAt(c, Offset(500f, 400f), 40f)) // up    -> OK
        assertEquals(1, RadialGeometry.itemAt(c, Offset(600f, 470f), 40f)) // right -> Problem
        assertEquals(2, RadialGeometry.itemAt(c, Offset(560f, 600f), 40f)) // down-right -> Check
        assertEquals(3, RadialGeometry.itemAt(c, Offset(440f, 600f), 40f)) // down-left -> Info
        assertEquals(4, RadialGeometry.itemAt(c, Offset(400f, 470f), 40f)) // left  -> Clear
        assertEquals(0, RadialGeometry.itemAt(c, Offset(500f, -2000f), 40f)) // far flick still counts
    }

    @Test
    fun clampKeepsRingOnScreen() {
        assertEquals(Offset(130f, 130f), RadialGeometry.clamp(Offset(10f, 20f), 1000f, 800f, outerPx = 120f, margin = 10f))
        assertEquals(Offset(870f, 670f), RadialGeometry.clamp(Offset(990f, 790f), 1000f, 800f, 120f, 10f))
        assertEquals(Offset(400f, 300f), RadialGeometry.clamp(Offset(400f, 300f), 1000f, 800f, 120f, 10f))
    }
}
