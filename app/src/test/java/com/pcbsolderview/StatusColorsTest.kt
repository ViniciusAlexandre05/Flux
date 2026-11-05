package com.pcbsolderview

import com.pcbsolderview.render.MARK_ALPHA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Status colours must be easy to tell apart, also once painted (semi-opaque) over the board.
 * Uses the CIE76 colour difference (ΔE) in CIELAB: about 2 is just noticeable, 25+ reads as a
 * different colour at a glance.
 */
class StatusColorsTest {
    private fun lab(argb: Int): DoubleArray {
        fun lin(c: Int) = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin((argb shr 16) and 255); val g = lin((argb shr 8) and 255); val b = lin(argb and 255)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun deltaE(a: Int, b: Int): Double {
        val p = lab(a); val q = lab(b)
        return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
    }

    /** [fg] painted at [alpha] over [bg]. */
    private fun over(fg: Int, bg: Int, alpha: Float): Int {
        fun ch(s: Int) = Math.round(((fg shr s) and 255) * alpha + ((bg shr s) and 255) * (1 - alpha)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val grounds = mapOf(
        "board substrate" to 0xFF0F2A1C.toInt(),
        "green solder mask" to 0xFF1E5C3A.toInt(),
        "slate background" to 0xFF0B0F14.toInt(),
        "light gray background" to 0xFFD9DDE3.toInt(),
        "white background" to 0xFFFFFFFF.toInt(),
    )

    private val minDeltaE = 35.0

    @Test
    fun everyPairIsClearlyDifferent() {
        val s = Status.entries
        for (i in s.indices) for (j in i + 1 until s.size) {
            val raw = deltaE(s[i].argb, s[j].argb)
            assertTrue(raw >= minDeltaE, "${s[i]} vs ${s[j]}: ΔE ${"%.1f".format(raw)}")
            for ((name, bg) in grounds) {
                val d = deltaE(over(s[i].argb, bg, MARK_ALPHA), over(s[j].argb, bg, MARK_ALPHA))
                assertTrue(d >= minDeltaE, "${s[i]} vs ${s[j]} painted over $name: ΔE ${"%.1f".format(d)}")
            }
        }
    }

    @Test
    fun okAndCheckMuchFurtherApartThanBefore() {
        // The reported problem: the old OK green and Check yellow, painted at 55% over the board.
        val board = grounds.getValue("board substrate")
        val before = deltaE(over(Status.OK.legacyArgb, board, 0.55f), over(Status.CHECK.legacyArgb, board, 0.55f))
        val after = deltaE(over(Status.OK.argb, board, MARK_ALPHA), over(Status.CHECK.argb, board, MARK_ALPHA))
        println("OK vs Check over the board: ΔE before %.1f, after %.1f".format(before, after))
        assertTrue(after > before + 10, "before ${"%.1f".format(before)}, after ${"%.1f".format(after)}")
    }

    @Test
    fun symbolsAreDistinct() {
        assertEquals(Status.entries.size, Status.entries.map { it.glyph }.toSet().size)
        assertEquals(Status.entries.size, Status.entries.map { it.legacyArgb }.toSet().size)
    }
}
