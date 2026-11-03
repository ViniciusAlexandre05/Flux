package com.pcbsolderview.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import com.pcbsolderview.model.Side

/**
 * Small top-view picture of the board for the recent-boards list. Uses the scene's cached paths,
 * so it costs one draw.
 */
fun BoardScene.thumbnail(maxPx: Int = 480, colors: BoardColors = BoardColors()): Bitmap {
    val e = extent
    val aspect = (e.width.toDouble() / e.height.coerceAtLeast(1)).coerceIn(0.25, 4.0)
    val w = if (aspect >= 1) maxPx else (maxPx * aspect).toInt().coerceAtLeast(16)
    val h = if (aspect >= 1) (maxPx / aspect).toInt().coerceAtLeast(16) else maxPx
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(colors.background.toArgb())

    val t = ViewTransform.fitted(e, w.toDouble(), h.toDouble(), paddingPx = 6.0)
    val m = android.graphics.Matrix().apply {
        setValues(floatArrayOf(t.a.toFloat(), t.c.toFloat(), t.tx.toFloat(), t.b.toFloat(), t.d.toFloat(), t.ty.toFloat(), 0f, 0f, 1f))
    }
    c.concat(m)
    val px = (1.0 / t.scale).toFloat()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    val outline = if (hasOutline) outline.asAndroidPath() else android.graphics.Path().apply {
        addRect(e.minX.toFloat(), e.minY.toFloat(), e.maxX.toFloat(), e.maxY.toFloat(), android.graphics.Path.Direction.CCW)
    }
    fill.color = colors.boardFill.toArgb(); c.drawPath(outline, fill)

    if (hasGeometry) {
        for (ls in drawOrder(Side.TOP)) {
            val alpha = if (ls.layer.side == Side.BOTTOM) 0.45f else 0.92f
            val col = ls.color.copy(alpha = alpha).toArgb()
            fill.color = col; c.drawPath(ls.fill.asAndroidPath(), fill)
            stroke.color = col
            for ((wUm, path) in ls.strokes) { stroke.strokeWidth = maxOf(wUm, px); c.drawPath(path.asAndroidPath(), stroke) }
        }
        fill.color = colors.multiLayer.toArgb(); c.drawPath(multiLayer.asAndroidPath(), fill)
        fill.color = colors.background.toArgb(); c.drawPath(holes.asAndroidPath(), fill)
    } else {
        // Pick & Place only: component boxes.
        fill.color = colors.topBody.copy(alpha = 0.55f).toArgb(); c.drawPath(componentsTop.asAndroidPath(), fill)
        fill.color = colors.bottomBody.copy(alpha = 0.35f).toArgb(); c.drawPath(componentsBottom.asAndroidPath(), fill)
    }
    stroke.color = Color(0xFFE8D27A).toArgb(); stroke.strokeWidth = 1.5f * px
    c.drawPath(outline, stroke)
    return bmp
}
