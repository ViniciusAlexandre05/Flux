package com.pcbsolderview.render

import com.pcbsolderview.model.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Uniform-grid spatial index over anything with a bounding box (board parts now; schematic
 * symbols, pins and wires in v0.1). Built once, read from any thread.
 *
 * Coordinates are the items' own units (µm for boards and schematics).
 */
class GridIndex<T>(val items: List<T>, private val boundsOf: (T) -> Rect) {

    private val bounds: Rect = items.map(boundsOf).reduceOrNull { a, b -> a.union(b) } ?: Rect(0, 0, 1, 1)
    private val cols: Int
    private val rows: Int
    private val cellW: Double
    private val cellH: Double
    private val cells: Array<IntArray>

    init {
        val n = items.size.coerceAtLeast(1)
        // About 2 items per cell on average.
        val side = sqrt(n / 2.0).toInt().coerceIn(1, 256)
        cols = side; rows = side
        cellW = max(1.0, bounds.width.toDouble() / cols)
        cellH = max(1.0, bounds.height.toDouble() / rows)
        val lists = Array(cols * rows) { ArrayList<Int>(4) }
        items.forEachIndexed { i, item ->
            val r = boundsOf(item)
            val (x0, y0) = cellOf(r.minX.toDouble(), r.minY.toDouble())
            val (x1, y1) = cellOf(r.maxX.toDouble(), r.maxY.toDouble())
            for (cy in y0..y1) for (cx in x0..x1) lists[cy * cols + cx].add(i)
        }
        cells = Array(lists.size) { lists[it].toIntArray() }
    }

    private fun cellOf(x: Double, y: Double): Pair<Int, Int> =
        ((x - bounds.minX) / cellW).toInt().coerceIn(0, cols - 1) to ((y - bounds.minY) / cellH).toInt().coerceIn(0, rows - 1)

    /** Items whose bounds, inflated by [tolerance], contain the point (no duplicates). */
    fun candidates(x: Double, y: Double, tolerance: Double = 0.0): List<T> {
        val out = LinkedHashSet<Int>()
        val (x0, y0) = cellOf(x - tolerance, y - tolerance)
        val (x1, y1) = cellOf(x + tolerance, y + tolerance)
        for (cy in y0..y1) for (cx in x0..x1) for (i in cells[cy * cols + cx]) {
            if (distanceToBox(boundsOf(items[i]), x, y) <= tolerance) out.add(i)
        }
        return out.map { items[it] }
    }

    /**
     * The best candidate near the point: boxes that contain the point beat near misses, then the
     * smallest box, then the closest. [accept] drops items (e.g. the far board side); [priority]
     * ranks before all of that (lower first).
     */
    fun hitTest(
        x: Double,
        y: Double,
        tolerance: Double = 0.0,
        accept: (T) -> Boolean = { true },
        priority: (T) -> Int = { 0 },
    ): T? =
        candidates(x, y, tolerance).filter(accept).minWithOrNull(
            compareBy<T>(
                priority,
                { if (distanceToBox(boundsOf(it), x, y) == 0.0) 0 else 1 },
                { boundsOf(it).area },
                { distanceToBox(boundsOf(it), x, y) },
            ),
        )

    companion object {
        /** 0 inside the box, else the Euclidean distance to its edge. */
        fun distanceToBox(r: Rect, x: Double, y: Double): Double {
            val dx = max(0.0, max(r.minX - x, x - r.maxX))
            val dy = max(0.0, max(r.minY - y, y - r.maxY))
            return if (dx == 0.0 && dy == 0.0) 0.0 else hypot(dx, dy)
        }
    }
}
