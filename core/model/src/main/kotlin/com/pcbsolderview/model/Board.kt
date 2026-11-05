package com.pcbsolderview.model

/**
 * Format-independent board model. Every parser (IPC-2581, Pick&Place+BOM, PcbDoc) produces this.
 *
 * Coordinates are micrometres (Long) in the board's own frame, Y axis pointing UP (Altium/IPC
 * convention). Conversion to screen space (Y down) happens in exactly one place: the render
 * module's ViewTransform.
 */
data class Board(
    val name: String,
    val source: SourceFormat,
    val outline: List<Polygon> = emptyList(),
    val layers: List<Layer> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val vias: List<Via> = emptyList(),
    val pads: List<Pad> = emptyList(),
    /** Every drilled hole (vias, through-hole pads, mounting holes), deduplicated. */
    val holes: List<Hole> = emptyList(),
    val fills: List<Fill> = emptyList(),
    val texts: List<BoardText> = emptyList(),
    val components: List<Component> = emptyList(),
    val nets: Map<String, Net> = emptyMap(),
    /** Units of the source file. Internal values are always micrometres. */
    val units: Units = Units.MILLIMETER,
) {
    /** Bounding box of everything; falls back to the component boxes when there is no outline. */
    val bounds: Rect by lazy {
        val boxes = sequence {
            outline.forEach { yield(it.bounds) }
            if (outline.isEmpty()) {
                components.forEach { yield(it.bounds) }
                pads.forEach { yield(it.bounds) }
                tracks.forEach { yield(it.bounds) }
                vias.forEach { yield(it.bounds) }
            }
        }
        boxes.fold(null as Rect?) { acc, r -> acc?.union(r) ?: r } ?: Rect(0, 0, 0, 0)
    }

    fun component(refDes: String): Component? = componentsByRef[refDes.uppercase()]

    private val componentsByRef: Map<String, Component> by lazy {
        components.associateBy { it.refDes.uppercase() }
    }

    fun padsOf(component: Component): List<Pad> = component.padIds.mapNotNull { pads.getOrNull(it) }
}

enum class SourceFormat {
    PICK_AND_PLACE, IPC2581, PCBDOC,

    /** No board file was opened, only a schematic: the board is empty. */
    SCHEMATIC_ONLY,
}

enum class Units(val micronsPerUnit: Double) {
    MICROMETER(1.0),
    MILLIMETER(1000.0),
    MIL(25.4),
    INCH(25400.0);

    fun toMicrons(value: Double): Long = Math.round(value * micronsPerUnit)
}

enum class Side {
    TOP, BOTTOM;

    val opposite: Side get() = if (this == TOP) BOTTOM else TOP
}

data class Point(val x: Long, val y: Long)

data class Rect(val minX: Long, val minY: Long, val maxX: Long, val maxY: Long) {
    val width: Long get() = maxX - minX
    val height: Long get() = maxY - minY
    val area: Double get() = width.toDouble() * height.toDouble()
    val center: Point get() = Point((minX + maxX) / 2, (minY + maxY) / 2)

    fun contains(x: Long, y: Long): Boolean = x in minX..maxX && y in minY..maxY
    fun intersects(o: Rect): Boolean = minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY
    fun union(o: Rect) = Rect(minOf(minX, o.minX), minOf(minY, o.minY), maxOf(maxX, o.maxX), maxOf(maxY, o.maxY))
    fun inflate(d: Long) = Rect(minX - d, minY - d, maxX + d, maxY + d)

    companion object {
        fun around(c: Point, w: Long, h: Long) = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
        fun of(points: Iterable<Point>): Rect {
            var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE
            var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
            }
            return if (minX == Long.MAX_VALUE) Rect(0, 0, 0, 0) else Rect(minX, minY, maxX, maxY)
        }
    }
}

/** Closed polygon; arcs are tessellated by the parser. [holes] are cut-outs. */
data class Polygon(val points: List<Point>, val holes: List<List<Point>> = emptyList()) {
    val bounds: Rect by lazy { Rect.of(points) }
}

enum class LayerType { COPPER, SILKSCREEN, SOLDER_MASK, PASTE, ASSEMBLY, OUTLINE, DRILL, DOCUMENT, OTHER }

data class Layer(
    val name: String,
    val type: LayerType,
    /** null for layers that are not on a side (inner copper, drill, outline). */
    val side: Side?,
    /** Stack order, 0 = top. */
    val order: Int,
    /** Colour from the source file (0xRRGGBB), if it defines one. */
    val rgb: Int? = null,
)

data class Hole(val position: Point, val diameter: Long, val plated: Boolean)

data class Track(
    val layer: String,
    val start: Point,
    val end: Point,
    val width: Long,
    val net: String? = null,
    /** When non-null the track is an arc from [start] to [end] around this centre. */
    val arcCenter: Point? = null,
    val clockwise: Boolean = false,
) {
    val bounds: Rect get() = Rect.of(listOf(start, end)).inflate(width / 2)
}

data class Via(
    val position: Point,
    val diameter: Long,
    val drill: Long,
    val net: String? = null,
) {
    val bounds: Rect get() = Rect.around(position, diameter, diameter)
}

enum class PadShape { ROUND, RECT, ROUNDED_RECT, OVAL, POLYGON }

data class Pad(
    /** Index into [Board.pads]. */
    val id: Int,
    val name: String,
    val position: Point,
    val shape: PadShape,
    val width: Long,
    val height: Long,
    val rotationDeg: Double = 0.0,
    /** Hole diameter for through-hole pads, null for SMD. */
    val drill: Long? = null,
    val net: String? = null,
    val layer: String? = null,
    val side: Side = Side.TOP,
    val componentRef: String? = null,
    /** Outline for [PadShape.POLYGON], absolute coordinates. */
    val polygon: List<Point>? = null,
    /** Corner radius for [PadShape.ROUNDED_RECT]. */
    val cornerRadius: Long = 0,
) {
    val isThroughHole: Boolean get() = drill != null
    val bounds: Rect
        get() = polygon?.let { Rect.of(it) } ?: rotatedBox(position, width, height, rotationDeg)
}

/** Copper pour, silkscreen fill, etc. */
data class Fill(val layer: String, val polygon: Polygon, val net: String? = null)

data class BoardText(
    val layer: String,
    val text: String,
    val position: Point,
    val height: Long,
    val rotationDeg: Double = 0.0,
    val mirrored: Boolean = false,
)

data class Component(
    val refDes: String,
    val value: String? = null,
    val description: String? = null,
    val footprint: String? = null,
    val partNumber: String? = null,
    val manufacturer: String? = null,
    val side: Side,
    val position: Point,
    val rotationDeg: Double = 0.0,
    /** Body/courtyard box used for hit-testing. */
    val bounds: Rect,
    /** True when [bounds] was guessed (e.g. Pick&Place has no body size). */
    val boundsEstimated: Boolean = false,
    /** Package body outline in board coordinates, when the source has one (IPC-2581). */
    val outline: List<Point>? = null,
    /** Library reference, e.g. "IEPE_CONDITIONER.PcbLib : RES 100K OHM 1% 1/16W 0402". */
    val libraryRef: String? = null,
    /** Body height above the board, µm, when the source has it. */
    val heightUm: Long? = null,
    /** True when [partNumber] was taken from the library name (no part number in the file). */
    val partNumberFromLibrary: Boolean = false,
    val padIds: List<Int> = emptyList(),
)

data class Net(val name: String, val padIds: List<Int> = emptyList())

/** Axis-aligned bounding box of a w×h rectangle centred on [c] and rotated by [deg]. */
fun rotatedBox(c: Point, w: Long, h: Long, deg: Double): Rect {
    val r = Math.toRadians(deg)
    val cos = kotlin.math.abs(kotlin.math.cos(r))
    val sin = kotlin.math.abs(kotlin.math.sin(r))
    val bw = Math.round(w * cos + h * sin)
    val bh = Math.round(w * sin + h * cos)
    return Rect.around(c, bw, bh)
}
