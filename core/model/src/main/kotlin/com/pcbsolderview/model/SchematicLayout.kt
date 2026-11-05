package com.pcbsolderview.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Everything to draw for one sheet, as plain geometry (pure Kotlin, testable on the JVM):
 * Altium objects resolved into fills, strokes, dots and texts, with Altium's default colours
 * where the file has none. Colours are the file's (0xAARRGGBB); the render module maps them to
 * the theme. Shared by the app's canvas and the JVM preview renderer used in tests.
 */
class SchematicLayout(val sheet: SchematicSheet) {

    enum class Kind { BORDER, GRAPHIC, PIN, WIRE, POWER, NO_ERC }

    class Stroke(val points: List<Point>, val widthUm: Long, val argb: Int, val kind: Kind, val closed: Boolean = false)
    class Fill(val points: List<Point>, val argb: Int)
    class Dot(val center: Point, val radius: Long, val argb: Int)

    val fills = ArrayList<Fill>()
    val strokes = ArrayList<Stroke>()
    val dots = ArrayList<Dot>()
    val texts = ArrayList<SchText>()

    /** Designator of the symbol each of [texts] belongs to (pins included), null for sheet text. */
    val textOwners = ArrayList<String?>()

    private fun addText(t: SchText, owner: String? = null) { texts += t; textOwners += owner }

    /** Default text size: the sheet's most common symbol text size, else 10 pt. */
    private val em: Long = sheet.symbols.flatMap { it.texts }.groupingBy { it.sizeUm }.eachCount().maxByOrNull { it.value }?.key ?: DEFAULT_EM

    init {
        if (sheet.width > 0 && sheet.height > 0) {
            val m = BORDER_MARGIN
            strokes += Stroke(rect(m, m, sheet.width - m, sheet.height - m), THIN, BORDER_INK, Kind.BORDER, closed = true)
        }
        sheet.graphics.forEach { graphic(it, GRAPHIC_INK) }
        sheet.texts.forEach { addText(it.withDefault(TEXT_INK)) }
        for (s in sheet.symbols) symbol(s)
        for (w in sheet.wires) strokes += Stroke(w.points, max(w.widthUm, THIN), w.argb ?: WIRE_INK, Kind.WIRE)
        for (j in sheet.junctions) dots += Dot(j, JUNCTION_R, WIRE_INK)
        for (l in sheet.netLabels) if (l.label.text.isNotEmpty()) addText(l.label.withDefault(LABEL_INK))
        for (p in sheet.powerPorts) power(p)
        for (x in sheet.noErc) {
            val d = NO_ERC_HALF
            strokes += Stroke(listOf(Point(x.x - d, x.y - d), Point(x.x + d, x.y + d)), THIN * 2, NO_ERC_INK, Kind.NO_ERC)
            strokes += Stroke(listOf(Point(x.x - d, x.y + d), Point(x.x + d, x.y - d)), THIN * 2, NO_ERC_INK, Kind.NO_ERC)
        }
    }

    private fun SchText.withDefault(ink: Int) = if (argb == null) copy(argb = ink) else this

    private fun graphic(g: SchGraphic, ink: Int) {
        val c = g.argb ?: ink
        when (g) {
            is SchGraphic.Poly -> strokes += Stroke(g.points, max(g.widthUm, THIN), c, Kind.GRAPHIC)
            is SchGraphic.Shape -> {
                g.fillArgb?.let { fills += Fill(g.points, it) }
                if (g.argb != null || g.fillArgb == null) strokes += Stroke(g.points, max(g.widthUm, THIN), c, Kind.GRAPHIC, closed = true)
            }
            is SchGraphic.Arc -> strokes += Stroke(arc(g.center, g.radius, g.startDeg, g.endDeg), max(g.widthUm, THIN), c, Kind.GRAPHIC)
        }
    }

    private fun symbol(s: SchSymbol) {
        s.graphics.forEach { graphic(it, BODY_INK) }
        for (p in s.pins) pin(p, s.refDes)
        s.texts.forEach { addText(it.withDefault(TEXT_INK), s.refDes) }
    }

    private fun pin(p: SchPin, owner: String) {
        if (p.hidden) return
        strokes += Stroke(listOf(p.base, p.position), THIN, p.argb ?: PIN_INK, Kind.PIN)
        val size = (em * 0.8).roundToLong()
        val vertical = p.angleDeg == 90 || p.angleDeg == 270
        val rot = if (vertical) 90.0 else 0.0
        if (p.showDesignator && p.designator.isNotEmpty()) {
            val mid = Point((p.base.x + p.position.x) / 2, (p.base.y + p.position.y) / 2)
            // Above a horizontal pin; left of a vertical one (rotated text's "up" is −X).
            val at = if (vertical) Point(mid.x - PIN_TEXT_GAP / 2, mid.y) else Point(mid.x, mid.y + PIN_TEXT_GAP / 2)
            addText(SchText(p.designator, at, size, rot, HAlign.CENTER, VAlign.BOTTOM, PIN_INK), owner)
        }
        val name = p.name
        if (p.showName && !name.isNullOrEmpty()) {
            // Inside the body, starting a small gap from where the pin meets it.
            val g = PIN_NAME_GAP
            val (at, h) = when (p.angleDeg) {
                0 -> Point(p.base.x - g, p.base.y) to HAlign.RIGHT
                180 -> Point(p.base.x + g, p.base.y) to HAlign.LEFT
                90 -> Point(p.base.x, p.base.y - g) to HAlign.RIGHT
                else -> Point(p.base.x, p.base.y + g) to HAlign.LEFT
            }
            addText(SchText(name, at, size, rot, h, VAlign.MIDDLE, PIN_INK, overline = p.nameOverline), owner)
        }
    }

    private fun power(p: SchPowerPort) {
        val c = p.argb ?: LABEL_INK
        val a = p.angleDeg * PI / 180
        val dx = cos(a); val dy = sin(a)
        // Along (u) the pointing direction and across (v) it, in µm from the connection point.
        fun pt(u: Double, v: Double) = Point(p.position.x + (u * dx - v * dy).roundToLong(), p.position.y + (u * dy + v * dx).roundToLong())
        fun line(vararg uv: Pair<Double, Double>) { strokes += Stroke(uv.map { pt(it.first, it.second) }, THIN, c, Kind.POWER) }
        val g = GRID.toDouble()
        var reach = 0.6 * g
        when (p.style) {
            PowerStyle.CIRCLE -> {
                line(0.0 to 0.0, 0.45 * g to 0.0)
                val center = pt(0.6 * g, 0.0)
                strokes += Stroke(arc(center, (0.15 * g).roundToLong(), 0.0, 360.0), THIN, c, Kind.POWER)
                reach = 0.75 * g
            }
            PowerStyle.ARROW, PowerStyle.GOST_ARROW -> {
                line(0.0 to 0.0, 0.5 * g to 0.0)
                line(0.35 * g to -0.2 * g, 0.6 * g to 0.0, 0.35 * g to 0.2 * g, 0.35 * g to -0.2 * g)
            }
            PowerStyle.WAVE -> {
                line(0.0 to 0.0, 0.5 * g to 0.0)
                val wave = (0..16).map { k -> val t = k / 16.0; (0.5 * g + 0.1 * g * sin(t * 2 * PI)) to ((t - 0.5) * 0.8 * g) }
                strokes += Stroke(wave.map { pt(it.first, it.second) }, THIN, c, Kind.POWER)
            }
            PowerStyle.GROUND_POWER, PowerStyle.GOST_GROUND -> {
                line(0.0 to 0.0, 0.4 * g to 0.0)
                line(0.4 * g to -0.5 * g, 0.4 * g to 0.5 * g)
                line(0.55 * g to -0.3 * g, 0.55 * g to 0.3 * g)
                line(0.7 * g to -0.1 * g, 0.7 * g to 0.1 * g)
                reach = 0.8 * g
            }
            PowerStyle.GROUND_SIGNAL -> {
                line(0.0 to 0.0, 0.4 * g to 0.0)
                line(0.4 * g to -0.5 * g, 0.4 * g to 0.5 * g, 0.8 * g to 0.0, 0.4 * g to -0.5 * g)
                reach = 0.9 * g
            }
            PowerStyle.GROUND_EARTH, PowerStyle.GOST_EARTH -> {
                line(0.0 to 0.0, 0.4 * g to 0.0)
                line(0.4 * g to -0.5 * g, 0.4 * g to 0.5 * g)
                for (k in -2..2 step 2) line(0.4 * g to k * 0.2 * g, 0.7 * g to (k * 0.2 - 0.25) * g)
                reach = 0.8 * g
            }
            else -> { // BAR, GOST_BAR, unknown
                line(0.0 to 0.0, 0.5 * g to 0.0)
                line(0.5 * g to -0.4 * g, 0.5 * g to 0.4 * g)
            }
        }
        if (!p.showName || p.text.isEmpty()) return
        val at = pt(reach + 0.15 * g, 0.0)
        val (h, v) = when (p.angleDeg) {
            90 -> HAlign.CENTER to VAlign.BOTTOM
            270 -> HAlign.CENTER to VAlign.TOP
            180 -> HAlign.RIGHT to VAlign.MIDDLE
            else -> HAlign.LEFT to VAlign.MIDDLE
        }
        addText(SchText(p.text, at, em, 0.0, h, v, c, bold = false))
    }

    companion object {
        /** 100 mil, Altium's default grid. */
        const val GRID = 2540L
        const val THIN = 254L / 2
        private const val JUNCTION_R = 508L
        private const val NO_ERC_HALF = 400L
        private const val BORDER_MARGIN = 5080L
        private const val PIN_TEXT_GAP = 500L
        private const val PIN_NAME_GAP = 1270L
        private const val DEFAULT_EM = 3528L

        // Altium's defaults (0xAARRGGBB).
        const val WIRE_INK = 0xFF000080.toInt()
        const val BODY_INK = 0xFF0000FF.toInt()
        const val PIN_INK = 0xFF000000.toInt()
        const val LABEL_INK = 0xFF800000.toInt()
        const val TEXT_INK = 0xFF000080.toInt()
        const val GRAPHIC_INK = 0xFF000080.toInt()
        const val BORDER_INK = 0xFF000000.toInt()
        const val NO_ERC_INK = 0xFFFF0000.toInt()
        const val PAPER = 0xFFFFFCF8.toInt()

        fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1))

        fun arc(c: Point, r: Long, startDeg: Double, endDeg: Double): List<Point> {
            val sweep = endDeg - startDeg
            val n = max(8, (kotlin.math.abs(sweep) / 8).toInt())
            return (0..n).map { k ->
                val a = (startDeg + sweep * k / n) * PI / 180
                Point(c.x + (r * cos(a)).roundToLong(), c.y + (r * sin(a)).roundToLong())
            }
        }
    }
}
