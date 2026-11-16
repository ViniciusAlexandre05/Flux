package com.pcbsolderview.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import com.pcbsolderview.model.HAlign
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.SchematicLayout
import com.pcbsolderview.model.SchText
import com.pcbsolderview.model.VAlign
import com.pcbsolderview.render.BoardScene.Companion.addRect
import kotlin.math.atan2

/** What to emphasise on a sheet: selection, highlight group, status paint, net focus. */
data class SchematicFocus(
    /** Designators (upper-case) of the selected part. */
    val selected: Set<String> = emptySet(),
    /** Designators (upper-case) of highlighted parts (identical parts, search results…). */
    val highlighted: Set<String> = emptySet(),
    /** Designators (upper-case) shown faded: parts that are not on the board. */
    val faded: Set<String> = emptySet(),
    /** Status paint by designator (any case). */
    val marks: Map<String, ComponentMark> = emptyMap(),
    /** Net in focus: wire segments and connection points to draw over a faded sheet. */
    val net: SchematicNetGeometry? = null,
)

/** Geometry of one schematic net on one sheet. */
class SchematicNetGeometry(val wires: List<List<Point>>, val points: List<Point>)

/**
 * Stateless schematic sheet view. Same gestures as the board ([viewGestures]); taps report sheet
 * coordinates (µm, Y up). Texts are drawn upright in screen space and skipped when too small.
 */
@Composable
fun SchematicCanvas(
    sheet: SheetScene,
    transform: ViewTransform?,
    onTransform: (ViewTransform) -> Unit,
    onViewport: (width: Int, height: Int) -> Unit,
    onTap: (x: Double, y: Double, toleranceUm: Double, fromStylus: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    colors: SchematicColors = SchematicColors(),
    focus: SchematicFocus = SchematicFocus(),
    rotationGesture: Boolean = true,
    onLongPress: ((Offset) -> Boolean)? = null,
    onLongPressDrag: (Offset) -> Unit = {},
    onLongPressRelease: (Offset) -> Unit = {},
    onStylusSeen: () -> Unit = {},
    /** Desktop: mouse double click (fit). */
    onDoubleTap: (() -> Unit)? = null,
) {
    val gestures = rememberViewGestureState()
    gestures.transform = transform
    gestures.onTransform = onTransform
    gestures.onTap = onTap
    gestures.rotation = rotationGesture
    gestures.onLongPress = onLongPress
    gestures.onLongPressDrag = onLongPressDrag
    gestures.onLongPressRelease = onLongPressRelease
    gestures.onStylusSeen = onStylusSeen
    gestures.onDoubleTap = onDoubleTap

    val matrix = remember { Matrix() }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true } }
    val selection = remember(sheet, focus.selected) { boxes(sheet, focus.selected) }
    val highlight = remember(sheet, focus.highlighted) { boxes(sheet, focus.highlighted) }
    val faded = remember(sheet, focus.faded) { boxes(sheet, focus.faded) }
    val marks = remember(sheet, focus.marks) {
        focus.marks.values.groupBy { it.argb }.mapValues { (_, ms) ->
            val refs = ms.map { it.refDes.uppercase() }.toSet()
            boxes(sheet, refs)
        }
    }
    val netPath = remember(focus.net) {
        focus.net?.let { g -> Path().apply { g.wires.forEach { with(SheetScene) { addPolyline(it) } } } }
    }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(focus.selected) { if (focus.selected.isNotEmpty()) { pulse.snapTo(1f); pulse.animateTo(0f, tween(SELECTION_PULSE_MS)) } }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { onViewport(it.width, it.height) }
            .viewGestures(gestures),
    ) {
        drawRect(Color(colors.background))
        val t = transform ?: return@Canvas
        t.writeTo(matrix)
        val px = (1.0 / t.scale).toFloat()
        val paper = Color(colors.paper(sheet.sheet.paperArgb))
        withTransform({ transform(matrix) }) {
            drawPath(Path().apply { addRect(sheet.extent) }, paper, style = Fill)
            for ((argb, path) in sheet.fills) drawPath(path, Color(colors.fill(argb)), style = Fill)
            // Status paint under the lines, translucent so the symbol stays readable.
            for ((argb, path) in marks) drawPath(path, Color(argb).copy(alpha = 0.45f), style = Fill)
            for ((k, path) in sheet.strokes) drawPath(path, Color(colors.stroke(k.argb)), style = Stroke(maxOf(k.widthUm.toFloat(), px), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for ((k, path) in sheet.wires) drawPath(path, Color(colors.stroke(k.argb)), style = Stroke(maxOf(k.widthUm.toFloat(), 1.5f * px), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for ((argb, path) in sheet.dots) drawPath(path, Color(colors.stroke(argb)), style = Fill)
            if (focus.faded.isNotEmpty()) drawPath(faded, paper.copy(alpha = 1f - colors.missingAlpha), style = Fill)
            if (netPath != null) {
                drawPath(Path().apply { addRect(sheet.extent) }, paper.copy(alpha = 0.6f), style = Fill)
                drawPath(netPath, Color(colors.net), style = Stroke(4f * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
                for (p in focus.net?.points.orEmpty()) drawCircle(Color(colors.net), radius = 5f * px, center = Offset(p.x.toFloat(), p.y.toFloat()))
            }
            if (focus.highlighted.isNotEmpty()) {
                drawPath(highlight, Color(colors.highlight).copy(alpha = 0.16f), style = Fill)
                drawPath(highlight, Color(colors.highlight), style = Stroke(2.5f * px, join = StrokeJoin.Round))
            }
            if (focus.selected.isNotEmpty()) {
                val p = pulse.value
                drawPath(selection, Color(colors.selected).copy(alpha = 0.22f + 0.18f * p), style = Stroke((6f + 5f * p) * px, join = StrokeJoin.Round))
                drawPath(selection, Color(colors.selected), style = Stroke(3.5f * px, join = StrokeJoin.Round))
            }
        }
        drawTexts(sheet, t, colors, focus, textPaint)
    }
}

/** Selection / highlight boxes around the symbols of [refs], a little larger than the symbol. */
private fun boxes(sheet: SheetScene, refs: Set<String>): Path = Path().apply {
    if (refs.isEmpty()) return@apply
    for (s in sheet.sheet.symbols) if (s.refDes.uppercase() in refs) addRect(s.bounds.inflate(400))
}

private const val MIN_TEXT_PX = 3.5f

private fun DrawScope.drawTexts(sheet: SheetScene, t: ViewTransform, colors: SchematicColors, focus: SchematicFocus, paint: Paint) {
    val canvas = drawContext.canvas.nativeCanvas
    val w = size.width; val h = size.height
    val texts = sheet.layout.texts
    val owners = sheet.layout.textOwners
    for (i in texts.indices) {
        val tx = texts[i]
        val sizePx = (tx.sizeUm * t.scale).toFloat()
        if (sizePx < MIN_TEXT_PX || tx.text.isEmpty()) continue
        val sx = t.toScreenX(tx.position.x.toDouble(), tx.position.y.toDouble()).toFloat()
        val sy = t.toScreenY(tx.position.x.toDouble(), tx.position.y.toDouble()).toFloat()
        val reach = sizePx * (tx.text.length + 1)
        if (sx < -reach || sy < -reach || sx > w + reach || sy > h + reach) continue
        // Direction of the text baseline on screen (sheet angle through the view's rotation and Y flip).
        val a = Math.toRadians(tx.rotationDeg)
        val ex = t.toScreenX(tx.position.x + 1000 * kotlin.math.cos(a), tx.position.y + 1000 * kotlin.math.sin(a)) - sx
        val ey = t.toScreenY(tx.position.x + 1000 * kotlin.math.cos(a), tx.position.y + 1000 * kotlin.math.sin(a)) - sy
        val angle = Math.toDegrees(atan2(ey, ex)).toFloat()

        paint.textSize = sizePx
        paint.typeface = typefaceFor(tx)
        paint.textAlign = when (tx.halign) { HAlign.LEFT -> Paint.Align.LEFT; HAlign.CENTER -> Paint.Align.CENTER; HAlign.RIGHT -> Paint.Align.RIGHT }
        var argb = colors.stroke(tx.argb ?: SchematicLayout.TEXT_INK)
        val owner = owners[i]
        if (owner != null && owner.uppercase() in focus.faded) argb = (argb and 0x00FFFFFF) or ((255 * colors.missingAlpha).toInt() shl 24)
        if (focus.net != null) argb = (argb and 0x00FFFFFF) or (0x70 shl 24)
        paint.color = argb
        val baseline = when (tx.valign) { VAlign.BOTTOM -> -0.2f * sizePx; VAlign.MIDDLE -> 0.32f * sizePx; VAlign.TOP -> 0.8f * sizePx }
        canvas.save()
        canvas.translate(sx, sy)
        canvas.rotate(angle)
        canvas.drawText(tx.text, 0f, baseline, paint)
        tx.overline?.let { over -> drawOverline(canvas, tx, over, paint, baseline, sizePx) }
        canvas.restore()
    }
}

private fun drawOverline(canvas: NativeCanvas, tx: SchText, over: List<Boolean>, paint: Paint, baseline: Float, sizePx: Float) {
    val total = paint.measureText(tx.text)
    val x0 = when (paint.textAlign) { Paint.Align.CENTER -> -total / 2; Paint.Align.RIGHT -> -total; else -> 0f }
    val y = baseline - 0.78f * sizePx
    val stroke = paint.strokeWidth
    paint.strokeWidth = maxOf(1f, sizePx * 0.07f)
    var i = 0
    while (i < over.size) {
        if (!over[i]) { i++; continue }
        var j = i
        while (j < over.size && over[j]) j++
        val a = x0 + paint.measureText(tx.text, 0, i.coerceAtMost(tx.text.length))
        val b = x0 + paint.measureText(tx.text, 0, j.coerceAtMost(tx.text.length))
        canvas.drawLine(a, y, b, y, paint)
        i = j
    }
    paint.strokeWidth = stroke
}

private val typefaces = HashMap<String, Typeface>()

private fun typefaceFor(t: SchText): Typeface {
    val key = "${t.fontName}|${t.bold}|${t.italic}"
    return typefaces.getOrPut(key) {
        val n = t.fontName.orEmpty().lowercase()
        val family = when {
            "courier" in n || "mono" in n || "consol" in n -> Typeface.MONOSPACE
            "times" in n || "georgia" in n || ("serif" in n && "sans" !in n) -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
        val style = when {
            t.bold && t.italic -> Typeface.BOLD_ITALIC
            t.bold -> Typeface.BOLD
            t.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        Typeface.create(family, style)
    }
}
