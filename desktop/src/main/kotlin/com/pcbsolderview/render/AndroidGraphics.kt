package com.pcbsolderview.render

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Typeface as SkTypeface

// Desktop only. The Android canvases draw text with android.graphics.Paint/Typeface on the native
// canvas. On desktop the native canvas is a Skia canvas, so this file provides the small part of
// that API the canvases use (same names), drawn with Skia fonts. Keeping the call sites unchanged
// makes syncing BoardCanvas/SchematicCanvas with Android a near-copy.

/** The skia canvas behind Compose's nativeCanvas on desktop. */
typealias NativeCanvas = org.jetbrains.skia.Canvas

/** android.graphics.Color constants used by the canvases. */
object GraphicsColor {
    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val GREEN: Int = 0xFF00FF00.toInt()
}

class Typeface private constructor(val family: Family, val style: Int) {
    enum class Family { SANS, SERIF, MONO }

    /** Skia typeface for this family/style, from the fonts installed on Windows. */
    internal val skia: SkTypeface? by lazy {
        val names = when (family) {
            Family.MONO -> listOf("Consolas", "Cascadia Mono", "Courier New", "monospace")
            Family.SERIF -> listOf("Times New Roman", "Georgia", "serif")
            Family.SANS -> listOf("Segoe UI", "Arial", "sans-serif")
        }
        val fs = when (style) {
            BOLD_ITALIC -> FontStyle.BOLD_ITALIC
            BOLD -> FontStyle.BOLD
            ITALIC -> FontStyle.ITALIC
            else -> FontStyle.NORMAL
        }
        names.firstNotNullOfOrNull { runCatching { FontMgr.default.matchFamilyStyle(it, fs) }.getOrNull() }
            ?: runCatching { FontMgr.default.legacyMakeTypeface("", fs) }.getOrNull()
    }

    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        private val cache = HashMap<Pair<Family, Int>, Typeface>()
        private fun of(f: Family, s: Int) = synchronized(cache) { cache.getOrPut(f to s) { Typeface(f, s) } }

        val DEFAULT: Typeface get() = of(Family.SANS, NORMAL)
        val SANS_SERIF: Typeface get() = of(Family.SANS, NORMAL)
        val SERIF: Typeface get() = of(Family.SERIF, NORMAL)
        val MONOSPACE: Typeface get() = of(Family.MONO, NORMAL)

        fun create(family: Typeface?, style: Int): Typeface = of(family?.family ?: Family.SANS, style)
    }
}

class Paint(flags: Int = 0) {
    enum class Align { LEFT, CENTER, RIGHT }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    var typeface: Typeface = Typeface.DEFAULT
    var textAlign: Align = Align.LEFT
    var style: Style = Style.FILL
    var color: Int = GraphicsColor.BLACK
    var textSize: Float = 12f
    var strokeWidth: Float = 0f
    var isSubpixelText: Boolean = false
    private val antiAlias = flags and ANTI_ALIAS_FLAG != 0
    private var shadow: ImageFilter? = null

    var alpha: Int
        get() = color ushr 24
        set(v) { color = (color and 0x00FFFFFF) or (v.coerceIn(0, 255) shl 24) }

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, shadowColor: Int) {
        shadow = if (radius <= 0f) null else ImageFilter.makeDropShadow(dx, dy, radius / 2f, radius / 2f, shadowColor)
    }

    private var font: Font? = null
    private var fontKey: Pair<Typeface, Float>? = null

    internal fun font(): Font {
        val key = typeface to textSize
        if (fontKey != key) {
            font = Font(typeface.skia, textSize).apply {
                edging = if (isSubpixelText) FontEdging.SUBPIXEL_ANTI_ALIAS else FontEdging.ANTI_ALIAS
                isSubpixel = isSubpixelText
            }
            fontKey = key
        }
        return font!!
    }

    fun measureText(text: String): Float = if (text.isEmpty()) 0f else font().measureTextWidth(text)

    fun measureText(text: String, start: Int, end: Int): Float = measureText(text.substring(start, end))

    private val skia = org.jetbrains.skia.Paint()

    internal fun skia(withShadow: Boolean): org.jetbrains.skia.Paint = skia.apply {
        isAntiAlias = antiAlias
        color = this@Paint.color
        mode = when (style) { Style.FILL -> PaintMode.FILL; Style.STROKE -> PaintMode.STROKE; Style.FILL_AND_STROKE -> PaintMode.STROKE_AND_FILL }
        strokeWidth = this@Paint.strokeWidth
        imageFilter = if (withShadow) shadow else null
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}

/** android.graphics.Canvas.drawText: x is the anchor given by [Paint.textAlign], y the baseline. */
fun NativeCanvas.drawText(text: String, x: Float, y: Float, paint: Paint) {
    if (text.isEmpty()) return
    val font = paint.font()
    val dx = when (paint.textAlign) {
        Paint.Align.LEFT -> 0f
        Paint.Align.CENTER -> -font.measureTextWidth(text) / 2f
        Paint.Align.RIGHT -> -font.measureTextWidth(text)
    }
    val p = paint.skia(withShadow = true)
    val mode = p.mode
    p.mode = PaintMode.FILL
    drawString(text, x + dx, y, font, p)
    p.mode = mode
}

fun NativeCanvas.drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
    drawCircle(cx, cy, radius, paint.skia(withShadow = false))
}

fun NativeCanvas.drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
    val p = paint.skia(withShadow = false)
    val mode = p.mode
    p.mode = PaintMode.STROKE
    drawLine(x0, y0, x1, y1, p)
    p.mode = mode
}
