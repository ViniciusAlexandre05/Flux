package com.pcbsolderview.render

import com.pcbsolderview.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewTransformTest {
    private val board = Rect(0, 0, 100_000, 50_000) // 100 x 50 mm
    private val eps = 1e-6

    @Test
    fun fitCentresAndFlipsY() {
        val t = ViewTransform.fitted(board, 1000.0, 1000.0, paddingPx = 0.0)
        assertEquals(0.01, t.scale, eps)
        // Board (0,0) is bottom-left -> screen bottom-left of the centred box.
        assertEquals(0.0, t.toScreenX(0.0, 0.0), eps)
        assertEquals(750.0, t.toScreenY(0.0, 0.0), eps)
        assertEquals(250.0, t.toScreenY(0.0, 50_000.0), eps) // higher Y is higher on screen
    }

    @Test
    fun roundTrip() {
        val t = ViewTransform.fitted(board, 1200.0, 800.0).rotate90(600.0, 400.0).mirror(600.0).zoomBy(3.0, 100.0, 50.0).panBy(7.0, -3.0)
        val (x, y) = t.toBoard(t.toScreenX(1234.0, 5678.0), t.toScreenY(1234.0, 5678.0))
        assertEquals(1234.0, x, 1e-6); assertEquals(5678.0, y, 1e-6)
    }

    @Test
    fun zoomKeepsFocusFixedAndClamps() {
        val t = ViewTransform.fitted(board, 1000.0, 1000.0)
        val (bx, by) = t.toBoard(300.0, 200.0)
        val z = t.zoomBy(4.0, 300.0, 200.0)
        assertEquals(300.0, z.toScreenX(bx, by), 1e-6)
        assertEquals(200.0, z.toScreenY(bx, by), 1e-6)
        assertEquals(4.0, z.zoomFactor, 1e-9)
        assertEquals(ViewTransform.MAX_ZOOM, z.zoomBy(1000.0, 0.0, 0.0).zoomFactor, 1e-9)
        assertEquals(ViewTransform.MIN_ZOOM, z.zoomBy(0.0001, 0.0, 0.0).zoomFactor, 1e-9)
    }

    @Test
    fun rotateIsClockwiseAndFourTurnsIsIdentity() {
        val t = ViewTransform.fitted(board, 1000.0, 1000.0)
        val r = t.rotate90(500.0, 500.0)
        // Board's right edge (+X) points right on screen; after a clockwise turn it points down.
        val dx = r.toScreenX(1000.0, 0.0) - r.toScreenX(0.0, 0.0)
        val dy = r.toScreenY(1000.0, 0.0) - r.toScreenY(0.0, 0.0)
        assertEquals(0.0, dx, 1e-9); assertTrue(dy > 0)
        val back = r.rotate90(500.0, 500.0).rotate90(500.0, 500.0).rotate90(500.0, 500.0)
        assertEquals(t.a, back.a, eps); assertEquals(t.d, back.d, eps)
        assertEquals(t.tx, back.tx, 1e-6); assertEquals(t.ty, back.ty, 1e-6)
        assertEquals(0, back.quarterTurns)
    }

    @Test
    fun mirrorFlipsXOnly() {
        val t = ViewTransform.fitted(board, 1000.0, 1000.0)
        val m = t.mirror(500.0)
        assertTrue(m.mirrored)
        assertEquals(1000.0 - t.toScreenX(10_000.0, 0.0), m.toScreenX(10_000.0, 0.0), 1e-6)
        assertEquals(t.toScreenY(10_000.0, 20_000.0), m.toScreenY(10_000.0, 20_000.0), 1e-6)
        // Fitting a mirrored, rotated view keeps its orientation and fills the viewport.
        val f = m.rotate90(500.0, 500.0).fit(board, 1000.0, 1000.0, 0.0)
        assertTrue(f.mirrored); assertEquals(1, f.quarterTurns)
        val s = f.toScreen(board)
        assertEquals(1000.0, maxOf(s.width, s.height), 1e-6)
    }

    @Test
    fun centerOnPutsPointInMiddle() {
        val t = ViewTransform.fitted(board, 800.0, 600.0).centerOn(25_000.0, 10_000.0, 800.0, 600.0, zoom = 8.0)
        assertEquals(400.0, t.toScreenX(25_000.0, 10_000.0), 1e-6)
        assertEquals(300.0, t.toScreenY(25_000.0, 10_000.0), 1e-6)
        assertEquals(8.0, t.zoomFactor, 1e-9)
    }
}
