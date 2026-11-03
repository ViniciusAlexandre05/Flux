package com.pcbsolderview.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Points along an arc from [sx],[sy] to [ex],[ey] around [cx],[cy], excluding the start point and
 * including the end. Start == end means a full circle. Y axis up: counter-clockwise = positive angle.
 */
fun arcPoints(
    sx: Double, sy: Double, ex: Double, ey: Double, cx: Double, cy: Double,
    clockwise: Boolean, maxStepDeg: Double = 6.0,
): List<Pair<Double, Double>> {
    val r = hypot(sx - cx, sy - cy)
    val a0 = atan2(sy - cy, sx - cx)
    val a1 = atan2(ey - cy, ex - cx)
    val full = abs(sx - ex) < 1e-9 && abs(sy - ey) < 1e-9
    var sweep: Double
    if (full) {
        sweep = if (clockwise) -2 * PI else 2 * PI
    } else {
        sweep = a1 - a0
        if (clockwise) { if (sweep >= 0) sweep -= 2 * PI } else { if (sweep <= 0) sweep += 2 * PI }
    }
    val n = ceil(abs(sweep) / Math.toRadians(maxStepDeg)).toInt().coerceAtLeast(2)
    val out = ArrayList<Pair<Double, Double>>(n)
    for (i in 1..n) {
        val a = a0 + sweep * i / n
        out += (cx + r * cos(a)) to (cy + r * sin(a))
    }
    if (!full) out[out.lastIndex] = ex to ey
    return out
}

/**
 * Pad outline in absolute board coordinates, counter-clockwise. Curves are tessellated.
 */
fun Pad.outline(): List<Point> {
    polygon?.let { return it.oriented(ccw = true) }
    val w = width / 2.0; val h = height / 2.0
    val local: List<Pair<Double, Double>> = when (shape) {
        PadShape.ROUND -> circle(0.0, 0.0, w, 32)
        PadShape.RECT -> listOf(-w to -h, w to -h, w to h, -w to h)
        PadShape.OVAL -> {
            val r = minOf(w, h)
            if (w >= h) {
                arc(w - r, 0.0, r, -90.0, 90.0) + arc(-(w - r), 0.0, r, 90.0, 270.0)
            } else {
                arc(0.0, h - r, r, 0.0, 180.0) + arc(0.0, -(h - r), r, 180.0, 360.0)
            }
        }
        PadShape.ROUNDED_RECT -> {
            val r = cornerRadius.toDouble().coerceIn(0.0, minOf(w, h))
            arc(w - r, -(h - r), r, -90.0, 0.0) + arc(w - r, h - r, r, 0.0, 90.0) +
                arc(-(w - r), h - r, r, 90.0, 180.0) + arc(-(w - r), -(h - r), r, 180.0, 270.0)
        }
        PadShape.POLYGON -> listOf(-w to -h, w to -h, w to h, -w to h)
    }
    val rad = Math.toRadians(rotationDeg)
    val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad)
    return local.map { (x, y) ->
        Point(position.x + Math.round(x * c - y * s), position.y + Math.round(x * s + y * c))
    }
}

/** Track outline as a polyline of its centre (arcs tessellated), start point included. */
fun Track.centerline(): List<Pair<Double, Double>> {
    val start = start.x.toDouble() to start.y.toDouble()
    val c = arcCenter ?: return listOf(start, end.x.toDouble() to end.y.toDouble())
    return listOf(start) + arcPoints(
        this.start.x.toDouble(), this.start.y.toDouble(), end.x.toDouble(), end.y.toDouble(),
        c.x.toDouble(), c.y.toDouble(), clockwise,
    )
}

fun circle(cx: Double, cy: Double, r: Double, n: Int): List<Pair<Double, Double>> =
    List(n) { i -> val a = 2 * PI * i / n; (cx + r * cos(a)) to (cy + r * sin(a)) }

private fun arc(cx: Double, cy: Double, r: Double, fromDeg: Double, toDeg: Double, steps: Int = 8): List<Pair<Double, Double>> =
    List(steps + 1) { i ->
        val a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / steps)
        (cx + r * cos(a)) to (cy + r * sin(a))
    }

/** Signed area (positive = counter-clockwise with Y up). */
fun signedArea(pts: List<Point>): Double {
    var s = 0.0
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
    }
    return s / 2
}

/** Returns the ring oriented counter-clockwise (ccw=true) or clockwise. */
fun List<Point>.oriented(ccw: Boolean): List<Point> = if ((signedArea(this) > 0) == ccw) this else asReversed()
