package com.pcbsolderview.render

import kotlin.math.ceil
import kotlin.math.floor

/** Adaptive background grid: a metric step that keeps lines/dots comfortably spaced on screen. */
object Grid {
    /** Candidate steps in µm (0.1 mm … 50 mm). */
    private val STEPS = longArrayOf(100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000)
    const val MIN_SPACING_PX = 22.0
    const val MAJOR_EVERY = 5

    /** Smallest step whose on-screen spacing is at least [MIN_SPACING_PX]. */
    fun stepFor(scalePxPerUm: Double): Long = STEPS.firstOrNull { it * scalePxPerUm >= MIN_SPACING_PX } ?: STEPS.last()

    /** Board-space range [min, max] covered by the viewport, expanded to whole steps. */
    fun range(min: Double, max: Double, step: Long): LongRange {
        val a = floor(min / step).toLong(); val b = ceil(max / step).toLong()
        return a..b
    }

    /** Visible board-space rectangle (minX, minY, maxX, maxY) of a w×h viewport, any rotation. */
    fun visible(t: ViewTransform, w: Double, h: Double): DoubleArray {
        val corners = listOf(t.toBoard(0.0, 0.0), t.toBoard(w, 0.0), t.toBoard(0.0, h), t.toBoard(w, h))
        return doubleArrayOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
    }

    fun isMajor(k: Long) = Math.floorMod(k, MAJOR_EVERY.toLong()) == 0L
}
