package com.pcbsolderview.render

import androidx.compose.ui.graphics.Path
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Polygon
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side

/**
 * Pre-built paths in board coordinates (µm, Y up). Built once per board, drawn every frame
 * under the [ViewTransform] matrix, so pan/zoom never rebuilds geometry.
 */
class BoardScene(val board: Board) {
    val index = ComponentIndex(board.components)

    val outline: Path = Path().apply { board.outline.forEach { addPolygon(it) } }
    val hasOutline: Boolean = board.outline.isNotEmpty()

    /** Board extent used when there is no outline: component area plus a margin. */
    val extent: Rect = board.bounds.let { b ->
        if (hasOutline) b else b.inflate(maxOf(2000L, (maxOf(b.width, b.height) * 0.04).toLong()))
    }

    val componentsTop: Path = componentPath(Side.TOP)
    val componentsBottom: Path = componentPath(Side.BOTTOM)

    private fun componentPath(side: Side) = Path().apply {
        board.components.filter { it.side == side }.forEach { addBody(it) }
    }

    fun pathFor(components: Collection<Component>): Path = Path().apply { components.forEach { addBody(it) } }

    companion object {
        fun Path.addBody(c: Component) = addRect(c.bounds)

        fun Path.addRect(r: Rect) {
            moveTo(r.minX.toFloat(), r.minY.toFloat())
            lineTo(r.maxX.toFloat(), r.minY.toFloat())
            lineTo(r.maxX.toFloat(), r.maxY.toFloat())
            lineTo(r.minX.toFloat(), r.maxY.toFloat())
            close()
        }

        fun Path.addPolygon(p: Polygon) {
            addRing(p.points)
            p.holes.forEach { addRing(it) }
        }

        fun Path.addRing(pts: List<Point>) {
            if (pts.isEmpty()) return
            moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            close()
        }
    }
}
