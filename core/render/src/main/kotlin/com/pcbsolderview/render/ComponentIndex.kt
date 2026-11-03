package com.pcbsolderview.render

import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import kotlin.math.hypot
import kotlin.math.max

/**
 * Uniform-grid spatial index over component bounds for hit-testing.
 *
 * Priority when several components overlap the touch point:
 *  1. components on the visible side,
 *  2. components whose box actually contains the point (vs. within touch tolerance),
 *  3. the smallest box.
 */
class ComponentIndex(private val components: List<Component>) {

    private val bounds: Rect = components.map { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: Rect(0, 0, 1, 1)
    private val cols: Int
    private val rows: Int
    private val cellW: Double
    private val cellH: Double
    private val cells: Array<IntArray>

    init {
        val n = components.size.coerceAtLeast(1)
        // About 2 components per cell on average.
        val side = kotlin.math.sqrt(n / 2.0).toInt().coerceIn(1, 256)
        cols = side; rows = side
        cellW = max(1.0, bounds.width.toDouble() / cols)
        cellH = max(1.0, bounds.height.toDouble() / rows)
        val lists = Array(cols * rows) { ArrayList<Int>(4) }
        components.forEachIndexed { i, c ->
            val (x0, y0) = cellOf(c.bounds.minX.toDouble(), c.bounds.minY.toDouble())
            val (x1, y1) = cellOf(c.bounds.maxX.toDouble(), c.bounds.maxY.toDouble())
            for (cy in y0..y1) for (cx in x0..x1) lists[cy * cols + cx].add(i)
        }
        cells = Array(lists.size) { lists[it].toIntArray() }
    }

    private fun cellOf(x: Double, y: Double): Pair<Int, Int> =
        ((x - bounds.minX) / cellW).toInt().coerceIn(0, cols - 1) to ((y - bounds.minY) / cellH).toInt().coerceIn(0, rows - 1)

    /** Components whose bounds, inflated by [tolerance], contain the point. */
    fun candidates(x: Double, y: Double, tolerance: Double = 0.0): List<Component> {
        val out = LinkedHashSet<Int>()
        val (x0, y0) = cellOf(x - tolerance, y - tolerance)
        val (x1, y1) = cellOf(x + tolerance, y + tolerance)
        for (cy in y0..y1) for (cx in x0..x1) for (i in cells[cy * cols + cx]) {
            if (distanceToBox(components[i].bounds, x, y) <= tolerance) out.add(i)
        }
        return out.map { components[it] }
    }

    /**
     * @param tolerance board units (µm); typically touch slop in px divided by view scale.
     */
    fun hitTest(x: Double, y: Double, visibleSide: Side, tolerance: Double = 0.0): Component? =
        candidates(x, y, tolerance).minWithOrNull(
            compareBy<Component>(
                { if (it.side == visibleSide) 0 else 1 },
                { if (distanceToBox(it.bounds, x, y) == 0.0) 0 else 1 },
                { it.bounds.area },
                { distanceToBox(it.bounds, x, y) },
            ),
        )

    companion object {
        fun distanceToBox(r: Rect, x: Double, y: Double): Double {
            val dx = max(0.0, max(r.minX - x, x - r.maxX))
            val dy = max(0.0, max(r.minY - y, y - r.maxY))
            return if (dx == 0.0 && dy == 0.0) 0.0 else hypot(dx, dy)
        }
    }
}
