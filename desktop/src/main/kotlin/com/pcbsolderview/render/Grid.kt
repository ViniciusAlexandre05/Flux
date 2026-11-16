package com.pcbsolderview.render

import kotlin.math.ceil
import kotlin.math.floor

enum class GridUnit(val label: String) { MM("mm"), MIL("mil") }

/** Background grid with a fixed, user-chosen spacing (it does not change with zoom). */
object Grid {
    /** Spacing choices per unit, in that unit. */
    val MM_STEPS = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 2.5, 5.0, 10.0)
    val MIL_STEPS = listOf(5.0, 10.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0)

    fun steps(unit: GridUnit) = if (unit == GridUnit.MM) MM_STEPS else MIL_STEPS

    fun toMicrons(value: Double, unit: GridUnit): Long = Math.round(if (unit == GridUnit.MM) value * 1000 else value * 25.4)

    /** Index of the choice in [unit] closest to [stepUm] (used when switching mm <-> mil). */
    fun nearestIndex(stepUm: Long, unit: GridUnit): Int =
        steps(unit).indices.minBy { kotlin.math.abs(toMicrons(steps(unit)[it], unit) - stepUm) }

    /** "0.25 mm", "50 mil". */
    fun label(value: Double, unit: GridUnit): String {
        val v = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
        return "$v ${unit.label}"
    }

    /** Automatic mode: candidate steps in µm (0.1 mm … 50 mm). */
    private val AUTO_STEPS = longArrayOf(100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000)
    const val AUTO_MIN_SPACING_PX = 22.0

    /** Automatic mode: smallest step whose on-screen spacing is at least [AUTO_MIN_SPACING_PX]. */
    fun autoStep(scalePxPerUm: Double): Long = AUTO_STEPS.firstOrNull { it * scalePxPerUm >= AUTO_MIN_SPACING_PX } ?: AUTO_STEPS.last()

    const val MAJOR_EVERY = 5

    /** Below this on-screen spacing, minor lines are skipped (only every 5th is drawn). */
    const val MIN_MINOR_PX = 8.0

    /** Below this on-screen spacing of the major lines, the grid is hidden. */
    const val MIN_MAJOR_PX = 8.0

    /** Grid indices covering the board-space range [min, max]. */
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

    /** What to draw at a zoom: ALL lines, MAJOR lines only, or NOTHING (too dense to be useful). */
    enum class Density { ALL, MAJOR, NOTHING }

    fun density(stepUm: Long, scalePxPerUm: Double): Density = when {
        stepUm * scalePxPerUm >= MIN_MINOR_PX -> Density.ALL
        stepUm * MAJOR_EVERY * scalePxPerUm >= MIN_MAJOR_PX -> Density.MAJOR
        else -> Density.NOTHING
    }
}
