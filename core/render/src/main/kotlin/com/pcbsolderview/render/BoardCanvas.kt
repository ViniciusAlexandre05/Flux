package com.pcbsolderview.render

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.BoardScene.Companion.addCenterline
import com.pcbsolderview.render.BoardScene.Companion.addRect
import com.pcbsolderview.render.BoardScene.Companion.addRing
import com.pcbsolderview.model.outline

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
 * The gestures themselves live in [viewGestures], shared with the schematic view.
 */
@Composable
fun BoardCanvas(
    scene: BoardScene,
    transform: ViewTransform?,
    onTransform: (ViewTransform) -> Unit,
    onViewport: (width: Int, height: Int) -> Unit,
    /** Board point (µm), touch tolerance (µm), and whether the tap came from the S Pen. */
    onTap: (x: Double, y: Double, toleranceUm: Double, fromStylus: Boolean) -> Unit,
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
    /**
     * Finger held still on [Offset] (screen px). Return true to take over the gesture: the canvas
     * then reports finger moves and the release instead of panning.
     */
    onLongPress: ((Offset) -> Boolean)? = null,
    onLongPressDrag: (Offset) -> Unit = {},
    onLongPressRelease: (Offset) -> Unit = {},
    /** First time an S Pen touches or hovers. */
    onStylusSeen: () -> Unit = {},
    /** S Pen erasing at a board point with a tolerance (µm). */
    onErase: (x: Double, y: Double, toleranceUm: Double) -> Unit = { _, _, _ -> },
    /** S Pen hovering at a screen point, or null when it leaves / touches. */
    onHover: (Offset?) -> Unit = {},
    colors: BoardColors = BoardColors(),
) {
    val highlightPath = remember(scene, highlighted) { scene.pathFor(highlighted) }
    val highlightPadsPath = remember(scene, highlighted) {
        Path().apply { highlighted.forEach { c -> scene.board.padsOf(c).forEach { addRing(it.outline()) } } }
    }
    val highlightedRefs = remember(highlighted) { highlighted.map { it.refDes }.toSet() }
    // Status paint of highlighted parts, redrawn above the focus fade so it stays visible.
    val highlightedMarkPaths = remember(scene, highlighted, marks) {
        highlighted.mapNotNull { c -> marks[c.refDes]?.let { it.argb to c } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, cs) -> Path().apply { cs.forEach { addRect(it.bounds) } } }
    }
    val selectedPath = remember(scene, selected) { scene.pathFor(listOfNotNull(selected)) }
    val selectedPadsPath = remember(scene, selected) {
        Path().apply { selected?.let { c -> scene.board.padsOf(c).forEach { addRing(it.outline()) } } }
    }
    // Brief pulse when a part is selected, so the eye finds it.
    val selectionPulse = remember { Animatable(0f) }
    LaunchedEffect(selected?.refDes) {
        if (selected != null) { selectionPulse.snapTo(1f); selectionPulse.animateTo(0f, tween(durationMillis = 900)) }
    }
    val badgePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    }
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
    // Latest values for the gesture coroutine (its pointerInput key stays stable).
    val gestures = rememberViewGestureState()
    gestures.transform = transform
    gestures.onTransform = onTransform
    gestures.onTap = onTap
    gestures.tool = tool
    gestures.rotation = options.rotationGesture
    gestures.penOnly = options.penOnlyDrawing
    gestures.penWidthPx = penWidthPx
    gestures.onStroke = onStroke
    gestures.onErase = onErase
    gestures.onLongPress = onLongPress
    gestures.onLongPressDrag = onLongPressDrag
    gestures.onLongPressRelease = onLongPressRelease
    gestures.onHover = onHover
    gestures.onStylusSeen = onStylusSeen
    val livePoints = gestures.livePoints

    Canvas(
        modifier
            // Canvas does not clip by default; without this a zoomed board paints over the top bar.
            .clipToBounds()
            .onSizeChanged { onViewport(it.width, it.height) }
            .viewGestures(gestures),
    ) {
        val start = System.nanoTime()
        drawRect(colors.background)
        val t = transform ?: return@Canvas
        t.writeTo(matrix)
        val px = 1f / t.scale.toFloat() // one screen pixel in board units

        val realistic = options.viewMode == ViewMode.REALISTIC
        val mask = Color(options.maskArgb)

        withTransform({ transform(matrix) }) {
            if (options.grid != GridStyle.NONE) drawGrid(t, options.grid, options.gridStepUm, options.gridAuto, colors.background, px)

            drawPath(outlinePath, if (realistic) mask else colors.boardFill, style = Fill)

            if (realistic) {
                // Assembled-board look: mask, exposed pads, silkscreen and component bodies only.
                val g = scene.realistic(visibleSide)
                if (scene.hasGeometry) {
                    // Copper under the mask: same hue, a darker tone (like a real board).
                    val copperTone = BodyStyle.copperUnderMask(mask)
                    for (ls in g.copper) {
                        drawPath(ls.fill, copperTone, style = Fill)
                        for ((w, path) in ls.strokes) drawPath(path, copperTone, style = Stroke(width = maxOf(w, px), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawPath(g.pads, BodyStyle.PadFinish, style = Fill)
                    drawPath(scene.viaRings, BodyStyle.PadFinish, style = Fill)
                    drawPath(scene.holes, colors.background, style = Fill)
                    val ink = BodyStyle.silkFor(mask)
                    for (ls in g.silk) {
                        drawPath(ls.fill, ink, style = Fill)
                        for ((w, path) in ls.strokes) drawPath(path, ink, style = Stroke(width = maxOf(w, px), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                // Soft drop shadow (fixed screen offset, so it falls the same way at any rotation).
                val (x0, y0) = t.toBoard(0.0, 0.0)
                val (x1, y1) = t.toBoard(SHADOW_DX_PX, SHADOW_DY_PX)
                withTransform({ translate((x1 - x0).toFloat(), (y1 - y0).toFloat()) }) {
                    drawPath(g.allBodies, Color.Black.copy(alpha = 0.38f), style = Fill)
                }
                for ((c, path) in g.bodies) {
                    drawPath(path, c, style = Fill)
                    // Bevel: light rim + dark edge give the body a little volume.
                    drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(width = 1.6f * px))
                    drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(width = 0.8f * px))
                }
                drawPath(outlinePath, lerp(mask, Color.Black, 0.45f), style = Stroke(width = 2f * px))
            } else if (scene.hasGeometry) {
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
            if (!realistic) drawPath(outlinePath, colors.outline, style = Stroke(width = 2f * px))

            netGeometry?.let { g ->
                // Fade everything toward the background, then draw the net on top.
                drawPath(outlinePath, colors.background.copy(alpha = FOCUS_FADE), style = Fill)
                drawPath(g.fill, colors.net, style = Fill)
                for ((w, path) in g.strokes) drawPath(path, colors.net, style = Stroke(width = maxOf(w, 2 * px), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            val (front, back) = if (visibleSide == Side.TOP) scene.componentsTop to scene.componentsBottom
            else scene.componentsBottom to scene.componentsTop
            val frontColor = if (visibleSide == Side.TOP) colors.topBody else colors.bottomBody
            val backColor = if (visibleSide == Side.TOP) colors.bottomBody else colors.topBody
            if (options.showComponentOutlines && !realistic) {
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
                // Nearly opaque, with a dark edge: the board under the part barely tints the status colour.
                for ((argb, path) in markPaths) {
                    drawPath(path, Color(argb).copy(alpha = MARK_ALPHA), style = Fill)
                    drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 1.5f * px))
                }
            }
            if (highlighted.isNotEmpty()) {
                // Focus: fade the rest of the board (copper, outlines, status paint) toward the
                // background, then redraw only the highlighted parts — pads solid, body tinted.
                drawPath(outlinePath, colors.background.copy(alpha = FOCUS_FADE), style = Fill)
                drawPath(highlightPath, colors.highlight.copy(alpha = 0.22f), style = Fill)
                // Highlighted parts keep their status colour (OK green, Problem red, …).
                if (options.showAnnotations) {
                    for ((argb, path) in highlightedMarkPaths) drawPath(path, Color(argb).copy(alpha = MARK_ALPHA), style = Fill)
                }
                drawPath(highlightPadsPath, colors.highlight, style = Fill)
                drawPath(highlightPath, colors.highlight, style = Stroke(width = 2.5f * px))
            }
            if (selected != null) {
                // No fill (its status colour stays visible): a glow that pulses briefly on selection,
                // a crisp outline, and the pads in the accent colour.
                val p = selectionPulse.value
                drawPath(selectedPath, colors.selected.copy(alpha = 0.30f + 0.35f * p), style = Stroke(width = (10f + 18f * p) * px, join = StrokeJoin.Round))
                drawPath(selectedPadsPath, colors.selected, style = Fill)
                drawPath(selectedPath, Color.Black.copy(alpha = 0.7f), style = Stroke(width = 5.5f * px, join = StrokeJoin.Round))
                drawPath(selectedPath, colors.selected, style = Stroke(width = 3.5f * px, join = StrokeJoin.Round))
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
        if (options.showDesignators) drawLabels(scene, t, visibleSide, labelPaint, colors, highlightedRefs)
        if (options.showAnnotations) drawStatusBadges(scene, t, visibleSide, marks, badgePaint)
        if (options.showFps) {
            val fps = stats.frame(start, System.nanoTime())
            drawContext.canvas.nativeCanvas.drawText(fps, 16f, size.height - 16f, fpsPaint)
        }
    }
}

private const val SHADOW_DX_PX = 2.5
private const val SHADOW_DY_PX = 3.5

/**
 * Background grid in board coordinates (drawn under the board, inside the view transform).
 * Manual: fixed spacing [stepUm]; when zoomed out too far, minor lines are skipped, then the grid
 * is hidden. Auto: the spacing follows the zoom. Lines: minor + brighter major every
 * [Grid.MAJOR_EVERY]; dots: one per intersection.
 */
private fun DrawScope.drawGrid(t: ViewTransform, style: GridStyle, stepUm: Long, auto: Boolean, background: Color, px: Float) {
    val density = if (auto) Grid.Density.ALL else Grid.density(stepUm, t.scale)
    if (density == Grid.Density.NOTHING) return
    // At MAJOR density, draw only the major lines (step × 5); they still sit on the chosen grid.
    val majorsOnly = density == Grid.Density.MAJOR
    val base = if (auto) Grid.autoStep(t.scale) else stepUm
    val step = if (majorsOnly) base * Grid.MAJOR_EVERY else base
    fun major(k: Long) = majorsOnly || Grid.isMajor(k)
    val v = Grid.visible(t, size.width.toDouble(), size.height.toDouble())
    val xs = Grid.range(v[0], v[2], step)
    val ys = Grid.range(v[1], v[3], step)
    if ((xs.last - xs.first) * (ys.last - ys.first) > 40_000) return // safety: never flood a frame
    val ink = if (background.luminance() > 0.5f) Color.Black else Color.White
    val minor = ink.copy(alpha = 0.09f)
    val major = ink.copy(alpha = 0.22f)
    val x0 = (xs.first * step).toFloat(); val x1 = (xs.last * step).toFloat()
    val y0 = (ys.first * step).toFloat(); val y1 = (ys.last * step).toFloat()
    when (style) {
        GridStyle.LINES -> {
            val minorPath = Path(); val majorPath = Path()
            for (k in xs) { val p = if (major(k)) majorPath else minorPath; val x = (k * step).toFloat(); p.moveTo(x, y0); p.lineTo(x, y1) }
            for (k in ys) { val p = if (major(k)) majorPath else minorPath; val y = (k * step).toFloat(); p.moveTo(x0, y); p.lineTo(x1, y) }
            drawPath(minorPath, minor, style = Stroke(width = 1f * px))
            drawPath(majorPath, major, style = Stroke(width = 1.4f * px))
        }
        GridStyle.DOTS -> {
            val minorPts = ArrayList<Offset>(); val majorPts = ArrayList<Offset>()
            for (i in xs) for (j in ys) {
                val o = Offset((i * step).toFloat(), (j * step).toFloat())
                if (major(i) && major(j)) majorPts += o else minorPts += o
            }
            drawPoints(minorPts, PointMode.Points, ink.copy(alpha = 0.22f), strokeWidth = 2.2f * px, cap = StrokeCap.Round)
            drawPoints(majorPts, PointMode.Points, ink.copy(alpha = 0.45f), strokeWidth = 3.6f * px, cap = StrokeCap.Round)
        }
        GridStyle.NONE -> Unit
    }
}

/** How strongly the rest of the board fades when a net or identical parts are in focus. */
private const val FOCUS_FADE = 0.72f

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

private const val MIN_LABEL_PX = 9f
private const val MAX_LABEL_PX = 42f

/** Opacity of status paint: high enough that the board under a part barely tints the colour. */
const val MARK_ALPHA = 0.85f

private const val MIN_BADGE_PART_PX = 14f

/**
 * A round badge with the status symbol at the top-right of each painted part on the visible side,
 * so the status reads without relying on colour alone. Skipped when the part is too small on screen.
 */
private fun DrawScope.drawStatusBadges(scene: BoardScene, t: ViewTransform, side: Side, marks: Map<String, ComponentMark>, paint: Paint) {
    if (marks.isEmpty()) return
    val w = size.width.toDouble(); val h = size.height.toDouble()
    val canvas = drawContext.canvas.nativeCanvas
    for (m in marks.values) {
        val glyph = m.glyph ?: continue
        val c = scene.board.component(m.refDes) ?: continue
        if (c.side != side) continue
        val r = t.toScreen(c.bounds)
        if (!r.intersects(w, h)) continue
        val minSide = minOf(r.width, r.height).toFloat()
        if (minSide < MIN_BADGE_PART_PX) continue
        val radius = (minSide * 0.3f).coerceIn(8f, 15f)
        val cx = (r.right - radius * 0.35f).toFloat(); val cy = (r.top + radius * 0.35f).toFloat()
        val color = Color(m.argb)
        paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.BLACK; paint.alpha = 170
        canvas.drawCircle(cx, cy, radius + 1.5f, paint)
        paint.color = color.toArgb(); paint.alpha = 255
        canvas.drawCircle(cx, cy, radius, paint)
        // Dark symbol on light colours (green, amber), white on dark ones (red, blue).
        paint.color = if (color.luminance() > 0.35f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        paint.textSize = radius * 1.35f
        canvas.drawText(glyph, cx, cy + paint.textSize * 0.36f, paint)
    }
}

/** Designators drawn upright in screen space; level-of-detail: skipped when too small to read. */
private fun DrawScope.drawLabels(
    scene: BoardScene, t: ViewTransform, side: Side, paint: Paint, colors: BoardColors,
    /** When not empty, every other designator is faded. */
    focus: Set<String> = emptySet(),
) {
    val w = size.width.toDouble(); val h = size.height.toDouble()
    val canvas = drawContext.canvas.nativeCanvas
    for (c in scene.board.components) {
        val r = t.toScreen(c.bounds)
        if (!r.intersects(w, h)) continue
        val len = c.refDes.length.coerceAtLeast(1)
        val size = minOf(r.height * 0.6, r.width / (len * 0.62), MAX_LABEL_PX.toDouble()).toFloat()
        if (size < MIN_LABEL_PX) continue
        paint.textSize = size
        val alpha = when {
            focus.isNotEmpty() && c.refDes !in focus -> 0.22f
            c.side == side -> 1f
            else -> 0.35f
        }
        paint.color = colors.label.copy(alpha = alpha).toArgb()
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
