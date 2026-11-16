package com.pcbsolderview.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.toArgb
import com.pcbsolderview.model.Side
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Surface

/**
 * Small top-view picture of the board for the recent-boards list. Uses the scene's cached paths,
 * so it costs one draw. Desktop: drawn with Skia instead of android.graphics.
 */
fun BoardScene.thumbnail(maxPx: Int = 480, colors: BoardColors = BoardColors()): Image {
    val e = extent
    val aspect = (e.width.toDouble() / e.height.coerceAtLeast(1)).coerceIn(0.25, 4.0)
    val w = if (aspect >= 1) maxPx else (maxPx * aspect).toInt().coerceAtLeast(16)
    val h = if (aspect >= 1) (maxPx / aspect).toInt().coerceAtLeast(16) else maxPx
    val surface = Surface.makeRasterN32Premul(w, h)
    val c = surface.canvas
    c.clear(colors.background.toArgb())

    val t = ViewTransform.fitted(e, w.toDouble(), h.toDouble(), paddingPx = 6.0)
    c.concat(Matrix33(t.a.toFloat(), t.c.toFloat(), t.tx.toFloat(), t.b.toFloat(), t.d.toFloat(), t.ty.toFloat(), 0f, 0f, 1f))
    val px = (1.0 / t.scale).toFloat()
    val fill = org.jetbrains.skia.Paint().apply { isAntiAlias = true; mode = PaintMode.FILL }
    val stroke = org.jetbrains.skia.Paint().apply { isAntiAlias = true; mode = PaintMode.STROKE; strokeCap = PaintStrokeCap.ROUND; strokeJoin = PaintStrokeJoin.ROUND }

    val outline = if (hasOutline) outline.asSkiaPath() else org.jetbrains.skia.Path().apply {
        addRect(org.jetbrains.skia.Rect.makeLTRB(e.minX.toFloat(), e.minY.toFloat(), e.maxX.toFloat(), e.maxY.toFloat()))
    }
    fill.color = colors.boardFill.toArgb(); c.drawPath(outline, fill)

    if (hasGeometry) {
        for (ls in drawOrder(Side.TOP)) {
            val alpha = if (ls.layer.side == Side.BOTTOM) 0.45f else 0.92f
            val col = ls.color.copy(alpha = alpha).toArgb()
            fill.color = col; c.drawPath(ls.fill.asSkiaPath(), fill)
            stroke.color = col
            for ((wUm, path) in ls.strokes) { stroke.strokeWidth = maxOf(wUm, px); c.drawPath(path.asSkiaPath(), stroke) }
        }
        fill.color = colors.multiLayer.toArgb(); c.drawPath(multiLayer.asSkiaPath(), fill)
        fill.color = colors.background.toArgb(); c.drawPath(holes.asSkiaPath(), fill)
    } else {
        // Pick & Place only: component boxes.
        fill.color = colors.topBody.copy(alpha = 0.55f).toArgb(); c.drawPath(componentsTop.asSkiaPath(), fill)
        fill.color = colors.bottomBody.copy(alpha = 0.35f).toArgb(); c.drawPath(componentsBottom.asSkiaPath(), fill)
    }
    stroke.color = Color(0xFFE8D27A).toArgb(); stroke.strokeWidth = 1.5f * px
    c.drawPath(outline, stroke)
    return surface.makeImageSnapshot()
}
