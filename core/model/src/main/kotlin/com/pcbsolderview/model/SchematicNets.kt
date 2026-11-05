package com.pcbsolderview.model

/** A pin of a part, as both views name it: designator + pin number (= board pad name). */
data class PinRef(val refDes: String, val pin: String) {
    override fun toString() = "$refDes.$pin"
}

/**
 * One electrical net of a schematic.
 *
 * @param name net label / power port / port name, else Altium's automatic "Net<designator>_<pin>"
 * @param wires wire polylines of this net, per sheet index
 * @param points pin connection points of this net, per sheet index
 */
data class SchNet(
    val id: Int,
    val name: String,
    val named: Boolean,
    val pins: Set<PinRef>,
    val wires: Map<Int, List<List<Point>>>,
    val points: Map<Int, List<Point>>,
)

/**
 * Connectivity of a schematic: wires that touch (an end on another wire, or a junction on both),
 * pins whose connection point lies on a wire or on another pin, net labels and power ports on a
 * wire or pin; then labels, power ports and ports of the same name join across the whole design
 * (flat multi-sheet). Buses are not traced (their members connect through labels).
 */
class SchematicConnectivity(val schematic: Schematic) {

    val nets: List<SchNet>
    private val byPin: Map<PinRef, SchNet>
    private val byName: Map<String, SchNet>

    init {
        val uf = UnionFind()
        // Items: wires, pins, labels, power ports; ids are strings so sheets do not collide.
        class PinItem(val sheet: Int, val id: String, val ref: PinRef, val at: Point)
        val pins = ArrayList<PinItem>()
        val wireOwner = HashMap<String, Pair<Int, SchWire>>()
        val names = HashMap<String, String>() // item → net name (label/power/port)
        val powerNames = HashSet<String>()
        val pinOf = HashMap<String, PinRef>() // item id → pin

        schematic.sheets.forEachIndexed { si, sheet ->
            val wires = sheet.wires
            fun wid(i: Int) = "w$si:$i"
            wires.forEachIndexed { i, w -> uf.add(wid(i)); wireOwner[wid(i)] = si to w }
            // Wire to wire: an end of one on the other; a junction on both.
            for (i in wires.indices) for (j in wires.indices) {
                if (i == j) continue
                val a = wires[i].points
                if (onPolyline(a.first(), wires[j].points) || onPolyline(a.last(), wires[j].points)) uf.union(wid(i), wid(j))
            }
            for (jn in sheet.junctions) {
                val on = wires.indices.filter { onPolyline(jn, wires[it].points) }
                for (k in 1 until on.size) uf.union(wid(on[0]), wid(on[k]))
            }
            fun attach(item: String, p: Point) {
                uf.add(item)
                wires.indices.filter { onPolyline(p, wires[it].points) }.forEach { uf.union(item, wid(it)) }
            }
            // Pins are identified per symbol: two symbols may (wrongly) share a designator.
            sheet.symbols.forEachIndexed { k, s ->
                if (!s.refDes.isAnnotated()) return@forEachIndexed
                s.pins.forEach { p ->
                    if (p.hidden) return@forEach
                    val id = "p$si:$k:${p.designator}"
                    pins += PinItem(si, id, PinRef(s.refDes, p.designator), p.position)
                    pinOf[id] = PinRef(s.refDes, p.designator)
                    attach(id, p.position)
                }
            }
            // Pin on pin (parts placed touching).
            val sheetPins = pins.filter { it.sheet == si }
            sheetPins.groupBy { it.at }.values.filter { it.size > 1 }.forEach { g ->
                for (k in 1 until g.size) uf.union(g[0].id, g[k].id)
            }
            fun attachToPins(item: String, p: Point) = sheetPins.filter { near(it.at, p) }.forEach { uf.union(item, it.id) }
            sheet.netLabels.forEachIndexed { i, l ->
                val id = "l$si:$i"; attach(id, l.position); attachToPins(id, l.position); names[id] = l.text
            }
            sheet.powerPorts.forEachIndexed { i, pp ->
                val id = "pp$si:$i"; attach(id, pp.position); attachToPins(id, pp.position); names[id] = pp.text; powerNames += pp.text
            }
        }
        // Same name, same net, across sheets.
        names.entries.groupBy({ it.value.uppercase() }, { it.key }).values.forEach { ids -> for (k in 1 until ids.size) uf.union(ids[0], ids[k]) }

        // Group into nets that connect at least one pin (or carry a name).
        val groups = uf.groups()
        val out = ArrayList<SchNet>()
        for (members in groups) {
            val pinRefs = members.mapNotNull { pinOf[it] }.toSet()
            val netNames = members.mapNotNull { names[it] }
            if (pinRefs.isEmpty() && netNames.isEmpty()) continue
            // Power port names win over labels (as in Altium); then the most common label.
            val name = netNames.filter { it in powerNames }.minOrNull()
                ?: netNames.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
                ?: pinRefs.minWithOrNull(compareBy(NaturalOrder) { "${it.refDes}_${it.pin}" })!!.let { "Net${it.refDes}_${it.pin}" }
            val wires = members.mapNotNull { wireOwner[it] }.groupBy({ it.first }, { it.second.points })
            val memberSet = members.toHashSet()
            val points = pins.filter { it.id in memberSet }.groupBy({ it.sheet }, { it.at })
            out += SchNet(0, name, netNames.isNotEmpty(), pinRefs, wires, points)
        }
        nets = out.sortedWith(compareBy(NaturalOrder) { it.name }).mapIndexed { i, n -> n.copy(id = i) }
        byPin = nets.flatMap { n -> n.pins.map { it to n } }.toMap()
        byName = nets.associateBy { it.name.uppercase() }
    }

    fun netOf(pin: PinRef): SchNet? = byPin[pin]

    fun net(name: String): SchNet? = byName[name.uppercase()]

    /** Net of the wire, pin or label under a sheet point (µm), within [tolerance]. */
    fun netAt(sheet: Int, x: Double, y: Double, tolerance: Double): SchNet? {
        var best: SchNet? = null
        var bestD = tolerance
        for (n in nets) {
            n.wires[sheet]?.forEach { w ->
                for (k in 0 until w.size - 1) {
                    val d = distToSegment(x, y, w[k], w[k + 1])
                    if (d <= bestD) { bestD = d; best = n }
                }
            }
            n.points[sheet]?.forEach { p ->
                val d = kotlin.math.hypot(x - p.x, y - p.y)
                if (d <= bestD) { bestD = d; best = n }
            }
        }
        return best
    }

    /**
     * Schematic net of each board net: the one holding most of the board net's pads (by designator
     * + pin number), so unnamed nets line up too. Pads of parts that are not in the schematic are
     * ignored; board nets with no pad in the schematic are left out.
     */
    fun matchBoard(board: Board): Map<String, SchNet> {
        val out = HashMap<String, SchNet>()
        val padsByNet = board.pads.filter { it.net != null && it.componentRef != null }.groupBy { it.net!! }
        for ((net, pads) in padsByNet) {
            val counts = pads.mapNotNull { netOf(PinRef(it.componentRef!!, it.name)) }.groupingBy { it }.eachCount()
            counts.maxByOrNull { it.value }?.let { out[net] = it.key }
        }
        return out
    }

    private class UnionFind {
        private val parent = HashMap<String, String>()
        fun add(x: String) { parent.putIfAbsent(x, x) }
        fun find(x: String): String {
            add(x)
            var r = x
            while (parent[r] != r) r = parent[r]!!
            var c = x
            while (parent[c] != r) { val n = parent[c]!!; parent[c] = r; c = n }
            return r
        }
        fun union(a: String, b: String) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        fun groups(): Collection<List<String>> = parent.keys.groupBy { find(it) }.values
    }

    companion object {
        /** Coordinates come from 10 mil units with fractions: allow a few µm of rounding. */
        private const val EPS = 3.0

        fun near(a: Point, b: Point) = kotlin.math.abs(a.x - b.x) <= EPS && kotlin.math.abs(a.y - b.y) <= EPS

        fun onPolyline(p: Point, pts: List<Point>): Boolean {
            for (k in 0 until pts.size - 1) if (distToSegment(p.x.toDouble(), p.y.toDouble(), pts[k], pts[k + 1]) <= EPS) return true
            return pts.size == 1 && near(p, pts[0])
        }

        fun distToSegment(x: Double, y: Double, a: Point, b: Point): Double {
            val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            return kotlin.math.hypot(x - (a.x + t * dx), y - (a.y + t * dy))
        }
    }
}
