package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Fill
import com.pcbsolderview.model.Hole
import com.pcbsolderview.model.Layer
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.Net
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Polygon
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.model.StrokeFont
import com.pcbsolderview.model.BoardText
import com.pcbsolderview.model.Track
import com.pcbsolderview.model.Units
import com.pcbsolderview.model.Via
import com.pcbsolderview.model.arcPoints
import com.pcbsolderview.model.oriented
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.ipc2581.Ipc2581Parser
import com.pcbsolderview.parser.text.fixMojibake
import com.pcbsolderview.parser.text.looksLikePartNumber
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Altium's native board (.PcbDoc, "PCB 6.0 Binary File") → [Board], with the same conventions as
 * [Ipc2581Parser] (µm, Y up, through-hole pads once per copper layer, layer names as Altium's).
 *
 * Container: an OLE compound file ([CompoundFile]). Text streams (`|KEY=value|` records):
 * `Board6` (outline, layer stack), `Components6`, `Nets6`, `Polygons6`. Binary streams, one record
 * per primitive = `u8 type` + sub-records (`u32 length` + payload):
 *
 * | type | stream | sub-records | main payload after the common header |
 * |---|---|---|---|
 * | 1 arc | Arcs6 | 1 | centre x, y, radius (i32), start, end angle (f64, degrees CCW), width (i32) |
 * | 2 pad | Pads6 | 5 or 6 | (1) name: u8 len + text; (5) x, y, size top/mid/bottom x, y, hole (i32), shape top/mid/bottom (u8: 1 round, 2 rect, 3 octagon), rotation (f64), plated (u8); (6) per-layer: alt shape at 532 + i (9 = rounded rect), corner radius % at 564 + i (i = 0 top … 31 bottom) |
 * | 3 via | Vias6 | 1 | x, y, diameter, hole (i32), start layer, end layer (u8) |
 * | 4 track | Tracks6 | 1 | start x, y, end x, y, width (i32) |
 * | 5 text | Texts6 | 2 | x, y, height (i32), font (u16), rotation (f64), mirrored (u8), stroke width (i32); byte 40 comment flag, 41 designator flag; sub-record 2 = u8 length + text. Drawn with [StrokeFont] as tracks; a component's designator / comment only when NAMEON / COMMENTON; `.Designator`-style templates are skipped |
 * | 6 fill | Fills6 | 1 | x1, y1, x2, y2 (i32), rotation (f64) — unverified (no sample has fills) |
 * | 11 region | Regions6 | 1 | u8, u16 hole count, u16, u32 len + properties (KIND, V7_LAYER), u32 n + n × (f64 x, f64 y), then per hole u32 n + points |
 * | 12 body | ComponentBodies6 | 1 | same as a region; properties carry OVERALLHEIGHT |
 *
 * Common header: `u8 layer, u8 flags1, u8 flags2 (2 = keep-out), u16 net, u16 polygon,
 * u16 component, 4 bytes`. Indices count records of Nets6 / Polygons6 / Components6 from 0;
 * 0xFFFF = none. Binary lengths are i32 in 1/10000 mil ([UNIT_UM] µm); region vertices are f64 in
 * the same unit. Coordinates are absolute, Y up — the frame of Altium's IPC-2581 export.
 *
 * Sub-record lengths grow between Altium versions: only leading fields are read and every
 * sub-record is skipped by its declared length. A damaged primitive is skipped and counted.
 */
object PcbDocParser {
    /** One internal unit (1/10000 mil) in µm. */
    const val UNIT_UM = 0.00254

    private const val NONE = 0xFFFF
    private const val LAYER_TOP = 1
    private const val LAYER_BOTTOM = 32
    private const val LAYER_MULTI = 74

    fun parse(
        bytes: ByteArray,
        fileName: String,
        onNotice: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Board {
        val cf = try {
            CompoundFile(bytes)
        } catch (e: Exception) {
            throw BoardParseException("$fileName is not a readable .PcbDoc (${e.message ?: e.javaClass.simpleName}).", e)
        }
        fun text(stream: String): List<AltiumRecord> = cf.read(stream)?.let { AltiumRecords.fromStream(it) }.orEmpty()

        val boardRec = text("Board6/Data").firstOrNull()
            ?: throw BoardParseException("$fileName has no board data (Board6); it may not be an Altium PCB document.")
        val netNames = text("Nets6/Data").map { it["NAME"]?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val compRecs = text("Components6/Data")
        val polygonNets = text("Polygons6/Data").map { p -> p["NET"]?.trim()?.toIntOrNull() }
        onProgress(0.1f)

        val b = Builder(fileName, boardRec, netNames, compRecs, polygonNets)
        val streams = listOf("Pads6/Data", "Vias6/Data", "Tracks6/Data", "Arcs6/Data", "Fills6/Data", "Regions6/Data", "ComponentBodies6/Data", "Texts6/Data")
        val sizes = streams.map { cf.read(it)?.size ?: 0 }
        val total = sizes.sum().coerceAtLeast(1)
        var done = 0
        streams.forEachIndexed { i, s ->
            cf.read(s)?.let { b.readStream(s, it) }
            done += sizes[i]
            onProgress(0.1f + 0.85f * done / total)
        }
        val board = b.build(baseName(fileName))
        if (b.skipped > 0) onNotice("$fileName: ${b.skipped} damaged or unknown item${if (b.skipped == 1) " was" else "s were"} skipped.")
        onProgress(1f)
        return board
    }

    /** "4488.189mil", "1.5mm", "0" → µm; null when unreadable. */
    fun lengthUm(s: String?): Long? {
        val t = s?.trim()?.lowercase() ?: return null
        val (num, unit) = when {
            t.endsWith("mil") -> t.removeSuffix("mil") to Units.MIL
            t.endsWith("mm") -> t.removeSuffix("mm") to Units.MILLIMETER
            t.endsWith("in") -> t.removeSuffix("in") to Units.INCH
            else -> t to Units.MIL
        }
        return num.trim().toDoubleOrNull()?.let { unit.toMicrons(it) }
    }

    /** Altium's legacy layer ids (u8 in binary records) → layer name, type and side. */
    fun layerInfo(id: Int): Triple<String, LayerType, Side?> = when (id) {
        LAYER_TOP -> Triple("Top Layer", LayerType.COPPER, Side.TOP)
        in 2..31 -> Triple("Mid-Layer ${id - 1}", LayerType.COPPER, null)
        LAYER_BOTTOM -> Triple("Bottom Layer", LayerType.COPPER, Side.BOTTOM)
        33 -> Triple("Top Overlay", LayerType.SILKSCREEN, Side.TOP)
        34 -> Triple("Bottom Overlay", LayerType.SILKSCREEN, Side.BOTTOM)
        35 -> Triple("Top Paste", LayerType.PASTE, Side.TOP)
        36 -> Triple("Bottom Paste", LayerType.PASTE, Side.BOTTOM)
        37 -> Triple("Top Solder", LayerType.SOLDER_MASK, Side.TOP)
        38 -> Triple("Bottom Solder", LayerType.SOLDER_MASK, Side.BOTTOM)
        in 39..54 -> Triple("Internal Plane ${id - 38}", LayerType.COPPER, null)
        55 -> Triple("Drill Guide", LayerType.DRILL, null)
        56 -> Triple("Keep-Out Layer", LayerType.OTHER, null)
        in 57..72 -> Triple("Mechanical ${id - 56}", LayerType.OTHER, null)
        73 -> Triple("Drill Drawing", LayerType.DRILL, null)
        else -> Triple("Layer $id", LayerType.OTHER, null)
    }

    private fun isCopperId(id: Int) = id in 1..32 || id in 39..54

    private fun baseName(n: String) = n.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

    private fun um(v: Int): Long = Math.round(v * UNIT_UM)
    private fun um(v: Double): Long = Math.round(v * UNIT_UM)

    /** Common header of every primitive. */
    private class Header(val layer: Int, val keepout: Boolean, val net: Int, val polygon: Int, val component: Int)

    private fun BinaryReader.header(): Header {
        val layer = u8()
        u8() // flags1
        val flags2 = u8()
        val net = u16()
        val polygon = u16()
        val component = u16()
        skip(4)
        return Header(layer, flags2 == 2, net, polygon, component)
    }

    private class RawPad(
        val name: String, val layer: Int, val net: Int, val component: Int, val x: Long, val y: Long,
        /** Sizes and shapes of top, mid and bottom. */
        val sizes: List<Pair<Long, Long>>, val shapes: List<Int>, val hole: Long, val rotation: Double, val plated: Boolean,
        /** Per copper layer (0 top … 31 bottom): alternative shape and corner radius %, when present. */
        val altShapes: IntArray?, val cornerPct: IntArray?,
    )

    private class Body(val component: Int, val outline: List<Point>, val heightUm: Long?)

    private class RawText(
        val layer: Int, val component: Int, val isComment: Boolean, val isDesignator: Boolean,
        val x: Long, val y: Long, val height: Long, val rotation: Double, val mirrored: Boolean, val stroke: Long, val text: String,
    )

    private class Builder(
        val fileName: String,
        val boardRec: AltiumRecord,
        val netNames: List<String?>,
        val compRecs: List<AltiumRecord>,
        val polygonNets: List<Int?>,
    ) {
        var skipped = 0
        val usedLayers = LinkedHashSet<Int>()
        val rawPads = ArrayList<RawPad>()
        val vias = ArrayList<Via>()
        val tracks = ArrayList<Track>()
        val fills = ArrayList<Fill>()
        val bodies = ArrayList<Body>()
        /** Component index → its designator and comment texts (the silkscreen strings). */
        val designatorText = HashMap<Int, String>()
        val commentText = HashMap<Int, String>()
        val texts = ArrayList<RawText>()
        val holes = LinkedHashMap<Triple<Long, Long, Long>, Hole>()

        fun net(i: Int): String? = if (i == NONE) null else netNames.getOrNull(i)
        fun layerName(id: Int): String { usedLayers += id; return layerInfo(id).first }

        fun addHole(x: Long, y: Long, d: Long, plated: Boolean) {
            if (d > 0) holes.putIfAbsent(Triple(x, y, d), Hole(Point(x, y), d, plated))
        }

        fun readStream(stream: String, data: ByteArray) {
            val r = BinaryReader(data, stream)
            while (!r.atEnd) {
                val type = r.u8()
                // Number of sub-records per type; anything else is assumed to have one (skipped).
                val subs = when (type) { 2 -> 6; 5 -> 2; else -> 1 }
                val parts = ArrayList<BinaryReader>(subs)
                try {
                    repeat(subs) { i ->
                        // The 6th pad sub-record is empty when unused; tolerate a pad cut short at the stream end.
                        if (type == 2 && i == 5 && r.remaining < 4) return@repeat
                        parts += r.sub()
                    }
                } catch (e: BoardParseException) {
                    skipped++ // framing is broken: the rest of this stream cannot be trusted
                    return
                }
                try {
                    when (type) {
                        1 -> arc(parts[0])
                        2 -> pad(parts)
                        3 -> via(parts[0])
                        4 -> track(parts[0])
                        6 -> fill(parts[0])
                        11 -> region(parts[0])
                        12 -> body(parts[0])
                        5 -> text(parts)
                        else -> skipped++
                    }
                } catch (e: BoardParseException) {
                    skipped++
                }
            }
        }

        /**
         * Texts (verified on the RP2040 file): after the header x, y, height (i32), font (u16),
         * rotation (f64), mirrored (u8), stroke width (i32); byte 40 of the first sub-record =
         * comment flag, byte 41 = designator flag; the string is sub-record 2, u8 length + text.
         * Special strings such as ".Designator" are templates (assembly drawings), not values.
         * A component's designator and comment also name it and give its value.
         */
        fun text(parts: List<BinaryReader>) {
            val s = parts[0]
            val h = s.header()
            if (h.keepout || s.remaining < 42 - 13) return
            val x = um(s.i32()); val y = um(s.i32()); val height = um(s.i32())
            s.u16() // font: 0/1 stroke, 2 TrueType, 3 barcode; all drawn with the stroke font
            val rotation = s.f64()
            val mirrored = s.u8() != 0
            val stroke = um(s.i32())
            val isComment = s.u8() != 0
            val isDesignator = s.u8() != 0
            val t = parts.getOrNull(1)?.takeIf { it.remaining > 0 } ?: return
            // Line breaks in multi-line texts: drawn as one line.
            val str = fixMojibake(t.text(minOf(t.u8(), t.remaining)).replace("\r\n", " ").replace('\n', ' ').trim())
            if (str.isEmpty() || str.startsWith(".")) return
            if (h.component != NONE && isDesignator) designatorText.putIfAbsent(h.component, str)
            if (h.component != NONE && isComment) commentText.putIfAbsent(h.component, str)
            texts += RawText(h.layer, h.component, isComment, isDesignator, x, y, height, rotation, mirrored, stroke, str)
        }

        /**
         * Visible texts as [BoardText] and stroke-font tracks. A component's designator and comment
         * are printed only when the component shows them (NAMEON / COMMENTON).
         */
        fun textGeometry(): Pair<List<BoardText>, List<Track>> {
            val boardTexts = ArrayList<BoardText>()
            val strokes = ArrayList<Track>()
            for (t in texts) {
                if (t.component != NONE) {
                    val c = compRecs.getOrNull(t.component)
                    if (t.isDesignator && c?.get("NAMEON") != null && !c.bool("NAMEON")) continue
                    if (t.isComment && c?.get("COMMENTON") != null && !c.bool("COMMENTON")) continue
                }
                val layer = layerName(t.layer)
                boardTexts += BoardText(layer, t.text, Point(t.x, t.y), t.height, t.rotation, t.mirrored)
                val width = t.stroke.coerceAtLeast(1)
                for (line in StrokeFont.strokes(t.text, t.x, t.y, t.height, width, t.rotation, t.mirrored)) {
                    for (i in 0 until line.size - 1) strokes += Track(layer, line[i], line[i + 1], width, null)
                }
            }
            return boardTexts to strokes
        }

        fun track(s: BinaryReader) {
            val h = s.header()
            if (h.keepout) return
            val start = Point(um(s.i32()), um(s.i32()))
            val end = Point(um(s.i32()), um(s.i32()))
            val width = um(s.i32())
            tracks += Track(layerName(h.layer), start, end, width, net(h.net))
        }

        fun arc(s: BinaryReader) {
            val h = s.header()
            if (h.keepout) return
            val cx = s.i32().toDouble(); val cy = s.i32().toDouble(); val r = s.i32().toDouble()
            val sa = Math.toRadians(s.f64()); val ea = Math.toRadians(s.f64())
            val width = um(s.i32())
            val start = Point(um(cx + r * cos(sa)), um(cy + r * sin(sa)))
            // A full circle (0 → 360) has start == end, which arcPoints draws as a circle.
            val full = Math.abs(Math.toDegrees(ea - sa)).let { it >= 359.999 || it < 1e-9 }
            val end = if (full) start else Point(um(cx + r * cos(ea)), um(cy + r * sin(ea)))
            tracks += Track(layerName(h.layer), start, end, width, net(h.net), arcCenter = Point(um(cx), um(cy)), clockwise = false)
        }

        fun via(s: BinaryReader) {
            val h = s.header()
            val x = um(s.i32()); val y = um(s.i32())
            val d = um(s.i32()); val hole = um(s.i32())
            vias += Via(Point(x, y), maxOf(d, hole), hole, net(h.net))
            addHole(x, y, hole, true)
        }

        fun fill(s: BinaryReader) {
            val h = s.header()
            if (h.keepout) return
            val x1 = s.i32().toDouble(); val y1 = s.i32().toDouble(); val x2 = s.i32().toDouble(); val y2 = s.i32().toDouble()
            val rot = if (s.remaining >= 8) Math.toRadians(s.f64()) else 0.0
            val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2
            val pts = listOf(x1 to y1, x2 to y1, x2 to y2, x1 to y2).map { (x, y) ->
                val dx = x - cx; val dy = y - cy
                Point(um(cx + dx * cos(rot) - dy * sin(rot)), um(cy + dx * sin(rot) + dy * cos(rot)))
            }
            fills += Fill(layerName(h.layer), Polygon(pts.oriented(true)), net(h.net))
        }

        /** Shared by regions and component bodies: header, properties, outline and holes. */
        private fun shape(s: BinaryReader): Triple<Header, AltiumRecord, Pair<List<Point>, List<List<Point>>>> {
            val h = s.header()
            s.u8()
            val holeCount = s.u16()
            s.skip(2)
            val props = AltiumRecord.parse(s.text(s.i32()))
            fun ring(): List<Point> {
                val n = s.i32()
                if (n < 0 || n.toLong() * 16 > s.remaining) s.fail("bad vertex count $n")
                val pts = ArrayList<Point>(n)
                repeat(n) { pts += Point(um(s.f64()), um(s.f64())) }
                if (pts.size > 1 && pts.first() == pts.last()) pts.removeAt(pts.lastIndex)
                return pts
            }
            val outline = ring()
            val holes = (0 until holeCount).map { ring() }
            return Triple(h, props, outline to holes)
        }

        fun region(s: BinaryReader) {
            val (h, props, geo) = shape(s)
            // KIND 0 = copper/graphics; 1 polygon cut-out, 2 dashed outline, 4 cavity, 5 board cut-out.
            if (h.keepout || props.int("KIND", 0) != 0) return
            val (outline, holes) = geo
            if (outline.size < 3) return
            val net = net(h.net) ?: polygonNets.getOrNull(h.polygon.takeIf { it != NONE } ?: -1)?.let { net(it) }
            fills += Fill(layerName(h.layer), Polygon(outline.oriented(true), holes.filter { it.size >= 3 }.map { it.oriented(false) }), net)
        }

        fun body(s: BinaryReader) {
            val (h, props, geo) = shape(s)
            if (h.component == NONE) return
            bodies += Body(h.component, geo.first, lengthUm(props["OVERALLHEIGHT"])?.takeIf { it > 0 })
        }

        fun pad(parts: List<BinaryReader>) {
            if (parts.size < 5) throw BoardParseException("pad with ${parts.size} sub-records")
            val n = parts[0]
            val name = if (n.remaining > 0) n.text(minOf(n.u8(), n.remaining)) else ""
            val g = parts[4]
            val h = g.header()
            val x = um(g.i32()); val y = um(g.i32())
            val sizes = List(3) { um(g.i32()) to um(g.i32()) }
            val hole = um(g.i32())
            val shapes = List(3) { g.u8() }
            val rotation = g.f64()
            val plated = g.u8() != 0
            var alt: IntArray? = null
            var corner: IntArray? = null
            parts.getOrNull(5)?.let { s6 ->
                if (s6.remaining >= 596) {
                    s6.skip(532)
                    alt = IntArray(32) { s6.u8() }
                    corner = IntArray(32) { s6.u8() }
                }
            }
            rawPads += RawPad(name, h.layer, h.net, h.component, x, y, sizes, shapes, hole, rotation, plated, alt, corner)
            if (hole > 0) addHole(x, y, hole, plated)
        }

        /** Copper layers in stack order (top first), from Board6's V9 stack; Top and Bottom at least. */
        fun copperStack(): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            var i = 0
            while (true) {
                val id = boardRec["V9_STACK_LAYER${i}_LAYERID"]?.trim()?.toLongOrNull() ?: break
                val stackName = boardRec["V9_STACK_LAYER${i}_NAME"]?.trim()
                val group = (id ushr 16).toInt(); val low = (id and 0xFFFF).toInt()
                val legacy = when {
                    group == 0x0100 && low == 0xFFFF -> LAYER_BOTTOM
                    group == 0x0100 && low in 1..31 -> low
                    group == 0x0101 && low in 1..16 -> 38 + low // internal planes (unverified)
                    else -> null
                }
                if (legacy != null) out += legacy to (stackName?.takeIf { it.isNotEmpty() && legacy != LAYER_TOP && legacy != LAYER_BOTTOM } ?: layerInfo(legacy).first)
                i++
            }
            if (out.none { it.first == LAYER_TOP }) out.add(0, LAYER_TOP to layerInfo(LAYER_TOP).first)
            if (out.none { it.first == LAYER_BOTTOM }) out += LAYER_BOTTOM to layerInfo(LAYER_BOTTOM).first
            return out
        }

        fun outline(): List<Polygon> {
            val pts = ArrayList<Point>()
            fun v(k: String, i: Int) = lengthUm(boardRec["$k$i"])
            var i = 0
            val n = generateSequence(0) { it + 1 }.takeWhile { boardRec["VX$it"] != null }.count()
            while (i < n) {
                val x = v("VX", i) ?: break; val y = v("VY", i) ?: break
                pts += Point(x, y)
                if (boardRec.int("KIND$i") == 1 && i + 1 < n) {
                    val nx = v("VX", i + 1) ?: 0; val ny = v("VY", i + 1) ?: 0
                    val cx = (v("CX", i) ?: 0).toDouble(); val cy = (v("CY", i) ?: 0).toDouble()
                    val r = (v("R", i) ?: 0).toDouble()
                    val sa = Math.toRadians(boardRec.double("SA$i") ?: 0.0)
                    // Altium stores the arc as start/end angles; it runs CCW from SA when SA is at this vertex.
                    val atSa = hypot(cx + r * cos(sa) - x, cy + r * sin(sa) - y)
                    val ea = Math.toRadians(boardRec.double("EA$i") ?: 0.0)
                    val atEa = hypot(cx + r * cos(ea) - x, cy + r * sin(ea) - y)
                    val cw = atEa < atSa
                    arcPoints(x.toDouble(), y.toDouble(), nx.toDouble(), ny.toDouble(), cx, cy, cw).dropLast(1)
                        .forEach { (ax, ay) -> pts += Point(Math.round(ax), Math.round(ay)) }
                }
                i++
            }
            if (pts.size > 1 && pts.first() == pts.last()) pts.removeAt(pts.lastIndex)
            return if (pts.size >= 3) listOf(Polygon(pts.oriented(true))) else emptyList()
        }

        fun build(name: String): Board {
            val (boardTexts, textStrokes) = textGeometry()
            val copper = copperStack()
            val copperName = copper.toMap()
            fun copperLayerName(id: Int): String { usedLayers += id; return copperName[id] ?: layerInfo(id).first }

            // Components first (pads need their designators and sides).
            // The designator printed on the board, else the schematic one. Duplicates (Altium allows them)
            // get a suffix, because the app finds parts by designator: "RP2040", "RP2040_2".
            val seen = HashMap<String, Int>()
            val refs = compRecs.mapIndexed { i, c ->
                val base = designatorText[i] ?: c["SOURCEDESIGNATOR"]?.trim()?.takeIf { it.isNotEmpty() } ?: "?${i + 1}"
                val n = (seen[base.uppercase()] ?: 0) + 1
                seen[base.uppercase()] = n
                if (n == 1) base else "${base}_$n"
            }
            val compSide = compRecs.map { if (it["LAYER"]?.trim().equals("BOTTOM", true)) Side.BOTTOM else Side.TOP }

            val pads = ArrayList<Pad>()
            val padsByComp = HashMap<Int, MutableList<Pad>>()
            for (rp in rawPads) {
                val layers = when {
                    rp.layer == LAYER_MULTI -> copper.map { it.first }
                    isCopperId(rp.layer) -> listOf(rp.layer)
                    else -> emptyList() // paste/mask-only pads are not copper
                }
                val comp = rp.component.takeIf { it != NONE }?.let { refs.getOrNull(it) }
                for (id in layers) {
                    val which = when (id) { LAYER_TOP -> 0; LAYER_BOTTOM -> 2; else -> 1 }
                    val (w, h) = rp.sizes[which]
                    if (w <= 0 || h <= 0) continue
                    val layerIndex = when (id) { LAYER_TOP -> 0; LAYER_BOTTOM -> 31; in 2..31 -> id - 1; else -> 1 }
                    val alt = rp.altShapes?.get(layerIndex) ?: 0
                    val pct = rp.cornerPct?.get(layerIndex) ?: 0
                    val side = layerInfo(id).third ?: rp.component.takeIf { it != NONE }?.let { compSide.getOrNull(it) } ?: Side.TOP
                    pads += makePad(
                        pads.size, rp, w, h, rp.shapes[which], alt, pct, copperLayerName(id), side, comp,
                    )
                    if (rp.component != NONE) padsByComp.getOrPut(rp.component) { ArrayList() } += pads.last()
                }
            }

            val bodiesByComp = bodies.groupBy { it.component }
            val components = compRecs.mapIndexed { i, c ->
                val ref = refs[i]
                val position = Point(lengthUm(c["X"]) ?: 0, lengthUm(c["Y"]) ?: 0)
                val compPads = padsByComp[i].orEmpty()
                val compBodies = bodiesByComp[i].orEmpty()
                val body = compBodies.filter { it.outline.size >= 3 }.maxByOrNull { Rect.of(it.outline).area }
                val bodyBox = compBodies.filter { it.outline.isNotEmpty() }.map { Rect.of(it.outline) }.reduceOrNull { a, b -> a.union(b) }
                val padBox = compPads.map { it.bounds }.reduceOrNull { a, b -> a.union(b) }
                // Altium double-encodes some texts ("Â±5%"), as in its IPC-2581 export.
                val description = (c["SOURCEDESCRIPTION"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: c["FOOTPRINTDESCRIPTION"]?.trim()?.takeIf { it.isNotEmpty() })?.let { fixMojibake(it) }
                val libName = c["SOURCELIBREFERENCE"]?.trim()?.takeIf { it.isNotEmpty() }
                val lib = c["SOURCECOMPONENTLIBRARY"]?.trim()?.takeIf { it.isNotEmpty() }
                val pnFromLib = looksLikePartNumber(libName)
                Component(
                    refDes = ref,
                    // The comment text is the value shown in Altium ("100nF"); else derived like IPC-2581.
                    value = commentText[i] ?: Ipc2581Parser.deriveValue(libName, description),
                    description = description ?: libName,
                    footprint = c["PATTERN"]?.trim()?.takeIf { it.isNotEmpty() },
                    partNumber = libName?.takeIf { pnFromLib },
                    partNumberFromLibrary = pnFromLib,
                    libraryRef = libName?.let { if (lib != null) "$lib : $it" else it },
                    heightUm = compBodies.mapNotNull { it.heightUm }.maxOrNull(),
                    side = compSide[i],
                    position = position,
                    rotationDeg = normalizeDeg(c.double("ROTATION") ?: 0.0),
                    bounds = listOfNotNull(bodyBox, padBox).reduceOrNull { a, b -> a.union(b) } ?: Rect.around(position, 1000, 1000),
                    outline = body?.outline,
                    padIds = compPads.map { it.id },
                )
            }

            val nets = pads.filter { it.net != null }.groupBy { it.net!! }.mapValues { (n, ps) -> Net(n, ps.map { it.id }) }

            // Layer list: every copper layer in stack order, plus the other layers primitives use,
            // arranged top to bottom (overlay, paste, solder, copper…, solder, paste, overlay, others).
            copper.forEach { usedLayers += it.first }
            val topSide = listOf(33, 35, 37).filter { it in usedLayers }
            val bottomSide = listOf(38, 36, 34).filter { it in usedLayers }
            val rest = usedLayers.filter { !isCopperId(it) && it !in topSide && it !in bottomSide && it != LAYER_MULTI }.sorted()
            val ordered = topSide.map { it to layerInfo(it).first } + copper + bottomSide.map { it to layerInfo(it).first } + rest.map { it to layerInfo(it).first }
            val layers = ordered.mapIndexed { order, (id, n) ->
                val (_, type, side) = layerInfo(id)
                Layer(n, type, side, order)
            }

            return Board(
                name = name,
                source = SourceFormat.PCBDOC,
                outline = outline(),
                layers = layers,
                tracks = tracks + textStrokes,
                vias = vias,
                pads = pads,
                holes = holes.values.toList(),
                fills = fills,
                texts = boardTexts,
                components = components,
                nets = nets,
                units = Units.MIL,
            )
        }

        private fun makePad(
            id: Int, rp: RawPad, w: Long, h: Long, shape: Int, alt: Int, pct: Int,
            layer: String, side: Side, comp: String?,
        ): Pad {
            val center = Point(rp.x, rp.y)
            val drill = rp.hole.takeIf { it > 0 }
            val net = net(rp.net)
            val rot = normalizeDeg(rp.rotation)
            fun pad(s: PadShape, radius: Long = 0, poly: List<Point>? = null) =
                Pad(id, rp.name, center, s, w, h, rot, drill, net, layer, side, comp, polygon = poly, cornerRadius = radius)
            return when {
                // Rounded rectangle: corner radius as a percentage of half the shorter side.
                alt == 9 && pct in 1..99 -> pad(PadShape.ROUNDED_RECT, Math.round(minOf(w, h) / 2.0 * pct / 100.0))
                alt == 9 && pct >= 100 -> pad(if (w == h) PadShape.ROUND else PadShape.OVAL)
                shape == 1 -> pad(if (w == h) PadShape.ROUND else PadShape.OVAL)
                shape == 3 -> pad(PadShape.POLYGON, poly = octagon(center, w, h, rot))
                else -> pad(PadShape.RECT)
            }
        }

        /** Altium's octagon: corners cut at 45° by a quarter of the shorter side. */
        private fun octagon(c: Point, w: Long, h: Long, rotDeg: Double): List<Point> {
            val hw = w / 2.0; val hh = h / 2.0; val k = minOf(w, h) / 4.0
            val local = listOf(
                hw to hh - k, hw - k to hh, -hw + k to hh, -hw to hh - k,
                -hw to -hh + k, -hw + k to -hh, hw - k to -hh, hw to -hh + k,
            )
            val r = Math.toRadians(rotDeg)
            return local.map { (x, y) -> Point(c.x + Math.round(x * cos(r) - y * sin(r)), c.y + Math.round(x * sin(r) + y * cos(r))) }
        }

        private fun normalizeDeg(a: Double) = ((a % 360.0) + 360.0) % 360.0
    }
}
