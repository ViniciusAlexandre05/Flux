package com.pcbsolderview.render

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.centerline
import com.pcbsolderview.model.outline
import kotlin.math.hypot
import kotlin.math.max

/** What a tap landed on. */
sealed interface BoardHit {
    data class OnComponent(val component: Component) : BoardHit
    /** A track, via, pad or copper pour belonging to [net]. */
    data class OnNet(val net: String, val layer: String?) : BoardHit
}

/**
 * Hit-testing for copper: tracks (as thick segments), pads (exact outline), vias and pours.
 * Uses a uniform grid; everything is in board micrometres.
 */
class CopperIndex(private val board: Board) {

    private class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val half: Double, val layer: String, val net: String?)

    private val bounds: Rect = board.bounds.inflate(1000)
    private val cell: Double = max(500.0, maxOf(bounds.width, bounds.height) / 256.0)
    private val cols = (bounds.width / cell).toInt() + 1
    private val rows = (bounds.height / cell).toInt() + 1

    private val segs = ArrayList<Seg>()
    private val segGrid = HashMap<Int, MutableList<Int>>()
    private val padOutlines: List<List<Point>> = board.pads.map { it.outline() }
    private val padGrid = HashMap<Int, MutableList<Int>>()

    init {
        board.tracks.forEach { t ->
            val pts = t.centerline()
            for (i in 1 until pts.size) {
                val s = Seg(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, t.width / 2.0, t.layer, t.net)
                segs += s
                insert(segGrid, segs.lastIndex, minOf(s.x1, s.x2) - s.half, minOf(s.y1, s.y2) - s.half, maxOf(s.x1, s.x2) + s.half, maxOf(s.y1, s.y2) + s.half)
            }
        }
        board.pads.forEachIndexed { i, p ->
            val b = p.bounds
            insert(padGrid, i, b.minX.toDouble(), b.minY.toDouble(), b.maxX.toDouble(), b.maxY.toDouble())
        }
    }

    private fun key(cx: Int, cy: Int) = cy * cols + cx
    private fun cx(x: Double) = ((x - bounds.minX) / cell).toInt().coerceIn(0, cols - 1)
    private fun cy(y: Double) = ((y - bounds.minY) / cell).toInt().coerceIn(0, rows - 1)

    private fun insert(grid: HashMap<Int, MutableList<Int>>, i: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        for (y in cy(y0)..cy(y1)) for (x in cx(x0)..cx(x1)) grid.getOrPut(key(x, y)) { ArrayList(4) }.add(i)
    }

    private fun near(grid: HashMap<Int, MutableList<Int>>, x: Double, y: Double, tol: Double): Set<Int> {
        val out = HashSet<Int>()
        for (gy in cy(y - tol)..cy(y + tol)) for (gx in cx(x - tol)..cx(x + tol)) grid[key(gx, gy)]?.let { out.addAll(it) }
        return out
    }

    /**
     * Pad whose outline contains the point (or is within [tolerance]) on a visible layer.
     * Pads of components mounted on the far side are never returned.
     */
    fun padAt(x: Double, y: Double, tolerance: Double, visible: (String?) -> Boolean, side: Side): Pad? =
        near(padGrid, x, y, tolerance).asSequence()
            .map { board.pads[it] }
            .filter { it.isThroughHole || visible(it.layer) }
            .filter { p ->
                val owner = p.componentRef?.let { board.component(it) }
                if (owner != null) owner.side == side else p.isThroughHole || p.side == side
            }
            .map { it to if (pointInRing(x, y, padOutlines[it.id])) 0.0 else ComponentIndex.distanceToBox(it.bounds, x, y) }
            .filter { it.second <= tolerance }
            .minWithOrNull(compareBy({ if (it.first.side == side || it.first.isThroughHole) 0 else 1 }, { it.second }))
            ?.first

    /** Net of the track or via under the point (and its distance), preferring the visible side. */
    fun traceAt(x: Double, y: Double, tolerance: Double, visible: (String?) -> Boolean, side: Side, sideOf: (String) -> Side?): Pair<BoardHit.OnNet, Double>? {
        var best: Triple<Int, Double, BoardHit.OnNet>? = null
        fun offer(rank: Int, d: Double, hit: BoardHit.OnNet) {
            val b = best
            if (b == null || rank < b.first || (rank == b.first && d < b.second)) best = Triple(rank, d, hit)
        }
        for (i in near(segGrid, x, y, tolerance)) {
            val s = segs[i]
            val net = s.net ?: continue
            if (!visible(s.layer)) continue
            val d = distToSegment(x, y, s.x1, s.y1, s.x2, s.y2) - s.half
            if (d <= tolerance) offer(if (sideOf(s.layer) == side) 0 else 1, max(0.0, d), BoardHit.OnNet(net, s.layer))
        }
        for (v in board.vias) {
            val net = v.net ?: continue
            val d = hypot(x - v.position.x, y - v.position.y) - v.diameter / 2.0
            if (d <= tolerance) offer(0, max(0.0, d), BoardHit.OnNet(net, null))
        }
        return best?.let { it.third to it.second }
    }

    /** Net of the copper pour containing the point. */
    fun pourAt(x: Double, y: Double, visible: (String?) -> Boolean, side: Side, sideOf: (String) -> Side?): BoardHit.OnNet? =
        board.fills.asSequence()
            .filter { it.net != null && visible(it.layer) && it.polygon.bounds.contains(x.toLong(), y.toLong()) }
            .filter { f -> pointInRing(x, y, f.polygon.points) && f.polygon.holes.none { pointInRing(x, y, it) } }
            .sortedBy { if (sideOf(it.layer) == side) 0 else 1 }
            .firstOrNull()?.let { BoardHit.OnNet(it.net!!, it.layer) }

    companion object {
        fun distToSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1; val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) return hypot(px - x1, py - y1)
            val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
        }

        fun pointInRing(x: Double, y: Double, ring: List<Point>): Boolean {
            var inside = false
            var j = ring.lastIndex
            for (i in ring.indices) {
                val xi = ring[i].x.toDouble(); val yi = ring[i].y.toDouble()
                val xj = ring[j].x.toDouble(); val yj = ring[j].y.toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        /** Distance from a point to a polyline, used for erasing annotation strokes. */
        fun distToPolyline(x: Double, y: Double, pts: List<Pair<Double, Double>>): Double {
            if (pts.size == 1) return hypot(x - pts[0].first, y - pts[0].second)
            var best = Double.MAX_VALUE
            for (i in 1 until pts.size) best = minOf(best, distToSegment(x, y, pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second))
            return best
        }
    }
}
