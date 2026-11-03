package com.pcbsolderview.render

import com.pcbsolderview.model.Rect
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Affine map from board micrometres (Y up) to screen pixels (Y down):
 *   sx = a*x + c*y + tx
 *   sy = b*x + d*y + ty
 *
 * This is the ONLY place where the Y axis is flipped. Immutable; every operation returns a copy.
 */
data class ViewTransform(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = -1.0,
    val tx: Double = 0.0, val ty: Double = 0.0,
    /** Number of clockwise quarter turns applied on screen (0..3). */
    val quarterTurns: Int = 0,
    /** True when showing the board from the bottom (mirrored left-right). */
    val mirrored: Boolean = false,
    /** Scale at "fit to screen"; zoom limits are relative to it. */
    val fitScale: Double = 1.0,
) {
    /** Pixels per micrometre. */
    val scale: Double get() = sqrt(abs(a * d - b * c))

    val zoomFactor: Double get() = scale / fitScale

    fun toScreenX(x: Double, y: Double) = a * x + c * y + tx
    fun toScreenY(x: Double, y: Double) = b * x + d * y + ty

    fun toBoard(sx: Double, sy: Double): Pair<Double, Double> {
        val det = a * d - b * c
        val px = sx - tx; val py = sy - ty
        return ((d * px - c * py) / det) to ((-b * px + a * py) / det)
    }

    /** Screen-space axis-aligned bounds of a board rectangle. */
    fun toScreen(r: Rect): ScreenRect {
        val xs = doubleArrayOf(
            toScreenX(r.minX.toDouble(), r.minY.toDouble()), toScreenX(r.maxX.toDouble(), r.minY.toDouble()),
            toScreenX(r.minX.toDouble(), r.maxY.toDouble()), toScreenX(r.maxX.toDouble(), r.maxY.toDouble()),
        )
        val ys = doubleArrayOf(
            toScreenY(r.minX.toDouble(), r.minY.toDouble()), toScreenY(r.maxX.toDouble(), r.minY.toDouble()),
            toScreenY(r.minX.toDouble(), r.maxY.toDouble()), toScreenY(r.maxX.toDouble(), r.maxY.toDouble()),
        )
        return ScreenRect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    fun panBy(dx: Double, dy: Double) = copy(tx = tx + dx, ty = ty + dy)

    /** Zooms by [factor] keeping screen point (px, py) fixed; clamped to [MIN_ZOOM]..[MAX_ZOOM] × fit. */
    fun zoomBy(factor: Double, px: Double, py: Double): ViewTransform {
        val target = (scale * factor).coerceIn(fitScale * MIN_ZOOM, fitScale * MAX_ZOOM)
        val f = target / scale
        if (f == 1.0) return this
        return copy(
            a = a * f, b = b * f, c = c * f, d = d * f,
            tx = px + (tx - px) * f, ty = py + (ty - py) * f,
        )
    }

    /** Rotates the view 90° clockwise around screen point (px, py). */
    fun rotate90(px: Double, py: Double): ViewTransform {
        // Screen rotation by +90° (Y down => clockwise): (x, y) -> (-(y - py) + px, (x - px) + py)
        return copy(
            a = -b, b = a, c = -d, d = c,
            tx = px - (ty - py), ty = py + (tx - px),
            quarterTurns = (quarterTurns + 1) % 4,
        )
    }

    /** Mirrors the view left-right around screen x = px (top view <-> bottom view). */
    fun mirror(px: Double): ViewTransform = copy(
        a = -a, c = -c, tx = 2 * px - tx,
        mirrored = !mirrored,
    )

    /** Same orientation (rotation/mirror), but scaled and centred so [bounds] fills the viewport. */
    fun fit(bounds: Rect, viewportW: Double, viewportH: Double, paddingPx: Double = 24.0): ViewTransform =
        fitted(bounds, viewportW, viewportH, quarterTurns, mirrored, paddingPx)

    /** Centres the view on board point (x, y) at the given zoom factor (relative to fit). */
    fun centerOn(x: Double, y: Double, viewportW: Double, viewportH: Double, zoom: Double = zoomFactor): ViewTransform {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = fitScale * z / scale
        val scaled = copy(a = a * f, b = b * f, c = c * f, d = d * f)
        val sx = scaled.toScreenX(x, y) - scaled.tx
        val sy = scaled.toScreenY(x, y) - scaled.ty
        return scaled.copy(tx = viewportW / 2 - sx, ty = viewportH / 2 - sy)
    }

    companion object {
        const val MIN_ZOOM = 0.5
        const val MAX_ZOOM = 60.0

        fun fitted(
            bounds: Rect, viewportW: Double, viewportH: Double,
            quarterTurns: Int = 0, mirrored: Boolean = false, paddingPx: Double = 24.0,
        ): ViewTransform {
            // Unit-scale orientation: mirror (board X), then flip Y, then rotate on screen.
            var t = ViewTransform(a = if (mirrored) -1.0 else 1.0, d = -1.0, mirrored = mirrored)
            repeat(((quarterTurns % 4) + 4) % 4) { t = t.rotate90(0.0, 0.0) }
            val sr = t.toScreen(bounds)
            val bw = sr.width.coerceAtLeast(1.0)
            val bh = sr.height.coerceAtLeast(1.0)
            val s = minOf((viewportW - 2 * paddingPx).coerceAtLeast(1.0) / bw, (viewportH - 2 * paddingPx).coerceAtLeast(1.0) / bh)
            val scaled = t.copy(a = t.a * s, b = t.b * s, c = t.c * s, d = t.d * s, tx = 0.0, ty = 0.0, fitScale = s)
            val r = scaled.toScreen(bounds)
            return scaled.copy(
                tx = (viewportW - r.width) / 2 - r.left,
                ty = (viewportH - r.height) / 2 - r.top,
            )
        }
    }
}

data class ScreenRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun intersects(w: Double, h: Double) = right >= 0 && bottom >= 0 && left <= w && top <= h
}
