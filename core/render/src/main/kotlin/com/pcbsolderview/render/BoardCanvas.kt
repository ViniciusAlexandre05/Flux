package com.pcbsolderview.render

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.BoardScene.Companion.addCenterline
import com.pcbsolderview.render.BoardScene.Companion.addRect
import kotlin.math.abs

data class BoardColors(
    val background: Color = Color(0xFF0B0F14),
    val boardFill: Color = Color(0xFF12301F),
    val outline: Color = Color(0xFFE8E26A),
    val topBody: Color = Color(0xFF3FA9F5),
    val bottomBody: Color = Color(0xFFE0533D),
    val label: Color = Color(0xFFFFFFFF),
    val selected: Color = Color(0xFFFFD400),
    val highlight: Color = Color(0xFFFF2BD6),
    val multiLayer: Color = Color(0xFFC0C0C0),
    val net: Color = Color(0xFF00E5FF),
)

/**
 * Stateless board view. The caller owns [transform] and receives every change via [onTransform].
 *
 * Gestures: one finger pans (or draws with [CanvasTool.PEN]); two fingers pan + pinch-zoom and,
 * after a deliberate twist of [ROTATION_THRESHOLD_DEG], rotate. Taps report board coordinates.
 */
@Composable
fun BoardCanvas(
    scene: BoardScene,
    transform: ViewTransform?,
    onTransform: (ViewTransform) -> Unit,
    onViewport: (width: Int, height: Int) -> Unit,
    /** Board point (µm) and touch tolerance (µm). */
    onTap: (x: Double, y: Double, toleranceUm: Double) -> Unit,
    modifier: Modifier = Modifier,
    visibleSide: Side = Side.TOP,
    options: DisplayOptions = DisplayOptions(hiddenLayers = scene.defaultHiddenLayers),
    selected: Component? = null,
    highlighted: Collection<Component> = emptyList(),
    selectedNet: String? = null,
    marks: Map<String, ComponentMark> = emptyMap(),
    strokes: List<InkStroke> = emptyList(),
    tool: CanvasTool = CanvasTool.NAVIGATE,
    penArgb: Int = 0xFFFFFFFF.toInt(),
    penWidthPx: Float = 4f,
    onStroke: (points: List<Pair<Double, Double>>, widthUm: Float) -> Unit = { _, _ -> },
    colors: BoardColors = BoardColors(),
) {
    val highlightPath = remember(scene, highlighted) { scene.pathFor(highlighted) }
    val selectedPath = remember(scene, selected) { scene.pathFor(listOfNotNull(selected)) }
    val netGeometry = remember(scene, selectedNet, options.hiddenLayers) { selectedNet?.let { scene.netGeometry(it, options.hiddenLayers) } }
    val markPaths = remember(scene, marks) {
        marks.values.groupBy { it.argb }.mapValues { (_, ms) ->
            Path().apply { ms.forEach { m -> scene.board.component(m.refDes)?.let { addRect(it.bounds) } } }
        }
    }
    val strokePaths = remember(strokes) { strokes.map { s -> s to Path().apply { addCenterline(s.points) } } }
    val drawOrder = remember(scene, visibleSide, options.hiddenLayers) { scene.drawOrder(visibleSide, options.hiddenLayers) }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // Dark halo keeps designators readable over copper.
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    val fpsPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; color = android.graphics.Color.GREEN; typeface = Typeface.MONOSPACE } }
    val stats = remember { FrameStats() }
    val matrix = remember { Matrix() }
    val outlinePath = remember(scene) {
        if (scene.hasOutline) scene.outline.also { it.fillType = PathFillType.EvenOdd }
        else Path().apply { addRect(scene.extent) }
    }
    val livePoints = remember { mutableStateListOf<Pair<Double, Double>>() }

    // Latest values for the gesture coroutine (pointerInput key stays stable).
    val latest = remember { LatestHolder() }
    latest.transform = transform
    latest.onTransform = onTransform
    latest.onTap = onTap
    latest.tool = tool
    latest.rotation = options.rotationGesture
    latest.penWidthPx = penWidthPx
    latest.onStroke = onStroke

    Canvas(
        modifier
            .onSizeChanged { onViewport(it.width, it.height) }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var drawing = latest.tool == CanvasTool.PEN
                    if (drawing) { livePoints.clear(); latest.transform?.let { livePoints += it.toBoard(down.position.x.toDouble(), down.position.y.toDouble()) } }
                    var moved = false
                    var multi = false
                    var travel = 0f
                    var twist = 0f
                    var rotating = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            multi = true
                            if (drawing) { drawing = false; livePoints.clear() } // second finger: navigate instead
                        }
                        val t = latest.transform ?: continue
                        if (drawing) {
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            travel += ch.positionChange().getDistance()
                            if (travel > slop / 2) moved = true
                            livePoints += t.toBoard(ch.position.x.toDouble(), ch.position.y.toDouble())
                            ch.consume()
                            continue
                        }
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val rot = event.calculateRotation()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (!moved) {
                            travel += pan.getDistance()
                            if (travel > slop || abs(1 - zoom) > 0.02f || multi) moved = true
                        }
                        if (!moved) continue
                        twist += rot
                        if (!rotating && multi && latest.rotation && abs(twist) > ROTATION_THRESHOLD_DEG) rotating = true
                        var next = t.panBy(pan.x.toDouble(), pan.y.toDouble())
                        if (centroid != Offset.Unspecified) {
                            next = next.zoomBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                            if (rotating) next = next.rotateBy(rot.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                        }
                        latest.onTransform(next)
                        event.changes.forEach { it.consume() }
                    }
                    val t = latest.transform
                    if (drawing && moved && livePoints.size >= 2 && t != null) {
                        latest.onStroke(livePoints.toList(), (latest.penWidthPx / t.scale).toFloat())
                    } else if (!moved && !multi && t != null) {
                        val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                        latest.onTap(bx, by, TOUCH_SLOP_PX / t.scale)
                    }
                    livePoints.clear()
                }
            },
    ) {
        val start = System.nanoTime()
        drawRect(colors.background)
        val t = transform ?: return@Canvas
        t.writeTo(matrix)
        val px = 1f / t.scale.toFloat() // one screen pixel in board units

        withTransform({ transform(matrix) }) {
            drawPath(outlinePath, colors.boardFill, style = Fill)

            if (scene.hasGeometry) {
                for (ls in drawOrder) {
                    val alpha = if (ls.layer.side == null || ls.layer.side == visibleSide) 0.92f else 0.45f
                    val c = ls.color.copy(alpha = alpha)
                    drawPath(ls.fill, c, style = Fill)
                    for ((w, path) in ls.strokes) {
                        drawPath(path, c, style = Stroke(width = maxOf(w, px), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                if (options.showMultiLayer) drawPath(scene.multiLayer, colors.multiLayer, style = Fill)
                drawPath(scene.holes, colors.background, style = Fill)
            }
            drawPath(outlinePath, colors.outline, style = Stroke(width = 2f * px))

            netGeometry?.let { g ->
                // Dim everything, then draw the net on top.
                drawPath(outlinePath, Color.Black.copy(alpha = 0.55f), style = Fill)
                drawPath(g.fill, colors.net, style = Fill)
                for ((w, path) in g.strokes) drawPath(path, colors.net, style = Stroke(width = maxOf(w, 2 * px), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            val (front, back) = if (visibleSide == Side.TOP) scene.componentsTop to scene.componentsBottom
            else scene.componentsBottom to scene.componentsTop
            val frontColor = if (visibleSide == Side.TOP) colors.topBody else colors.bottomBody
            val backColor = if (visibleSide == Side.TOP) colors.bottomBody else colors.topBody
            if (options.showComponentOutlines) {
                if (scene.hasGeometry) {
                    drawPath(front, frontColor.copy(alpha = 0.45f), style = Stroke(width = 1f * px))
                } else {
                    drawPath(back, backColor.copy(alpha = 0.10f), style = Fill)
                    drawPath(back, backColor.copy(alpha = 0.35f), style = Stroke(width = 1f * px))
                    drawPath(front, frontColor.copy(alpha = 0.30f), style = Fill)
                    drawPath(front, frontColor, style = Stroke(width = 1.5f * px))
                }
            }

            if (options.showAnnotations) {
                for ((argb, path) in markPaths) {
                    val c = Color(argb)
                    drawPath(path, c.copy(alpha = 0.55f), style = Fill)
                    drawPath(path, c, style = Stroke(width = 2f * px))
                }
            }
            if (highlighted.isNotEmpty()) {
                drawPath(highlightPath, colors.highlight.copy(alpha = 0.45f), style = Fill)
                drawPath(highlightPath, colors.highlight, style = Stroke(width = 2.5f * px))
            }
            if (selected != null) {
                drawPath(selectedPath, colors.selected.copy(alpha = 0.35f), style = Fill)
                drawPath(selectedPath, colors.selected, style = Stroke(width = 3f * px))
            }
            if (options.showAnnotations) {
                for ((s, path) in strokePaths) {
                    drawPath(path, Color(s.argb), style = Stroke(width = s.widthUm, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (livePoints.size >= 1) {
                val live = Path().apply { addCenterline(livePoints) }
                drawPath(live, Color(penArgb), style = Stroke(width = penWidthPx * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (options.showDesignators) drawLabels(scene, t, visibleSide, labelPaint, colors)
        if (options.showFps) {
            val fps = stats.frame(start, System.nanoTime())
            drawContext.canvas.nativeCanvas.drawText(fps, 16f, size.height - 16f, fpsPaint)
        }
    }
}

private class LatestHolder {
    var transform: ViewTransform? = null
    var onTransform: (ViewTransform) -> Unit = {}
    var onTap: (Double, Double, Double) -> Unit = { _, _, _ -> }
    var tool: CanvasTool = CanvasTool.NAVIGATE
    var rotation: Boolean = true
    var penWidthPx: Float = 4f
    var onStroke: (List<Pair<Double, Double>>, Float) -> Unit = { _, _ -> }
}

/** Frame-rate meter: frames drawn in the last second and average draw time. */
private class FrameStats {
    private val times = ArrayDeque<Long>()
    private var drawNs = 0.0
    fun frame(startNs: Long, endNs: Long): String {
        times.addLast(startNs)
        while (times.isNotEmpty() && startNs - times.first() > 1_000_000_000L) times.removeFirst()
        drawNs = drawNs * 0.9 + (endNs - startNs) * 0.1
        return "%d fps  draw %.1f ms".format(times.size, drawNs / 1e6)
    }
}

const val TOUCH_SLOP_PX = 24.0
const val ROTATION_THRESHOLD_DEG = 12f
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
