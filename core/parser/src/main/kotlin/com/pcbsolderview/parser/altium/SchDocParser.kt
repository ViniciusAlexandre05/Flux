package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.HAlign
import com.pcbsolderview.model.PinElectrical
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.PowerStyle
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.SchGraphic
import com.pcbsolderview.model.SchNetLabel
import com.pcbsolderview.model.SchPin
import com.pcbsolderview.model.SchPowerPort
import com.pcbsolderview.model.SchSymbol
import com.pcbsolderview.model.SchText
import com.pcbsolderview.model.SchWire
import com.pcbsolderview.model.SchematicSheet
import com.pcbsolderview.model.VAlign
import com.pcbsolderview.parser.BoardParseException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Altium schematic sheet (.SchDoc) → [SchematicSheet]. Native (compound file, `FileHeader`
 * stream) and ASCII files hold the same records, so both go through [parseRecords].
 *
 * Record numbers (RECORD=n) follow Altium's object kinds: 1 component, 2 pin, 4 label,
 * 5 Bézier, 6 polyline, 7 polygon, 8 ellipse, 10 round rectangle, 11 elliptical arc, 12 arc,
 * 13 line, 14 rectangle, 15/16 sheet symbol/entry, 17 power port, 18 port, 22 no-ERC,
 * 25 net label, 26 bus, 27 wire, 28 text frame, 29 junction, 31 sheet, 34 designator,
 * 37 bus entry, 39 template, 41 parameter, 44/45 implementation list/model.
 * Unknown records are ignored. OWNERINDEX counts records from 0, header excluded.
 */
object SchDocParser {

    /** Parses a native or ASCII .SchDoc. */
    fun parse(bytes: ByteArray, name: String): SchematicSheet {
        val records = if (CompoundFile.isCompoundFile(bytes)) {
            val cf = CompoundFile(bytes)
            val header = cf.read("FileHeader") ?: throw BoardParseException("$name is not a schematic (no FileHeader stream).")
            AltiumRecords.fromStream(header)
        } else {
            AltiumRecords.fromText(String(bytes, charset("windows-1252")))
        }
        return parseRecords(records, name)
    }

    /** True when [text] starts like a .SchDoc saved as ASCII. */
    fun looksLikeAsciiSchDoc(text: String) = text.trimStart().startsWith("|HEADER=Protel for Windows - Schematic", ignoreCase = true)

    /** [all] includes the header record first (when present). */
    fun parseRecords(all: List<AltiumRecord>, name: String): SchematicSheet {
        val records = if (all.firstOrNull()?.get("HEADER") != null) all.drop(1) else all
        if (records.none { it.type == 31 || it.type == 1 || it.type == 27 }) {
            throw BoardParseException("$name contains no schematic objects.")
        }
        return Builder(records, sheetName(name)).build()
    }

    private fun sheetName(n: String) = n.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

    private class Font(val sizeUm: Long, val name: String?, val bold: Boolean, val italic: Boolean, val rotation: Double)

    private class Builder(val records: List<AltiumRecord>, val name: String) {
        val fonts = HashMap<Int, Font>()
        var systemFont = 1
        val children = HashMap<Int, MutableList<Int>>()

        /** Sheet parameters (Title, Revision, DrawnBy…): parameters owned by no component. */
        val sheetParams: Map<String, String> = records.filter { it.type == 41 && it["OWNERINDEX"] == null }
            .mapNotNull { r -> r["NAME"]?.let { it.uppercase() to (r["TEXT"] ?: "") } }.toMap()

        /**
         * Label text with Altium special strings resolved: "=Title" shows the sheet's Title parameter;
         * unset ("*") or unknown ones show nothing, like an unfilled title block.
         */
        fun sheetText(t: String): String? {
            if (t.isBlank()) return null
            if (!t.startsWith("=")) return t
            val key = t.substring(1).trim().uppercase()
            val v = if (key == "DOCUMENTNAME") "$name.SchDoc" else sheetParams[key]
            return v?.takeIf { it.isNotBlank() && it != "*" }
        }

        init {
            records.forEachIndexed { i, r ->
                val owner = r["OWNERINDEX"]?.trim()?.toIntOrNull() ?: return@forEachIndexed
                children.getOrPut(owner) { ArrayList() } += i
            }
        }

        fun build(): SchematicSheet {
            var width = 0L; var height = 0L; var paper: Int? = null
            records.firstOrNull { it.type == 31 }?.let { s ->
                val n = s.int("FONTIDCOUNT")
                for (i in 1..n) {
                    fonts[i] = Font(
                        sizeUm = Math.round(s.int("SIZE$i", 10) * PT_UM),
                        name = s["FONTNAME$i"], bold = s.bool("BOLD$i"), italic = s.bool("ITALIC$i"),
                        rotation = s.double("ROTATION$i") ?: 0.0,
                    )
                }
                systemFont = s.int("SYSTEMFONT", 1)
                if (s.bool("USECUSTOMSHEET")) {
                    width = s.coordUm("CUSTOMX"); height = s.coordUm("CUSTOMY")
                } else {
                    val (w, h) = SHEET_SIZES.getOrElse(s.int("SHEETSTYLE")) { SHEET_SIZES[0] }
                    width = Math.round(w * AltiumRecord.SCH_UNIT_UM); height = Math.round(h * AltiumRecord.SCH_UNIT_UM)
                }
                paper = s["AREACOLOR"]?.let { color(it) }
            }

            val symbols = ArrayList<SchSymbol>()
            val owned = HashSet<Int>()
            records.forEachIndexed { i, r -> if (r.type == 1) symbols += symbol(i, r, owned) }

            val wires = ArrayList<SchWire>()
            val junctions = ArrayList<Point>()
            val labels = ArrayList<SchNetLabel>()
            val ports = ArrayList<SchPowerPort>()
            val graphics = ArrayList<SchGraphic>()
            val texts = ArrayList<SchText>()
            val noErc = ArrayList<Point>()
            records.forEachIndexed { i, r ->
                if (i in owned || r.type == 1) return@forEachIndexed
                // Children of a component that were filtered out (other part / display mode).
                val owner = r["OWNERINDEX"]?.trim()?.toIntOrNull()
                if (owner != null && records.getOrNull(owner)?.type == 1) return@forEachIndexed
                if (r.bool("ISHIDDEN")) return@forEachIndexed
                when (r.type) {
                    27 -> points(r).takeIf { it.size >= 2 }?.let { wires += SchWire(it, lineWidth(r), color(r["COLOR"])) }
                    // Buses are drawn, not traced: their members connect through net labels.
                    26 -> points(r).takeIf { it.size >= 2 }?.let { graphics += SchGraphic.Poly(it, max(lineWidth(r), 3 * MIL10), color(r["COLOR"])) }
                    29 -> junctions += loc(r)
                    22 -> noErc += loc(r)
                    25 -> r["TEXT"]?.takeIf { it.isNotBlank() }?.let { t -> labels += SchNetLabel(t, loc(r), text(r, t)) }
                    17 -> ports += powerPort(r)
                    18 -> port(r, labels, graphics)
                    4 -> r["TEXT"]?.let { t -> sheetText(t)?.let { texts += text(r, it) } }
                    28 -> textFrame(r, graphics, texts)
                    41 -> if (owner == null || records.getOrNull(owner)?.type == 31) r["TEXT"]?.let { t ->
                        // Sheet parameters (Title, Date…) only show where a label refers to them.
                        if (r["LOCATION.X"] != null && t != "*") texts += text(r, t)
                    }
                    15 -> sheetSymbol(r, graphics)
                    16 -> {}
                    32, 33 -> r["TEXT"]?.let { t -> texts += text(r, t) }
                    211 -> graphics += SchGraphic.Shape(rectPoints(loc(r), corner(r)), MIL10, color(r["COLOR"]), null)
                    else -> graphic(r)?.let { graphics += it }
                }
            }
            return SchematicSheet(name, width, height, paper, symbols, wires, junctions, labels, ports, graphics, texts, noErc)
        }

        // ---- components

        fun symbol(index: Int, r: AltiumRecord, owned: MutableSet<Int>): SchSymbol {
            val partId = r.int("CURRENTPARTID", 1)
            val partCount = (r.int("PARTCOUNT", 2) - 1).coerceAtLeast(1)
            val displayMode = r.int("DISPLAYMODE", 0)
            val pins = ArrayList<SchPin>()
            val graphics = ArrayList<SchGraphic>()
            val texts = ArrayList<SchText>()
            val params = LinkedHashMap<String, String>()
            var designator: String? = null
            var footprint: String? = null
            val paramTexts = ArrayList<Pair<String, AltiumRecord>>()

            fun visit(i: Int) {
                owned += i
                val c = records[i]
                val pid = c.int("OWNERPARTID", -1)
                if (c.type != 41 && c.type != 34 && pid != -1 && pid != partId) return
                if (c.int("OWNERPARTDISPLAYMODE", 0) != displayMode && c.type != 41 && c.type != 34) return
                when (c.type) {
                    2 -> pins += pin(c)
                    34 -> {
                        designator = c["TEXT"]?.trim()
                        if (!c.bool("ISHIDDEN")) paramTexts += "\u0000designator" to c
                    }
                    41 -> {
                        val n = c["NAME"] ?: return
                        val v = c["TEXT"] ?: ""
                        if (v != "*" && v.isNotEmpty()) params[n] = v
                        if (!c.bool("ISHIDDEN") && c["LOCATION.X"] != null) paramTexts += n to c
                    }
                    44 -> children[i]?.forEach { m ->
                        owned += m
                        val model = records[m]
                        if (model.type == 45 && model["MODELTYPE"].equals("PCBLIB", true)) {
                            if (footprint == null || model.bool("ISCURRENT")) footprint = model["MODELNAME"]
                        }
                        children[m]?.forEach { owned += it }
                    }
                    4 -> c["TEXT"]?.takeIf { it.isNotBlank() && !c.bool("ISHIDDEN") }?.let { texts += text(c, it) }
                    else -> graphic(c)?.let { graphics += it }
                }
                // Parameters of pins etc. are not needed; mark them as owned so they are not drawn.
                if (c.type != 44) children[i]?.forEach { markOwned(it, owned) }
            }
            children[index]?.forEach { visit(it) }

            val refDes = designator?.takeIf { it.isNotEmpty() } ?: "?"
            val label = if (partCount > 1 && refDes.last().isDigit()) refDes + ('A' + (partId - 1).coerceIn(0, 25)) else refDes
            for ((n, c) in paramTexts) {
                val shown = if (n == "\u0000designator") label else resolve(c["TEXT"] ?: "", params)
                if (shown.isNotBlank() && shown != "*") texts += text(c, shown)
            }
            val position = loc(r)
            val pts = ArrayList<Point>()
            pins.forEach { pts += it.position; pts += it.base }
            graphics.forEach { g -> pts += graphicPoints(g) }
            val bounds = if (pts.isEmpty()) Rect.around(position, 2 * GRID, 2 * GRID) else Rect.of(pts).inflate(MIL10 * 2)
            return SchSymbol(
                refDes = refDes, label = label, partId = partId, partCount = partCount,
                libRef = r["LIBREFERENCE"] ?: r["DESIGNITEMID"],
                description = r["COMPONENTDESCRIPTION"]?.takeIf { it.isNotBlank() && it != "*" },
                comment = params["Comment"]?.let { resolve(it, params) }?.takeIf { it.isNotBlank() && it != "*" },
                footprint = footprint,
                position = position, rotationDeg = r.int("ORIENTATION") * 90.0, mirrored = r.bool("ISMIRRORED"),
                pins = pins, graphics = graphics, texts = texts,
                parameters = params, bounds = bounds,
            )
        }

        fun markOwned(i: Int, owned: MutableSet<Int>) {
            if (!owned.add(i)) return
            children[i]?.forEach { markOwned(it, owned) }
        }

        /** "=Value" refers to another parameter. */
        fun resolve(v: String, params: Map<String, String>): String {
            if (!v.startsWith("=")) return v
            val key = v.substring(1)
            return params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: v
        }

        fun pin(c: AltiumRecord): SchPin {
            val conglomerate = c.int("PINCONGLOMERATE")
            val pinName = c["NAME"]?.trim()?.takeIf { it.isNotEmpty() }?.let { overbar(it) }
            val angle = (conglomerate and 3) * 90
            val base = loc(c)
            val len = c.coordUm("PINLENGTH")
            val tip = when (angle) {
                0 -> Point(base.x + len, base.y)
                90 -> Point(base.x, base.y + len)
                180 -> Point(base.x - len, base.y)
                else -> Point(base.x, base.y - len)
            }
            return SchPin(
                designator = c["DESIGNATOR"]?.trim().orEmpty(),
                name = pinName?.first, nameOverline = pinName?.second,
                position = tip, base = base,
                hidden = conglomerate and 0x04 != 0,
                showName = conglomerate and 0x08 != 0,
                showDesignator = conglomerate and 0x10 != 0,
                electrical = PinElectrical.entries.getOrElse(c.int("ELECTRICAL", 0)) { PinElectrical.UNKNOWN },
                angleDeg = angle,
                argb = color(c["COLOR"]),
            )
        }

        // ---- sheet objects

        fun powerPort(r: AltiumRecord) = SchPowerPort(
            text = r["TEXT"]?.trim().orEmpty(),
            position = loc(r),
            angleDeg = r.int("ORIENTATION") * 90,
            style = POWER_STYLES.getOrElse(r.int("STYLE")) { PowerStyle.UNKNOWN },
            showName = r.bool("SHOWNETNAME"),
            argb = color(r["COLOR"]),
        )

        /** Port: a named box; both ends connect like net labels of that name. */
        fun port(r: AltiumRecord, labels: MutableList<SchNetLabel>, graphics: MutableList<SchGraphic>) {
            val name = r["NAME"]?.trim().orEmpty()
            val p = loc(r)
            val w = r.coordUm("WIDTH").takeIf { it > 0 } ?: (5 * GRID)
            val h = r.coordUm("HEIGHT").takeIf { it > 0 } ?: GRID
            val vertical = r.int("STYLE") in 4..7
            val end = if (vertical) Point(p.x, p.y - w) else Point(p.x + w, p.y)
            val box = if (vertical) Rect(p.x - h / 2, end.y, p.x + h / 2, p.y) else Rect(p.x, p.y - h / 2, end.x, p.y + h / 2)
            graphics += SchGraphic.Shape(rectPoints(Point(box.minX, box.minY), Point(box.maxX, box.maxY)), MIL10, color(r["COLOR"]), color(r["AREACOLOR"]))
            if (name.isEmpty()) return
            val font = fonts[r.int("FONTID", systemFont)] ?: fonts[systemFont]
            val t = SchText(
                name, box.center, font?.sizeUm ?: DEFAULT_EM, if (vertical) 90.0 else 0.0, HAlign.CENTER, VAlign.MIDDLE,
                color(r["TEXTCOLOR"]) ?: color(r["COLOR"]), font?.bold == true, font?.italic == true, font?.name,
            )
            labels += SchNetLabel(name, p, t)
            labels += SchNetLabel(name, end, t.copy(text = ""))
        }

        fun sheetSymbol(r: AltiumRecord, graphics: MutableList<SchGraphic>) {
            val p = loc(r)
            val w = r.coordUm("XSIZE"); val h = r.coordUm("YSIZE")
            graphics += SchGraphic.Shape(rectPoints(Point(p.x, p.y - h), Point(p.x + w, p.y)), lineWidth(r), color(r["COLOR"]), color(r["AREACOLOR"]))
        }

        fun textFrame(r: AltiumRecord, graphics: MutableList<SchGraphic>, texts: MutableList<SchText>) {
            val a = loc(r); val b = corner(r)
            val box = Rect.of(listOf(a, b))
            if (r.bool("ISSOLID") || r.bool("SHOWBORDER")) {
                graphics += SchGraphic.Shape(
                    rectPoints(a, b), MIL10, if (r.bool("SHOWBORDER")) color(r["COLOR"]) else null,
                    if (r.bool("ISSOLID")) color(r["AREACOLOR"]) else null,
                )
            }
            val font = fonts[r.int("FONTID", systemFont)] ?: fonts[systemFont]
            val em = font?.sizeUm ?: DEFAULT_EM
            val lines = (r["TEXT"] ?: "").split("~1")
            val margin = r.coordUm("TEXTMARGIN").takeIf { it > 0 } ?: (MIL10 * 5)
            val halign = when (r.int("ALIGNMENT", 1)) { 2 -> HAlign.CENTER; 3 -> HAlign.RIGHT; else -> HAlign.LEFT }
            val x = when (halign) { HAlign.LEFT -> box.minX + margin; HAlign.CENTER -> box.center.x; HAlign.RIGHT -> box.maxX - margin }
            lines.forEachIndexed { k, line ->
                if (line.isBlank()) return@forEachIndexed
                val y = box.maxY - margin - Math.round(em * 1.15 * (k + 1))
                texts += SchText(line, Point(x, y), em, 0.0, halign, VAlign.BOTTOM, color(r["TEXTCOLOR"]), font?.bold == true, font?.italic == true, font?.name)
            }
        }

        fun graphic(r: AltiumRecord): SchGraphic? {
            val c = color(r["COLOR"])
            val w = lineWidth(r)
            val fill = if (r.bool("ISSOLID") && !r.bool("TRANSPARENT")) color(r["AREACOLOR"]) else null
            return when (r.type) {
                13 -> SchGraphic.Poly(listOf(loc(r), corner(r)), w, c)
                6 -> points(r).takeIf { it.size >= 2 }?.let { SchGraphic.Poly(it, w, c) }
                37 -> SchGraphic.Poly(listOf(loc(r), corner(r)), w, c)
                7 -> points(r).takeIf { it.size >= 3 }?.let { SchGraphic.Shape(it, w, c, fill) }
                5 -> points(r).takeIf { it.size >= 2 }?.let { SchGraphic.Poly(bezier(it), w, c) }
                14 -> SchGraphic.Shape(rectPoints(loc(r), corner(r)), w, c, fill)
                10 -> SchGraphic.Shape(roundRect(loc(r), corner(r), r.coordUm("CORNERXRADIUS"), r.coordUm("CORNERYRADIUS")), w, c, fill)
                8 -> {
                    val rx = r.coordUm("RADIUS"); val ry = r.coordUm("SECONDARYRADIUS").takeIf { it > 0 } ?: rx
                    SchGraphic.Shape(ellipse(loc(r), rx, ry, 0.0, 360.0), w, c, fill)
                }
                12 -> {
                    val start = r.double("STARTANGLE") ?: 0.0
                    var end = r.double("ENDANGLE") ?: 0.0
                    if (end <= start) end += 360.0
                    SchGraphic.Arc(loc(r), r.coordUm("RADIUS"), start, end, w, c)
                }
                11 -> {
                    val start = r.double("STARTANGLE") ?: 0.0
                    var end = r.double("ENDANGLE") ?: 0.0
                    if (end <= start) end += 360.0
                    val rx = r.coordUm("RADIUS"); val ry = r.coordUm("SECONDARYRADIUS").takeIf { it > 0 } ?: rx
                    SchGraphic.Poly(ellipse(loc(r), rx, ry, start, end), w, c)
                }
                9 -> {
                    val start = r.double("STARTANGLE") ?: 0.0
                    var end = r.double("ENDANGLE") ?: 0.0
                    if (end <= start) end += 360.0
                    val center = loc(r)
                    val rad = r.coordUm("RADIUS")
                    SchGraphic.Shape(listOf(center) + ellipse(center, rad, rad, start, end), w, c, fill)
                }
                else -> null
            }
        }

        fun text(r: AltiumRecord, raw: String): SchText {
            val font = fonts[r.int("FONTID", systemFont)] ?: fonts[systemFont]
            val (plain, over) = overbar(raw)
            val just = r.int("JUSTIFICATION")
            var orient = r.int("ORIENTATION") and 3
            var h = when (just % 3) { 1 -> HAlign.CENTER; 2 -> HAlign.RIGHT; else -> HAlign.LEFT }
            var v = when (just / 3) { 1 -> VAlign.MIDDLE; 2 -> VAlign.TOP; else -> VAlign.BOTTOM }
            // Altium keeps text readable: 180° and 270° are drawn as 0°/90° with the anchor mirrored.
            if (orient >= 2) {
                orient -= 2
                h = when (h) { HAlign.LEFT -> HAlign.RIGHT; HAlign.RIGHT -> HAlign.LEFT; else -> h }
                v = when (v) { VAlign.BOTTOM -> VAlign.TOP; VAlign.TOP -> VAlign.BOTTOM; else -> v }
            }
            return SchText(
                text = plain, position = loc(r), sizeUm = font?.sizeUm ?: DEFAULT_EM,
                rotationDeg = orient * 90.0, halign = h, valign = v,
                argb = color(r["COLOR"]), bold = font?.bold == true, italic = font?.italic == true, fontName = font?.name,
                overline = over,
            )
        }

        // ---- geometry helpers

        fun loc(r: AltiumRecord) = Point(r.coordUm("LOCATION.X"), r.coordUm("LOCATION.Y"))
        fun corner(r: AltiumRecord) = Point(r.coordUm("CORNER.X"), r.coordUm("CORNER.Y"))

        fun points(r: AltiumRecord): List<Point> {
            val n = r.int("LOCATIONCOUNT")
            return (1..n).map { Point(r.coordUm("X$it"), r.coordUm("Y$it")) }
        }

        fun lineWidth(r: AltiumRecord): Long = when (r.int("LINEWIDTH")) {
            0 -> MIL10 / 4
            1 -> MIL10
            2 -> 3 * MIL10
            else -> 5 * MIL10
        }

        fun graphicPoints(g: SchGraphic): List<Point> = when (g) {
            is SchGraphic.Poly -> g.points
            is SchGraphic.Shape -> g.points
            is SchGraphic.Arc -> listOf(
                Point(g.center.x - g.radius, g.center.y - g.radius), Point(g.center.x + g.radius, g.center.y + g.radius),
            )
        }
    }

    // ---- shared helpers

    /** Altium colours are Windows COLORREF (0x00BBGGRR). */
    fun color(v: String?): Int? {
        val c = v?.trim()?.toLongOrNull() ?: return null
        val r = (c and 0xFF).toInt(); val g = ((c shr 8) and 0xFF).toInt(); val b = ((c shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** `R\S\T\` → "RST" with an overline on each character followed by a backslash. */
    fun overbar(raw: String): Pair<String, List<Boolean>?> {
        if ('\\' !in raw) return raw to null
        val sb = StringBuilder(); val over = ArrayList<Boolean>()
        for (ch in raw) {
            if (ch == '\\') { if (over.isNotEmpty()) over[over.size - 1] = true }
            else { sb.append(ch); over += false }
        }
        return sb.toString() to over
    }

    fun rectPoints(a: Point, b: Point): List<Point> {
        val r = Rect.of(listOf(a, b))
        return listOf(Point(r.minX, r.minY), Point(r.maxX, r.minY), Point(r.maxX, r.maxY), Point(r.minX, r.maxY))
    }

    fun roundRect(a: Point, b: Point, rx0: Long, ry0: Long): List<Point> {
        val r = Rect.of(listOf(a, b))
        val rx = rx0.coerceIn(0, r.width / 2); val ry = ry0.coerceIn(0, r.height / 2)
        if (rx == 0L || ry == 0L) return rectPoints(a, b)
        val out = ArrayList<Point>()
        out += ellipse(Point(r.maxX - rx, r.minY + ry), rx, ry, 270.0, 360.0, 6)
        out += ellipse(Point(r.maxX - rx, r.maxY - ry), rx, ry, 0.0, 90.0, 6)
        out += ellipse(Point(r.minX + rx, r.maxY - ry), rx, ry, 90.0, 180.0, 6)
        out += ellipse(Point(r.minX + rx, r.minY + ry), rx, ry, 180.0, 270.0, 6)
        return out
    }

    fun ellipse(c: Point, rx: Long, ry: Long, startDeg: Double, endDeg: Double, segments: Int = 0): List<Point> {
        val sweep = endDeg - startDeg
        val n = if (segments > 0) segments else max(8, (abs(sweep) / 6).toInt())
        return (0..n).map { k ->
            val a = (startDeg + sweep * k / n) * PI / 180
            Point(c.x + (rx * cos(a)).roundToLong(), c.y + (ry * sin(a)).roundToLong())
        }
    }

    /** Cubic Bézier chain: points 1-2-3-4, 4-5-6-7, … */
    fun bezier(p: List<Point>): List<Point> {
        if (p.size < 4) return p
        val out = ArrayList<Point>()
        var i = 0
        while (i + 3 < p.size) {
            val (a, b, c, d) = listOf(p[i], p[i + 1], p[i + 2], p[i + 3])
            for (k in 0..16) {
                if (k == 0 && out.isNotEmpty()) continue
                val t = k / 16.0; val u = 1 - t
                val x = u * u * u * a.x + 3 * u * u * t * b.x + 3 * u * t * t * c.x + t * t * t * d.x
                val y = u * u * u * a.y + 3 * u * u * t * b.y + 3 * u * t * t * c.y + t * t * t * d.y
                out += Point(x.roundToLong(), y.roundToLong())
            }
            i += 3
        }
        return out
    }

    /** One point (font size unit) in µm. */
    private const val PT_UM = 25400.0 / 72.0
    private const val MIL10 = 254L
    private const val GRID = 2540L
    private val DEFAULT_EM = Math.round(10 * PT_UM)

    /** Standard sheet sizes (10 mil units), by SHEETSTYLE. */
    private val SHEET_SIZES = listOf(
        1150 to 760, 1550 to 1110, 2230 to 1570, 3150 to 2230, 4460 to 3150, // A4…A0
        950 to 750, 1500 to 950, 2000 to 1500, 3200 to 2000, 4200 to 3200, // A…E
        1100 to 850, 1400 to 850, 1700 to 1100, // Letter, Legal, Tabloid
        990 to 790, 1540 to 990, 2060 to 1560, 3260 to 2060, 4280 to 3280, // OrCAD A…E
    )

    private val POWER_STYLES = listOf(
        PowerStyle.CIRCLE, PowerStyle.ARROW, PowerStyle.BAR, PowerStyle.WAVE,
        PowerStyle.GROUND_POWER, PowerStyle.GROUND_SIGNAL, PowerStyle.GROUND_EARTH,
        PowerStyle.GOST_ARROW, PowerStyle.GOST_GROUND, PowerStyle.GOST_EARTH, PowerStyle.GOST_BAR,
    )
}
