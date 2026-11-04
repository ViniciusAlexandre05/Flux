package com.pcbsolderview.ui

import androidx.compose.ui.geometry.Offset
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.ViewTransform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarqueeGeometryTest {
    private val board = Rect(0, 0, 100_000, 50_000)
    private fun part(ref: String, x: Long, y: Long, side: Side = Side.TOP) =
        Component(refDes = ref, side = side, position = Point(x, y), bounds = Rect(x - 500, y - 500, x + 500, y + 500))

    private val parts = listOf(
        part("R1", 10_000, 40_000), part("R2", 20_000, 40_000), part("U1", 80_000, 10_000),
        part("C9", 15_000, 40_000, Side.BOTTOM),
    )

    @Test
    fun selectsCentresInsideOnVisibleSide() {
        val t = ViewTransform.fitted(board, 1000.0, 500.0, paddingPx = 0.0) // 0.01 px/µm, Y flipped
        // Screen box around the top-left area: board x 5..25 mm, y 35..45 mm.
        val m = Marquee(Offset(50f, 50f), Offset(250f, 150f), done = true)
        assertEquals(listOf("R1", "R2"), MarqueeGeometry.partsInside(parts, m, t, Side.TOP).map { it.refDes })
        assertEquals(listOf("C9"), MarqueeGeometry.partsInside(parts, m, t, Side.BOTTOM).map { it.refDes })
    }

    @Test
    fun worksOnRotatedView() {
        val t = ViewTransform.fitted(board, 1000.0, 1000.0, paddingPx = 0.0).rotateBy(90.0, 500.0, 500.0)
        val sx = t.toScreenX(80_000.0, 10_000.0).toFloat(); val sy = t.toScreenY(80_000.0, 10_000.0).toFloat()
        val m = Marquee(Offset(sx - 20, sy - 20), Offset(sx + 20, sy + 20), done = true)
        assertEquals(listOf("U1"), MarqueeGeometry.partsInside(parts, m, t, Side.TOP).map { it.refDes })
    }

    @Test
    fun annotationOutlineIsClosed() {
        val t = ViewTransform.fitted(board, 1000.0, 500.0, paddingPx = 0.0)
        val o = MarqueeGeometry.annotationOutline(Marquee(Offset(0f, 0f), Offset(100f, 100f)), t)
        assertEquals(5, o.size); assertEquals(o.first(), o.last())
        assertTrue(Marquee(Offset(0f, 0f), Offset(10f, 200f)).isTiny)
    }
}
