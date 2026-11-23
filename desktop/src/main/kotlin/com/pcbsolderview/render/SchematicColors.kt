package com.pcbsolderview.render

import com.pcbsolderview.model.SchematicLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How schematic colours follow the app theme. [Ink.ORIGINAL] keeps Altium's colours on its paper
 * (light theme); [Ink.INVERTED] flips each colour's lightness, keeping its hue, so navy wires turn
 * light blue on dark paper and pale-yellow bodies turn dark; [Ink.HIGH_CONTRAST] uses saturated,
 * bright strokes on black.
 */
data class SchematicColors(
    val background: Int = 0xFF0B0F14.toInt(),
    /** Paper colour; null keeps the file's paper. */
    val paper: Int? = null,
    val ink: Ink = Ink.ORIGINAL,
    val selected: Int = 0xFFFFB000.toInt(),
    val highlight: Int = 0xFFD5009F.toInt(),
    val net: Int = 0xFF00B8D4.toInt(),
    /** Parts that are not on the board are drawn faded. */
    val missingAlpha: Float = 0.45f,
) {
    enum class Ink { ORIGINAL, INVERTED, HIGH_CONTRAST }

    private val strokeCache = HashMap<Int, Int>()
    private val fillCache = HashMap<Int, Int>()

    /** Colour for lines and text. */
    fun stroke(argb: Int): Int = strokeCache.getOrPut(argb) {
        when (ink) {
            Ink.ORIGINAL -> argb
            Ink.INVERTED -> withHsl(argb) { h, s, l -> Triple(h, s, max(1 - l, 0.62)) }
            Ink.HIGH_CONTRAST -> withHsl(argb) { h, s, l -> if (s < 0.2) Triple(h, 0.0, 1.0) else Triple(h, 1.0, max(0.62, 1 - l)) }
        }
    }

    /** Colour for filled areas (symbol bodies, frames). */
    fun fill(argb: Int): Int = fillCache.getOrPut(argb) {
        when (ink) {
            Ink.ORIGINAL -> argb
            Ink.INVERTED -> withHsl(argb) { h, s, l -> Triple(h, s * 0.6, min(1 - l, 0.2) + 0.06) }
            Ink.HIGH_CONTRAST -> withHsl(argb) { h, s, _ -> Triple(h, s * 0.5, 0.1) }
        }
    }

    fun paper(fileArgb: Int?): Int = paper ?: fileArgb ?: SchematicLayout.PAPER

    companion object {
        /** Applies [f] to the colour in HSL (h 0..360, s, l 0..1), keeping alpha. */
        fun withHsl(argb: Int, f: (Double, Double, Double) -> Triple<Double, Double, Double>): Int {
            val a = argb ushr 24
            val r = (argb shr 16 and 0xFF) / 255.0; val g = (argb shr 8 and 0xFF) / 255.0; val b = (argb and 0xFF) / 255.0
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (mx + mn) / 2
            val d = mx - mn
            val s = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
            val h = when {
                d == 0.0 -> 0.0
                mx == r -> 60 * (((g - b) / d).mod(6.0))
                mx == g -> 60 * ((b - r) / d + 2)
                else -> 60 * ((r - g) / d + 4)
            }
            val (h2, s2, l2) = f(h, s.coerceIn(0.0, 1.0), l)
            val c = (1 - abs(2 * l2 - 1)) * s2
            val x = c * (1 - abs((h2 / 60).mod(2.0) - 1))
            val m = l2 - c / 2
            val (r1, g1, b1) = when ((h2 / 60).toInt().coerceIn(0, 5)) {
                0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
            }
            fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
            return (a shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
        }
    }
}

/**
 * Schematic view options that are not colours (Settings › Appearance › Schematic view).
 * [textScale] multiplies every text size of the sheet; the grid is drawn on the paper in sheet
 * units, from the sheet origin, every [gridStepUm] (Altium's default snap grid is 100 mil).
 */
data class SchematicStyle(
    val textScale: Float = 1f,
    val grid: GridStyle = GridStyle.NONE,
    val gridStepUm: Long = 2540,
)
