package com.pcbsolderview.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Layer
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Polygon
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.centerline
import com.pcbsolderview.model.circle
import com.pcbsolderview.model.outline

/** Geometry of one layer: filled areas (pours + SMD pads) and tracks grouped by width. */
class LayerScene(
    val layer: Layer,
    val color: Color,
    val fill: Path,
    /** (width in µm, path of centre lines) — drawn with round caps/joins. */
    val strokes: List<Pair<Float, Path>>,
    val isEmpty: Boolean,
) {
    val name: String get() = layer.name
}

/**
 * Pre-built paths in board coordinates (µm, Y up). Built once per board, drawn every frame
 * under the [ViewTransform] matrix, so pan/zoom never rebuilds geometry.
 *
 * All filled rings are counter-clockwise and holes clockwise, so the default non-zero fill rule
 * handles overlapping shapes and cut-outs correctly.
 */
class BoardScene(val board: Board) {
    val picker = BoardPicker(board)
    val index: ComponentIndex get() = picker.components

    val outline: Path = Path().apply { board.outline.forEach { addPolygon(it) } }
    val hasOutline: Boolean = board.outline.isNotEmpty()

    /** True when the source has real copper geometry (IPC-2581), not just component positions. */
    val hasGeometry: Boolean = board.pads.isNotEmpty() || board.tracks.isNotEmpty()

    /** Board extent used when there is no outline: component area plus a margin. */
    val extent: Rect = board.bounds.let { b ->
        if (hasOutline) b else b.inflate(maxOf(2000L, (maxOf(b.width, b.height) * 0.04).toLong()))
    }

    /** All layers with geometry, in stack order (top first). */
    val layers: List<LayerScene> = buildLayers()

    /** Through-hole pads and vias (drawn above every copper layer, like Altium's Multi-Layer). */
    val multiLayer: Path = Path().apply {
        board.pads.filter { it.isThroughHole }.distinctBy { Triple(it.position, it.componentRef, it.name) }.forEach { addRing(it.outline()) }
        board.vias.forEach { v -> addCircle(v.position, v.diameter / 2.0) }
    }

    val holes: Path = Path().apply {
        board.holes.forEach { h -> addCircle(h.position, h.diameter / 2.0) }
        if (board.holes.isEmpty()) board.vias.forEach { v -> addCircle(v.position, v.drill / 2.0) }
    }

    /** Holes except via holes: vias are tented (covered by mask) on a real board. */
    val holesWithoutVias: Path = Path().apply {
        val viaHoles = board.vias.map { it.position to it.drill }.toHashSet()
        board.holes.filter { (it.position to it.diameter) !in viaHoles }.forEach { h -> addCircle(h.position, h.diameter / 2.0) }
    }

    val componentsTop: Path = componentPath(Side.TOP)
    val componentsBottom: Path = componentPath(Side.BOTTOM)

    private fun componentPath(side: Side) = Path().apply {
        board.components.filter { it.side == side }.forEach { addBody(it) }
    }

    fun pathFor(components: Collection<Component>): Path = Path().apply { components.forEach { addBody(it) } }

    /** Layers hidden until the user turns them on: everything except copper and silkscreen. */
    val defaultHiddenLayers: Set<String> =
        layers.filter { it.layer.type != LayerType.COPPER && it.layer.type != LayerType.SILKSCREEN }.map { it.name }.toSet()

    /** Layers to draw for a view from [side], back to front. */
    fun drawOrder(side: Side, hidden: Set<String> = defaultHiddenLayers): List<LayerScene> {
        val visible = layers.filter { it.name !in hidden }
        fun depth(l: LayerScene): Int {
            val fromFront = if (side == Side.TOP) l.layer.order else layers.size - l.layer.order
            // Silkscreen sits above copper on the same side.
            val bonus = if (l.layer.type == LayerType.SILKSCREEN && l.layer.side == side) -1000 else 0
            return fromFront + bonus
        }
        return visible.sortedByDescending { depth(it) }
    }

    /** Realistic (assembled-board) geometry for one side: exposed pads, silkscreen, component bodies. */
    class Realistic(
        /** Copper layers on this side (tracks + planes), seen through the solder mask. */
        val copper: List<LayerScene>,
        val pads: Path,
        val silk: List<LayerScene>,
        val bodies: List<Pair<Color, Path>>,
        val allBodies: Path,
    )

    private val realisticCache = HashMap<Side, Realistic>()

    fun realistic(side: Side): Realistic = realisticCache.getOrPut(side) {
        val pads = Path()
        board.pads.filter { it.isThroughHole || it.side == side }
            .distinctBy { Triple(it.position, it.componentRef, it.name) }
            .forEach { pads.addRing(it.outline()) }
        val silk = layers.filter { it.layer.type == LayerType.SILKSCREEN && it.layer.side == side }
        // Parts without pads in a CAD export are artwork (logos), already in the silkscreen.
        val parts = board.components.filter { it.side == side && (!hasGeometry || it.padIds.isNotEmpty()) }
        val all = Path()
        val bodies = parts.groupBy { BodyStyle.colorFor(it) }.map { (color, cs) ->
            color to Path().apply { cs.forEach { c -> val s = BodyStyle.shapeFor(c); addRing(s); all.addRing(s) } }
        }
        val copper = layers.filter { it.layer.type == LayerType.COPPER && it.layer.side == side }
        Realistic(copper, pads, silk, bodies, all)
    }

    /** All copper of one net: pours + pads + vias as fill, tracks grouped by width. */
    class NetGeometry(val fill: Path, val strokes: List<Pair<Float, Path>>)

    fun netGeometry(net: String, hidden: Set<String>): NetGeometry {
        val fill = Path()
        board.fills.filter { it.net == net && it.layer !in hidden }.forEach { fill.addPolygon(it.polygon) }
        board.pads.filter { it.net == net && (it.isThroughHole || it.layer !in hidden) }.forEach { fill.addRing(it.outline()) }
        board.vias.filter { it.net == net }.forEach { fill.addCircle(it.position, it.diameter / 2.0) }
        val strokes = board.tracks.filter { it.net == net && it.layer !in hidden }.groupBy { it.width }.map { (w, ts) ->
            w.toFloat() to Path().apply { ts.forEach { addCenterline(it.centerline()) } }
        }
        return NetGeometry(fill, strokes)
    }

    private fun buildLayers(): List<LayerScene> {
        val tracksByLayer = board.tracks.groupBy { it.layer }
        val fillsByLayer = board.fills.groupBy { it.layer }
        val padsByLayer = board.pads.filter { !it.isThroughHole }.groupBy { it.layer }
        return board.layers.map { layer ->
            val fill = Path()
            fillsByLayer[layer.name].orEmpty().forEach { fill.addPolygon(it.polygon) }
            padsByLayer[layer.name].orEmpty().forEach { fill.addRing(it.outline()) }
            val strokes = tracksByLayer[layer.name].orEmpty().groupBy { it.width }.map { (w, ts) ->
                w.toFloat() to Path().apply { ts.forEach { addCenterline(it.centerline()) } }
            }
            val empty = fillsByLayer[layer.name].isNullOrEmpty() && padsByLayer[layer.name].isNullOrEmpty() && strokes.isEmpty()
            LayerScene(layer, layerColor(layer), fill, strokes, empty)
        }.filterNot { it.isEmpty }
    }

    companion object {
        /** Altium's default colours, used when the file does not carry its own. */
        fun layerColor(l: Layer): Color {
            l.rgb?.let { return Color(0xFF000000.toInt() or it) }
            return when (l.type) {
                LayerType.COPPER -> when (l.side) {
                    Side.TOP -> Color(0xFFFF0000)
                    Side.BOTTOM -> Color(0xFF0000FF)
                    null -> Color(0xFFBC8E00)
                }
                LayerType.SILKSCREEN -> if (l.side == Side.BOTTOM) Color(0xFF808000) else Color(0xFFFFFF00)
                LayerType.SOLDER_MASK -> Color(0xFF800080)
                LayerType.PASTE -> Color(0xFF808080)
                else -> Color(0xFFC0C0C0)
            }
        }

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

        fun Path.addCenterline(pts: List<Pair<Double, Double>>) {
            if (pts.isEmpty()) return
            moveTo(pts[0].first.toFloat(), pts[0].second.toFloat())
            if (pts.size == 1) lineTo(pts[0].first.toFloat(), pts[0].second.toFloat())
            for (i in 1 until pts.size) lineTo(pts[i].first.toFloat(), pts[i].second.toFloat())
        }

        fun Path.addCircle(c: Point, r: Double) {
            val pts = circle(c.x.toDouble(), c.y.toDouble(), r, 24)
            moveTo(pts[0].first.toFloat(), pts[0].second.toFloat())
            for (i in 1 until pts.size) lineTo(pts[i].first.toFloat(), pts[i].second.toFloat())
            close()
        }
    }
}
