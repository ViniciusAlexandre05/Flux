package com.pcbsolderview.parser.altium

import java.io.ByteArrayOutputStream

/**
 * Test helper: writes the binary primitive records of a .PcbDoc in the layout [PcbDocParser]
 * reads (see its KDoc). Coordinates are given in mil and stored in 1/10000 mil.
 */
class PcbDocWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    class Sub {
        val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun i32(v: Int) = apply { for (i in 0..3) b.write((v shr (8 * i)) and 0xFF) }
        fun f64(v: Double) = apply { val l = java.lang.Double.doubleToLongBits(v); for (i in 0..7) b.write(((l shr (8 * i)) and 0xFF).toInt()) }
        fun mil(v: Double) = i32(Math.round(v * 10_000).toInt())
        fun zeros(n: Int) = apply { repeat(n) { b.write(0) } }
        fun text(s: String) = apply { b.write(s.toByteArray(charset("windows-1252"))) }
        fun header(layer: Int, net: Int = NONE, polygon: Int = NONE, component: Int = NONE, keepout: Boolean = false) = apply {
            u8(layer); u8(0x0C); u8(if (keepout) 2 else 0); u16(net); u16(polygon); u16(component); i32(-1)
        }
        fun ring(points: List<Pair<Double, Double>>) = apply { i32(points.size); points.forEach { (x, y) -> f64(x * 10_000); f64(y * 10_000) } }
    }

    fun record(type: Int, vararg subs: Sub) {
        out.write(type)
        for (s in subs) {
            val d = s.b.toByteArray()
            for (i in 0..3) out.write((d.size shr (8 * i)) and 0xFF)
            out.write(d)
        }
    }

    fun track(layer: Int, x1: Double, y1: Double, x2: Double, y2: Double, width: Double, net: Int = NONE, component: Int = NONE, keepout: Boolean = false) =
        record(4, Sub().header(layer, net, component = component, keepout = keepout).mil(x1).mil(y1).mil(x2).mil(y2).mil(width).zeros(16))

    fun arc(layer: Int, cx: Double, cy: Double, r: Double, startDeg: Double, endDeg: Double, width: Double, net: Int = NONE) =
        record(1, Sub().header(layer, net).mil(cx).mil(cy).mil(r).f64(startDeg).f64(endDeg).mil(width).zeros(12))

    fun via(x: Double, y: Double, diameter: Double, hole: Double, net: Int = NONE) =
        record(3, Sub().header(74, net).mil(x).mil(y).mil(diameter).mil(hole).u8(1).u8(32).zeros(200))

    /**
     * A pad; [shape] 1 round, 2 rect, 3 octagon. [roundPct] adds sub-record 6 with alternative
     * shape 9 (rounded rectangle) and that corner radius on every layer.
     */
    fun pad(
        name: String, layer: Int, x: Double, y: Double, w: Double, h: Double, shape: Int,
        hole: Double = 0.0, rotation: Double = 0.0, net: Int = NONE, component: Int = NONE, roundPct: Int? = null,
    ) {
        val geo = Sub().header(layer, net, component = component).mil(x).mil(y)
        repeat(3) { geo.mil(w).mil(h) }
        geo.mil(hole).u8(shape).u8(shape).u8(shape).f64(rotation).u8(1).zeros(124)
        val s6 = Sub()
        if (roundPct != null) {
            s6.zeros(532)
            repeat(32) { s6.u8(9) }
            repeat(32) { s6.u8(roundPct) }
            s6.zeros(651 - 596)
        }
        record(2, Sub().u8(name.length).text(name), Sub().u8(0), Sub().text("|&|0"), Sub().u8(0), geo, s6)
    }

    /** A region (Regions6) or, with type 12, a component body (ComponentBodies6). */
    fun region(
        layer: Int, props: String, outline: List<Pair<Double, Double>>, holes: List<List<Pair<Double, Double>>> = emptyList(),
        net: Int = NONE, polygon: Int = NONE, component: Int = NONE, type: Int = 11,
    ) {
        val s = Sub().header(layer, net, polygon, component).u8(0).u16(holes.size).u16(0)
        val p = props.toByteArray(charset("windows-1252")) + 0
        s.i32(p.size); s.b.write(p)
        s.ring(outline)
        holes.forEach { s.ring(it) }
        record(type, s)
    }

    companion object {
        const val NONE = 0xFFFF
    }
}
