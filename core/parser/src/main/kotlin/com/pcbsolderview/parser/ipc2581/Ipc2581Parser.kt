package com.pcbsolderview.parser.ipc2581

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
import com.pcbsolderview.model.Track
import com.pcbsolderview.model.Units
import com.pcbsolderview.model.Via
import com.pcbsolderview.model.arcPoints
import com.pcbsolderview.model.oriented
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.xml.XmlParserFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming IPC-2581 (revisions B and C) reader. Single pass with XmlPullParser; raw records are
 * resolved into a [Board] at the end. Everything is converted to micrometres.
 *
 * Handles Altium's dialect: pads in Step/PadStack/LayerPad (with PinRef), copper and legend in
 * LayerFeature/Set/Features (Line, Arc, Polyline, Contour with Cutout, wrapped or not in
 * UserSpecial), holes in PadStack/LayerHole and LayerFeature/Set/Hole, BOM without values.
 */
class Ipc2581Parser(private val xml: XmlParserFactory) {

    fun parse(input: InputStream, fileName: String, totalBytes: Long = -1, onProgress: (Float) -> Unit = {}): Board {
        val counting = CountingStream(input)
        val p = xml.create(counting)
        val s = State()
        try {
            var events = 0
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> { s.stack.add(p.name); s.start(p) }
                    XmlPullParser.END_TAG -> { s.end(p.name); s.stack.removeAt(s.stack.lastIndex) }
                    XmlPullParser.END_DOCUMENT -> break
                }
                if (totalBytes > 0 && ++events % 4096 == 0) onProgress((counting.count.toFloat() / totalBytes).coerceIn(0f, 1f))
            }
        } catch (e: XmlPullParserException) {
            throw BoardParseException("The IPC-2581 file is damaged or incomplete (${e.message}).", e)
        }
        if (!s.sawRoot) throw BoardParseException("Not an IPC-2581 file (no <IPC-2581> root element).")
        onProgress(1f)
        return s.build(fileName.substringAfterLast('/').substringBeforeLast('.'))
    }

    // ---------------------------------------------------------------- raw records

    private sealed interface Prim {
        data class Circle(val d: Double) : Prim
        data class Rect(val w: Double, val h: Double, val r: Double = 0.0) : Prim
        data class Oval(val w: Double, val h: Double) : Prim
        data class Poly(val pts: List<Pair<Double, Double>>) : Prim
    }

    private class Xf(val rot: Double = 0.0, val mirror: Boolean? = null, val dx: Double = 0.0, val dy: Double = 0.0, val scale: Double = 1.0)

    private class PinRef(val comp: String, val pin: String)

    private class RawPad(val layer: String, val xf: Xf?, val loc: Pair<Double, Double>?, val prim: String?, val pin: PinRef?, val net: String?)

    private class RawHole(val x: Double, val y: Double, val d: Double, val plating: String, val pin: PinRef?)

    private class RawPadStack(val net: String?, val holes: List<RawHole>, val pads: List<RawPad>)

    private class RawPackage(val name: String, val outline: List<Pair<Double, Double>>, val pins: List<Triple<String, Pair<Double, Double>, String?>>)

    private class RawComponent(val ref: String, val pkg: String?, val layer: String?, val part: String?, val xf: Xf?, val loc: Pair<Double, Double>?)

    private class RawBom(val libRef: String?, val description: String?, val partNumber: String?, val manufacturer: String?, val refs: List<String>)

    private class RawLayer(val name: String, val function: String?, val side: String?)

    private class LineStyle(val width: Double)

    // ---------------------------------------------------------------- state machine

    private inner class State {
        val stack = ArrayList<String>()
        var sawRoot = false
        var cadUnits = Units.MILLIMETER
        var units = Units.MILLIMETER
        var stepName: String? = null
        var stepCount = 0

        val prims = HashMap<String, Prim>()
        val lineDescs = HashMap<String, LineStyle>()
        val colors = HashMap<String, Int>()
        val layers = ArrayList<RawLayer>()
        val stackOrder = HashMap<String, Int>()
        val packages = HashMap<String, RawPackage>()
        val components = ArrayList<RawComponent>()
        val padStacks = ArrayList<RawPadStack>()
        val boms = ArrayList<RawBom>()
        val logicalNets = HashMap<Pair<String, String>, String>()
        val outline = ArrayList<Polygon>()
        val tracks = ArrayList<Track>()
        val fills = ArrayList<Fill>()
        val setPads = ArrayList<RawPad>()
        val setHoles = ArrayList<RawHole>()
        val layerColorRef = HashMap<String, String>()

        // pending element data
        var entryId: String? = null
        var pendXf: Xf? = null
        var pendLoc: Pair<Double, Double>? = null
        var pendPrim: String? = null
        var pendPin: PinRef? = null
        var pendLineWidth: Double? = null
        var lineAttrs: DoubleArray? = null
        var lineClockwise = false
        var poly = ArrayList<Pair<Double, Double>>()
        var contourOuter: List<Point>? = null
        val contourHoles = ArrayList<List<Point>>()
        var pkgName: String? = null
        var pkgOutline: List<Pair<Double, Double>> = emptyList()
        val pkgPins = ArrayList<Triple<String, Pair<Double, Double>, String?>>()
        var pinNumber: String? = null
        var compAttrs: Array<String?>? = null
        var psNet: String? = null
        val psHoles = ArrayList<RawHole>()
        val psPads = ArrayList<RawPad>()
        var holeAttrs: RawHole? = null
        var layerPadLayer: String? = null
        var featureLayer: String? = null
        var setNet: String? = null
        var bomAttrs: Array<String?>? = null
        val bomRefs = ArrayList<String>()
        val bomChars = HashMap<String, String>()
        var logicalNet: String? = null

        fun parent(n: Int = 1): String? = stack.getOrNull(stack.size - 1 - n)
        fun inside(name: String) = stack.contains(name)
        /** Only the first Step is read (panels repeat the board). */
        fun skipStep() = stepCount > 1 && inside("Step")

        fun XmlPullParser.a(n: String): String? = getAttributeValue(null, n)
        fun XmlPullParser.d(n: String): Double? = a(n)?.trim()?.toDoubleOrNull()
        fun XmlPullParser.um(n: String): Double? = d(n)?.let { it * units.micronsPerUnit }

        fun start(p: XmlPullParser) {
            if (skipStep()) return
            when (p.name) {
                "IPC-2581" -> sawRoot = true
                "DictionaryStandard", "DictionaryUser", "DictionaryLineDesc", "DictionaryFont", "DictionaryFirmware" ->
                    units = parseUnits(p.a("units")) ?: cadUnits
                "CadHeader" -> { cadUnits = parseUnits(p.a("units")) ?: cadUnits; units = cadUnits }
                "EntryStandard", "EntryUser", "EntryLineDesc", "EntryColor" -> entryId = p.a("id")
                "Color" -> if (parent() == "EntryColor") entryId?.let { id ->
                    colors[id] = ((p.d("r") ?: 0.0).toInt() shl 16) or ((p.d("g") ?: 0.0).toInt() shl 8) or (p.d("b") ?: 0.0).toInt()
                }
                "Circle", "Donut", "Thermal", "Moire", "Butterfly", "Hexagon", "Octagon" -> if (inEntry()) {
                    val d = p.um("diameter") ?: p.um("outerDiameter") ?: p.um("length") ?: p.um("size")
                    d?.let { putPrim(Prim.Circle(it)) }
                }
                "RectCenter", "RectCham" -> if (inEntry()) putPrim(Prim.Rect(p.um("width") ?: 0.0, p.um("height") ?: 0.0))
                "RectRound" -> if (inEntry()) putPrim(Prim.Rect(p.um("width") ?: 0.0, p.um("height") ?: 0.0, p.um("radius") ?: 0.0))
                "Oval", "Ellipse" -> if (inEntry()) putPrim(Prim.Oval(p.um("width") ?: 0.0, p.um("height") ?: 0.0))
                "Diamond" -> if (inEntry()) {
                    val w = (p.um("width") ?: 0.0) / 2; val h = (p.um("height") ?: 0.0) / 2
                    putPrim(Prim.Poly(listOf(0.0 to -h, w to 0.0, 0.0 to h, -w to 0.0)))
                }
                "Triangle" -> if (inEntry()) {
                    val b = (p.um("base") ?: 0.0) / 2; val h = (p.um("height") ?: 0.0) / 2
                    putPrim(Prim.Poly(listOf(-b to -h, b to -h, 0.0 to h)))
                }
                "RectCorner" -> if (inEntry()) {
                    val x0 = p.um("lowerLeftX") ?: 0.0; val y0 = p.um("lowerLeftY") ?: 0.0
                    val x1 = p.um("upperRightX") ?: 0.0; val y1 = p.um("upperRightY") ?: 0.0
                    putPrim(Prim.Poly(listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)))
                }
                "LineDesc" -> {
                    val w = p.um("lineWidth") ?: 0.0
                    if (parent() == "EntryLineDesc") entryId?.let { lineDescs[it] = LineStyle(w) } else pendLineWidth = w
                }
                "LineDescRef" -> pendLineWidth = p.a("id")?.let { lineDescs[it]?.width }

                "Layer" -> if (parent() == "CadData") p.a("name")?.let { layers += RawLayer(it, p.a("layerFunction"), p.a("side")) }
                "StackupLayer" -> {
                    val ref = p.a("layerOrGroupRef"); val seq = p.d("sequence")
                    if (ref != null && seq != null) stackOrder[ref] = seq.toInt()
                }
                "Step" -> { stepCount++; if (stepCount == 1) stepName = p.a("name") }

                "PolyBegin" -> { poly = ArrayList(); poly += xy(p) }
                "PolyStepSegment" -> poly += xy(p)
                "PolyStepCurve" -> poly.lastOrNull()?.let { (sx, sy) ->
                    val (ex, ey) = xy(p)
                    poly += arcPoints(sx, sy, ex, ey, p.um("centerX") ?: sx, p.um("centerY") ?: sy, p.a("clockwise") == "true")
                }
                "Polyline" -> pendLineWidth = null
                "Contour" -> { contourOuter = null; contourHoles.clear() }

                "Xform" -> pendXf = Xf(
                    rot = p.d("rotation") ?: 0.0,
                    mirror = p.a("mirror")?.let { it == "true" },
                    dx = p.um("xOffset") ?: 0.0, dy = p.um("yOffset") ?: 0.0,
                    scale = p.d("scale") ?: 1.0,
                )
                "Location" -> pendLoc = xy(p)
                "StandardPrimitiveRef", "UserPrimitiveRef" -> pendPrim = p.a("id")
                "PinRef" -> {
                    val pr = PinRef(p.a("componentRef") ?: "", p.a("pin") ?: "")
                    if (parent() == "LogicalNet") logicalNet?.let { logicalNets[pr.comp to pr.pin] = it } else pendPin = pr
                }
                "LogicalNet" -> logicalNet = p.a("name")

                "Package" -> { pkgName = p.a("name"); pkgOutline = emptyList(); pkgPins.clear() }
                "Pin" -> if (inside("Package")) { pinNumber = p.a("number") ?: p.a("name"); resetPending() }

                "Component" -> if (inside("Step")) {
                    compAttrs = arrayOf(p.a("refDes"), p.a("packageRef"), p.a("layerRef"), p.a("part")); resetPending()
                }

                "PadStack" -> { psNet = netName(p.a("net")); psHoles.clear(); psPads.clear() }
                "LayerHole" -> { resetPending(); holeAttrs = rawHole(p) }
                "LayerPad" -> { resetPending(); layerPadLayer = p.a("layerRef") }

                "LayerFeature" -> featureLayer = p.a("layerRef")
                "Set" -> if (featureLayer != null) setNet = netName(p.a("net"))
                "ColorRef" -> if (featureLayer != null) p.a("id")?.let { layerColorRef[featureLayer!!] = it }
                "Line", "Arc" -> if (featureLayer != null) {
                    pendLineWidth = null
                    lineClockwise = p.a("clockwise") == "true"
                    lineAttrs = doubleArrayOf(
                        p.um("startX") ?: 0.0, p.um("startY") ?: 0.0, p.um("endX") ?: 0.0, p.um("endY") ?: 0.0,
                        p.um("centerX") ?: Double.NaN, p.um("centerY") ?: Double.NaN,
                    )
                }
                "Pad" -> if (featureLayer != null) resetPending()
                "Hole" -> if (featureLayer != null) setHoles += rawHole(p)

                "BomItem" -> {
                    bomAttrs = arrayOf(p.a("OEMDesignNumberRef"), p.a("description"), p.a("internalPartNumber")); bomRefs.clear(); bomChars.clear()
                }
                "RefDes" -> if (parent() == "BomItem") p.a("name")?.let { bomRefs += it }
                "Textual" -> {
                    val n = p.a("textualCharacteristicName") ?: p.a("definitionSource")
                    val v = p.a("textualCharacteristicValue")
                    if (n != null && v != null) bomChars[n.lowercase()] = v
                }
            }
        }

        fun end(name: String) {
            if (skipStep()) return
            // Closed shapes repeat the first point at the end; drop it.
            if ((name == "Polygon" || name == "Cutout") && poly.size > 1 && poly.first() == poly.last()) poly.removeAt(poly.lastIndex)
            when (name) {
                "DictionaryStandard", "DictionaryUser", "DictionaryLineDesc", "DictionaryFont", "DictionaryFirmware" -> units = cadUnits
                "EntryStandard", "EntryUser", "EntryLineDesc", "EntryColor" -> entryId = null
                "Polygon" -> when {
                    inEntry() -> putPrim(Prim.Poly(poly))
                    parent() == "Outline" && inside("Package") -> pkgOutline = poly
                    parent() == "Profile" -> outline += Polygon(poly.toPoints())
                    parent() == "Contour" -> contourOuter = poly.toPoints()
                }
                "Cutout" -> when {
                    parent() == "Profile" -> outline.lastOrNull()?.let { outline[outline.lastIndex] = it.copy(holes = it.holes + listOf(poly.toPoints())) }
                    parent() == "Contour" -> contourHoles += poly.toPoints()
                }
                "Contour" -> if (featureLayer != null && !inEntry()) contourOuter?.let { outer ->
                    fills += Fill(featureLayer!!, Polygon(outer.oriented(true), contourHoles.map { it.oriented(false) }), setNet)
                }
                "Polyline" -> if (featureLayer != null && !inEntry()) {
                    val w = pendLineWidth ?: 0.0
                    for (i in 1 until poly.size) tracks += Track(featureLayer!!, pt(poly[i - 1]), pt(poly[i]), Math.round(w), setNet)
                }
                "Line", "Arc" -> if (featureLayer != null && !inEntry()) lineAttrs?.let { l ->
                    val center = if (name == "Arc" && !l[4].isNaN()) Point(Math.round(l[4]), Math.round(l[5])) else null
                    tracks += Track(
                        featureLayer!!, Point(Math.round(l[0]), Math.round(l[1])), Point(Math.round(l[2]), Math.round(l[3])),
                        Math.round(pendLineWidth ?: 0.0), setNet, center, lineClockwise,
                    )
                    lineAttrs = null
                }
                "Pad" -> if (featureLayer != null) setPads += RawPad(featureLayer!!, pendXf, pendLoc, pendPrim, pendPin, setNet)
                "Set" -> setNet = null
                "LayerFeature" -> featureLayer = null

                "Pin" -> if (inside("Package")) pinNumber?.let { pkgPins += Triple(it, pendLoc ?: (0.0 to 0.0), pendPrim) }
                "Package" -> pkgName?.let { packages[it] = RawPackage(it, pkgOutline, pkgPins.toList()) }
                "Component" -> compAttrs?.let { a ->
                    a[0]?.let { components += RawComponent(it, a[1], a[2], a[3], pendXf, pendLoc) }
                    compAttrs = null
                }
                "LayerHole" -> holeAttrs?.let { psHoles += RawHole(it.x, it.y, it.d, it.plating, pendPin ?: it.pin); holeAttrs = null }
                "LayerPad" -> layerPadLayer?.let { psPads += RawPad(it, pendXf, pendLoc, pendPrim, pendPin, psNet); layerPadLayer = null }
                "PadStack" -> padStacks += RawPadStack(psNet, psHoles.toList(), psPads.toList())
                "LogicalNet" -> logicalNet = null
                "BomItem" -> bomAttrs?.let { a ->
                    boms += RawBom(
                        a[0], a[1],
                        bomChars["manufacturer part number"] ?: bomChars["mpn"] ?: bomChars["part number"],
                        bomChars["manufacturer"] ?: bomChars["manufacturer 1"],
                        bomRefs.toList(),
                    )
                    bomAttrs = null
                }
            }
        }

        private fun inEntry() = inside("EntryStandard") || inside("EntryUser")
        private fun putPrim(prim: Prim) { entryId?.let { prims.putIfAbsent(it, prim) } }
        private fun resetPending() { pendXf = null; pendLoc = null; pendPrim = null; pendPin = null }
        private fun xy(p: XmlPullParser) = (p.um("x") ?: 0.0) to (p.um("y") ?: 0.0)
        private fun rawHole(p: XmlPullParser) = RawHole(
            p.um("x") ?: 0.0, p.um("y") ?: 0.0, p.um("diameter") ?: 0.0, p.a("platingStatus") ?: "PLATED", null,
        )

        // ------------------------------------------------------------ resolve

        fun build(fallbackName: String): Board {
            val layerList = layers.mapIndexed { i, l ->
                val type = layerType(l.function)
                val side = when (l.side?.uppercase()) {
                    "TOP" -> Side.TOP
                    "BOTTOM" -> Side.BOTTOM
                    else -> null
                }
                val rgb = colors["LAYER_COLOR_${l.name}"] ?: layerColorRef[l.name]?.let { colors[it] }
                Layer(l.name, type, side, stackOrder[l.name] ?: i, rgb)
            }.sortedBy { it.order }.mapIndexed { i, l -> l.copy(order = i) }
            val byName = layerList.associateBy { it.name }
            fun isCopper(layer: String) = byName[layer]?.type == LayerType.COPPER
            fun sideOf(layer: String?) = layer?.let { byName[it]?.side }

            val pads = ArrayList<Pad>()
            val vias = ArrayList<Via>()
            val holes = LinkedHashMap<Triple<Long, Long, Long>, Hole>()
            fun addHole(h: RawHole) {
                val hole = Hole(pt(h.x to h.y), Math.round(h.d), !h.plating.equals("NONPLATED", true))
                holes.putIfAbsent(Triple(hole.position.x, hole.position.y, hole.diameter), hole)
            }

            fun addPad(rp: RawPad, drill: Long?) {
                val loc = rp.loc ?: return
                val prim = rp.prim?.let { prims[it] }
                val net = rp.net ?: rp.pin?.let { logicalNets[it.comp to it.pin] }
                pads += makePad(pads.size, rp.pin?.pin ?: "", loc, rp.xf, prim, drill, net, rp.layer, sideOf(rp.layer) ?: Side.TOP, rp.pin?.comp?.takeIf { it.isNotEmpty() })
            }

            for (ps in padStacks) {
                ps.holes.forEach { addHole(it) }
                val via = ps.holes.firstOrNull { it.plating.equals("VIA", true) }
                if (via != null) {
                    val padSize = ps.pads.filter { isCopper(it.layer) }.maxOfOrNull { primSize(it.prim?.let(prims::get)) } ?: 0.0
                    vias += Via(pt(via.x to via.y), Math.round(maxOf(padSize, via.d)), Math.round(via.d), ps.net)
                    continue
                }
                val drill = ps.holes.firstOrNull()?.d?.let { Math.round(it) }
                ps.pads.filter { isCopper(it.layer) }.forEach { addPad(it, drill) }
            }
            setPads.filter { isCopper(it.layer) }.forEach { addPad(it, null) }
            setHoles.forEach { addHole(it) }

            // BOM by designator
            val bomByRef = HashMap<String, RawBom>()
            boms.forEach { b -> b.refs.forEach { bomByRef[it.uppercase()] = b } }

            val padsByComp = pads.groupBy { it.componentRef?.uppercase() }
            val comps = components.map { rc ->
                val side = sideOf(rc.layer) ?: Side.TOP
                val loc = rc.loc ?: (0.0 to 0.0)
                val pkg = rc.pkg?.let { packages[it] }
                val mirror = rc.xf?.mirror ?: (side == Side.BOTTOM)
                val toBoard = { q: Pair<Double, Double> -> transform(q, rc.xf, mirror, loc) }

                var compPads = padsByComp[rc.ref.uppercase()].orEmpty()
                if (compPads.isEmpty() && pkg != null) {
                    // No PadStack data: synthesise pads from the package pins.
                    val copper = rc.layer?.takeIf { isCopper(it) } ?: layerList.firstOrNull { it.type == LayerType.COPPER && it.side == side }?.name
                    pkg.pins.forEach { (num, at, primId) ->
                        val prim = primId?.let { prims[it] }
                        val rot = (rc.xf?.rot ?: 0.0)
                        pads += makePad(pads.size, num, toBoard(at), Xf(rot = rot), prim, null, logicalNets[rc.ref to num], copper, side, rc.ref)
                    }
                    compPads = pads.filter { it.componentRef == rc.ref }
                }

                val outlineBox = pkg?.outline?.takeIf { it.size >= 2 }?.map { pt(toBoard(it)) }?.let { Rect.of(it) }
                val padBox = compPads.map { it.bounds }.reduceOrNull { a, b -> a.union(b) }
                val position = pt(loc)
                val bounds = listOfNotNull(outlineBox, padBox).reduceOrNull { a, b -> a.union(b) } ?: Rect.around(position, 1000, 1000)
                val bom = bomByRef[rc.ref.uppercase()]
                val libName = bom?.libRef?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
                Component(
                    refDes = rc.ref,
                    value = deriveValue(libName, bom?.description),
                    description = bom?.description?.trim()?.takeIf { it.isNotEmpty() } ?: libName,
                    footprint = footprintName(rc.pkg ?: rc.part),
                    partNumber = bom?.partNumber,
                    manufacturer = bom?.manufacturer,
                    side = side,
                    position = position,
                    rotationDeg = normalizeDeg(rc.xf?.rot ?: 0.0),
                    bounds = bounds,
                    padIds = compPads.map { it.id },
                )
            }

            val nets = pads.filter { it.net != null }.groupBy { it.net!! }.mapValues { (n, ps) -> Net(n, ps.map { it.id }) }

            return Board(
                name = stepName ?: fallbackName,
                source = SourceFormat.IPC2581,
                outline = outline,
                layers = layerList,
                tracks = tracks,
                vias = vias,
                pads = pads,
                holes = holes.values.toList(),
                fills = fills,
                components = comps,
                nets = nets,
                units = cadUnits,
            )
        }

        private fun footprintName(pkg: String?): String? {
            pkg ?: return null
            val base = pkg.replace(Regex("_\\d+$"), "")
            return if (base != pkg && packages.containsKey(base)) base else pkg
        }
    }

    private fun makePad(
        id: Int, name: String, loc: Pair<Double, Double>, xf: Xf?, prim: Prim?, drill: Long?, net: String?,
        layer: String?, side: Side, comp: String?,
    ): Pad {
        val rot = xf?.rot ?: 0.0
        val center = pt((loc.first + (xf?.dx ?: 0.0)) to (loc.second + (xf?.dy ?: 0.0)))
        return when (prim) {
            is Prim.Circle -> Pad(id, name, center, PadShape.ROUND, Math.round(prim.d), Math.round(prim.d), rot, drill, net, layer, side, comp)
            is Prim.Rect -> Pad(
                id, name, center, if (prim.r > 0) PadShape.ROUNDED_RECT else PadShape.RECT,
                Math.round(prim.w), Math.round(prim.h), rot, drill, net, layer, side, comp, cornerRadius = Math.round(prim.r),
            )
            is Prim.Oval -> Pad(id, name, center, PadShape.OVAL, Math.round(prim.w), Math.round(prim.h), rot, drill, net, layer, side, comp)
            is Prim.Poly -> {
                val m = xf?.mirror == true
                val poly = prim.pts.map { pt(transform(it, Xf(rot = rot), m, center.x.toDouble() to center.y.toDouble())) }
                val box = Rect.of(poly)
                Pad(id, name, center, PadShape.POLYGON, box.width, box.height, rot, drill, net, layer, side, comp, polygon = poly)
            }
            null -> {
                val d = drill ?: 500L
                Pad(id, name, center, PadShape.ROUND, d, d, rot, drill, net, layer, side, comp)
            }
        }
    }

    private fun primSize(p: Prim?): Double = when (p) {
        is Prim.Circle -> p.d
        is Prim.Rect -> maxOf(p.w, p.h)
        is Prim.Oval -> maxOf(p.w, p.h)
        is Prim.Poly -> Rect.of(p.pts.map { pt(it) }).let { maxOf(it.width, it.height).toDouble() }
        null -> 0.0
    }

    /** Package-local -> board: scale, mirror X, rotate CCW, offset, then place at [loc]. */
    private fun transform(q: Pair<Double, Double>, xf: Xf?, mirror: Boolean, loc: Pair<Double, Double>): Pair<Double, Double> {
        val s = xf?.scale ?: 1.0
        var x = q.first * s; val y = q.second * s
        if (mirror) x = -x
        val r = Math.toRadians(xf?.rot ?: 0.0)
        val rx = x * cos(r) - y * sin(r); val ry = x * sin(r) + y * cos(r)
        return (rx + (xf?.dx ?: 0.0) + loc.first) to (ry + (xf?.dy ?: 0.0) + loc.second)
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        fun parseUnits(s: String?): Units? = when (s?.uppercase()) {
            "MILLIMETER" -> Units.MILLIMETER
            "MICRON" -> Units.MICROMETER
            "INCH" -> Units.INCH
            "MILS", "MIL" -> Units.MIL
            else -> null
        }

        fun layerType(function: String?): LayerType = when (function?.uppercase()) {
            "SIGNAL", "CONDUCTOR", "PLANE", "MIXED", "CONDFILM", "CONDFOIL", "POWER_GROUND" -> LayerType.COPPER
            "LEGEND", "SILKSCREEN" -> LayerType.SILKSCREEN
            "SOLDERMASK" -> LayerType.SOLDER_MASK
            "PASTEMASK", "SOLDERPASTE" -> LayerType.PASTE
            "DRILL" -> LayerType.DRILL
            "ASSEMBLY" -> LayerType.ASSEMBLY
            "BOARD_OUTLINE", "ROUT", "PROFILE" -> LayerType.OUTLINE
            "DOCUMENT", "COMPONENT_TOP", "COMPONENT_BOTTOM" -> LayerType.DOCUMENT
            else -> LayerType.OTHER
        }

        fun netName(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("No Net", true) && !it.equals("NoNet", true) }

        private fun pt(q: Pair<Double, Double>) = Point(Math.round(q.first), Math.round(q.second))
        private fun List<Pair<Double, Double>>.toPoints() = map { pt(it) }
        private fun normalizeDeg(a: Double) = ((a % 360.0) + 360.0) % 360.0

        private val resistor = Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*([KkMmGg]?)\s*(?:OHMS?|Ω)""", RegexOption.IGNORE_CASE)
        private val capacitor = Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*([pPnNuUµ])F\b""")
        private val inductor = Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*([nNuUµmM])H\b""")

        /**
         * IPC-2581 from Altium has no value field. Takes it from the library name / description:
         * "RES 100K OHM 1%" -> "100kΩ", "CAP CER 0.1UF 50V" -> "0.1uF"; otherwise the library name
         * itself ("L7812CV").
         */
        fun deriveValue(libName: String?, description: String?): String? {
            for (text in listOfNotNull(libName, description)) {
                resistor.find(text)?.let { m ->
                    val mult = m.groupValues[2].lowercase().let { if (it == "m") "M" else if (it == "g") "G" else it }
                    return "${m.groupValues[1]}${mult}Ω"
                }
                capacitor.find(text)?.let { m -> return "${m.groupValues[1]}${m.groupValues[2].lowercase().replace('µ', 'u')}F" }
                inductor.find(text)?.let { m ->
                    val u = m.groupValues[2].let { if (it == "M" || it == "m") "m" else it.lowercase().replace('µ', 'u') }
                    return "${m.groupValues[1]}${u}H"
                }
            }
            return libName
        }
    }
}
