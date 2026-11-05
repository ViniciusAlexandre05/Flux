package com.pcbsolderview.render

import androidx.compose.ui.graphics.Path
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.SchematicLayout
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.SchSymbol
import com.pcbsolderview.model.Schematic
import com.pcbsolderview.model.SchematicSheet
import com.pcbsolderview.render.BoardScene.Companion.addRing

/**
 * Pre-built drawing data for a schematic: one [SheetScene] per sheet, built on a background
 * thread (paths are plain geometry, theme colours are applied at draw time).
 */
class SchematicScene(val schematic: Schematic) {
    val sheets: List<SheetScene> = schematic.sheets.map { SheetScene(it) }
}

class SheetScene(val sheet: SchematicSheet) {
    val layout = SchematicLayout(sheet)

    /** Stroke group: same file colour and width. */
    data class Key(val argb: Int, val widthUm: Long)

    val fills: Map<Int, Path>
    val strokes: Map<Key, Path>
    val wires: Map<Key, Path>
    val dots: Map<Int, Path>
    val extent: Rect = sheet.bounds
    val symbols = GridIndex(sheet.symbols) { it.bounds }

    init {
        fills = layout.fills.groupBy { it.argb }.mapValues { (_, fs) -> Path().apply { fs.forEach { addRing(it.points) } } }
        val (w, other) = layout.strokes.partition { it.kind == SchematicLayout.Kind.WIRE }
        strokes = group(other)
        wires = group(w)
        dots = layout.dots.groupBy { it.argb }.mapValues { (_, ds) ->
            Path().apply { ds.forEach { addRing(SchematicLayout.arc(it.center, it.radius, 0.0, 360.0)) } }
        }
    }

    private fun group(list: List<SchematicLayout.Stroke>): Map<Key, Path> =
        list.groupBy { Key(it.argb, it.widthUm) }.mapValues { (_, ss) -> Path().apply { ss.forEach { addPolyline(it.points, it.closed) } } }

    /** Symbol under a sheet point: the smallest box that contains it, else the nearest within [tolerance]. */
    fun symbolAt(x: Double, y: Double, tolerance: Double): SchSymbol? = symbols.hitTest(x, y, tolerance)

    companion object {
        fun Path.addPolyline(pts: List<Point>, closed: Boolean = false) {
            if (pts.isEmpty()) return
            moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            if (pts.size == 1) lineTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            if (closed) close()
        }
    }
}
