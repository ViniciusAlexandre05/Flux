package com.pcbsolderview.render

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side

data class BoardColors(
    val background: Color = Color(0xFF0B0F14),
    val boardFill: Color = Color(0xFF12301F),
    val outline: Color = Color(0xFFE8E26A),
    val topBody: Color = Color(0xFF3FA9F5),
    val bottomBody: Color = Color(0xFFE0533D),
    val label: Color = Color(0xFFFFFFFF),
    val selected: Color = Color(0xFFFFD400),
    val highlight: Color = Color(0xFFFF2BD6),
)

/**
 * Stateless board view. The caller owns [transform] and receives every change via [onTransform].
 *
 * @param visibleSide side facing the viewer; components on the other side are dimmed.
 */
@Composable
fun BoardCanvas(
    scene: BoardScene,
    transform: ViewTransform?,
    onTransform: (ViewTransform) -> Unit,
    onViewport: (width: Int, height: Int) -> Unit,
    onTap: (Component?) -> Unit,
    modifier: Modifier = Modifier,
    visibleSide: Side = Side.TOP,
    selected: Component? = null,
    highlighted: Collection<Component> = emptyList(),
    colors: BoardColors = BoardColors(),
) {
    val highlightPath = remember(scene, highlighted) { scene.pathFor(highlighted) }
    val selectedPath = remember(scene, selected) { scene.pathFor(listOfNotNull(selected)) }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val matrix = remember { Matrix() }
    val outlinePath = remember(scene) {
        if (scene.hasOutline) scene.outline.also { it.fillType = PathFillType.EvenOdd }
        else androidx.compose.ui.graphics.Path().apply { with(BoardScene) { addRect(scene.extent) } }
    }

    // Latest values for the gesture coroutines (pointerInput keys stay stable).
    val latest = remember { LatestHolder() }
    latest.transform = transform
    latest.onTransform = onTransform
    latest.onTap = onTap
    latest.side = visibleSide
    latest.scene = scene

    Canvas(
        modifier
            .onSizeChanged { onViewport(it.width, it.height) }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val t = latest.transform ?: return@detectTransformGestures
                    latest.onTransform(
                        t.panBy(pan.x.toDouble(), pan.y.toDouble())
                            .zoomBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble()),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        val t = latest.transform ?: return@detectTapGestures
                        latest.onTransform(t.zoomBy(2.0, p.x.toDouble(), p.y.toDouble()))
                    },
                    onTap = { p ->
                        val t = latest.transform ?: return@detectTapGestures
                        val (bx, by) = t.toBoard(p.x.toDouble(), p.y.toDouble())
                        val tolerance = TOUCH_SLOP_PX / t.scale
                        latest.onTap(latest.scene?.index?.hitTest(bx, by, latest.side, tolerance))
                    },
                )
            },
    ) {
        drawRect(colors.background)
        val t = transform ?: return@Canvas
        t.writeTo(matrix)
        val px = 1f / t.scale.toFloat() // one screen pixel in board units

        withTransform({ transform(matrix) }) {
            drawPath(outlinePath, colors.boardFill, style = Fill)
            drawPath(outlinePath, colors.outline, style = Stroke(width = 2f * px))

            val (front, back) = if (visibleSide == Side.TOP) scene.componentsTop to scene.componentsBottom
            else scene.componentsBottom to scene.componentsTop
            val frontColor = if (visibleSide == Side.TOP) colors.topBody else colors.bottomBody
            val backColor = if (visibleSide == Side.TOP) colors.bottomBody else colors.topBody

            drawPath(back, backColor.copy(alpha = 0.10f), style = Fill)
            drawPath(back, backColor.copy(alpha = 0.35f), style = Stroke(width = 1f * px))
            drawPath(front, frontColor.copy(alpha = 0.30f), style = Fill)
            drawPath(front, frontColor, style = Stroke(width = 1.5f * px))

            if (highlighted.isNotEmpty()) {
                drawPath(highlightPath, colors.highlight.copy(alpha = 0.45f), style = Fill)
                drawPath(highlightPath, colors.highlight, style = Stroke(width = 2.5f * px))
            }
            if (selected != null) {
                drawPath(selectedPath, colors.selected.copy(alpha = 0.35f), style = Fill)
                drawPath(selectedPath, colors.selected, style = Stroke(width = 3f * px))
            }
        }
        drawLabels(scene, t, visibleSide, labelPaint, colors)
    }
}

private class LatestHolder {
    var transform: ViewTransform? = null
    var onTransform: (ViewTransform) -> Unit = {}
    var onTap: (Component?) -> Unit = {}
    var side: Side = Side.TOP
    var scene: BoardScene? = null
}

const val TOUCH_SLOP_PX = 24.0
private const val MIN_LABEL_PX = 9f
private const val MAX_LABEL_PX = 42f

/** Designators drawn upright in screen space; level-of-detail: skipped when too small to read. */
private fun DrawScope.drawLabels(scene: BoardScene, t: ViewTransform, side: Side, paint: Paint, colors: BoardColors) {
    val w = size.width.toDouble(); val h = size.height.toDouble()
    val canvas = drawContext.canvas.nativeCanvas
    for (c in scene.board.components) {
        val r = t.toScreen(c.bounds)
        if (!r.intersects(w, h)) continue
        val len = c.refDes.length.coerceAtLeast(1)
        val size = minOf(r.height * 0.6, r.width / (len * 0.62), MAX_LABEL_PX.toDouble()).toFloat()
        if (size < MIN_LABEL_PX) continue
        paint.textSize = size
        paint.color = if (c.side == side) colors.label.toArgb() else colors.label.copy(alpha = 0.35f).toArgb()
        canvas.drawText(c.refDes, r.centerX.toFloat(), (r.centerY + size * 0.35).toFloat(), paint)
    }
}

fun ViewTransform.writeTo(m: Matrix) {
    m.reset()
    val v = m.values
    v[Matrix.ScaleX] = a.toFloat()
    v[Matrix.SkewY] = b.toFloat()
    v[Matrix.SkewX] = c.toFloat()
    v[Matrix.ScaleY] = d.toFloat()
    v[Matrix.TranslateX] = tx.toFloat()
    v[Matrix.TranslateY] = ty.toFloat()
}
