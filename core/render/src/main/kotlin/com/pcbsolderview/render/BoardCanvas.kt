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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.BoardScene.Companion.addCenterline
import com.pcbsolderview.render.BoardScene.Companion.addRect
import com.pcbsolderview.render.BoardScene.Companion.addRing
import com.pcbsolderview.model.outline
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
    /** S Pen side button pressed (true) / released (false), while touching or hovering. */
    onStylusButton: (Boolean) -> Unit = {},
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
    val haptic = LocalHapticFeedback.current
    latest.onSnap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    latest.longPressEnabled = onLongPress != null
    latest.onLongPress = { p -> (onLongPress?.invoke(p) == true).also { if (it) haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
    latest.onLongPressDrag = onLongPressDrag
    latest.onLongPressRelease = onLongPressRelease
    latest.penOnly = options.penOnlyDrawing
    latest.onStylusButton = onStylusButton
    latest.onErase = { p, tol -> onErase(p.first, p.second, tol) }
    latest.onHover = onHover
    val stylusSeen = remember { booleanArrayOf(false) }
    latest.onStylusSeen = { if (!stylusSeen[0]) { stylusSeen[0] = true; onStylusSeen() } }

    Canvas(
        modifier
            // Canvas does not clip by default; without this a zoomed board paints over the top bar.
            .clipToBounds()
            .onSizeChanged { onViewport(it.width, it.height) }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // ---- S Pen: writes (or erases with the side button), never pans or zooms.
                    if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                        latest.onStylusSeen()
                        fun board(p: Offset) = latest.transform?.toBoard(p.x.toDouble(), p.y.toDouble())
                        // Pen or eraser is decided once, at touch-down, so an eraser can never remove
                        // the stroke that is being drawn.
                        val erasing = down.type == PointerType.Eraser || latest.tool == CanvasTool.ERASER || currentEvent.stylusButton()
                        var penMoved = false
                        var penTravel = 0f
                        livePoints.clear()
                        board(down.position)?.let { if (erasing) latest.onErase(it, STYLUS_ERASE_PX / latest.transform!!.scale) else livePoints += it }
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            // Measure BEFORE consuming: positionChange() reports zero for consumed changes,
                            // which made every stroke look like a tap and threw the ink away.
                            penTravel += ch.positionChangeIgnoreConsumed().getDistance()
                            e.changes.forEach { it.consume() } // palm/fingers touching meanwhile are ignored
                            if (!ch.pressed) break
                            if (penTravel > slop / 2) penMoved = true
                            val p = board(ch.position) ?: continue
                            if (erasing) latest.onErase(p, STYLUS_ERASE_PX / latest.transform!!.scale) else livePoints += p
                        }
                        val t = latest.transform
                        if (t != null) {
                            if (!erasing && penMoved && livePoints.size >= 2) {
                                latest.onStroke(livePoints.toList(), (latest.penWidthPx / t.scale).toFloat())
                            } else if (!penMoved && !erasing) {
                                // A quick tap with the pen selects, like a finger.
                                val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                                latest.onTap(bx, by, TOUCH_SLOP_PX / t.scale, true)
                            }
                        }
                        livePoints.clear()
                        return@awaitEachGesture
                    }

                    // ---- Fingers. With penOnly, fingers never draw.
                    var drawing = latest.tool == CanvasTool.PEN && !latest.penOnly
                    if (drawing) { livePoints.clear(); latest.transform?.let { livePoints += it.toBoard(down.position.x.toDouble(), down.position.y.toDouble()) } }
                    var moved = false

                    // Long press (finger held still): hand the gesture to the caller, e.g. a radial menu.
                    if (!drawing && latest.longPressEnabled) {
                        var still = 0f
                        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var r = 0
                            while (r == 0) {
                                val e = awaitPointerEvent()
                                val pressed = e.changes.count { it.pressed }
                                still += e.calculatePan().getDistance()
                                r = when {
                                    pressed == 0 -> LP_RELEASED
                                    pressed >= 2 || still > slop -> LP_MOVED
                                    else -> 0
                                }
                            }
                            r
                        }
                        when (outcome) {
                            null -> if (latest.onLongPress(down.position)) {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                                    ch.consume()
                                    if (!ch.pressed) { latest.onLongPressRelease(ch.position); break }
                                    latest.onLongPressDrag(ch.position)
                                }
                                return@awaitEachGesture
                            }
                            LP_RELEASED -> {
                                latest.transform?.let { t ->
                                    val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                                    latest.onTap(bx, by, TOUCH_SLOP_PX / t.scale, false)
                                }
                                return@awaitEachGesture
                            }
                            else -> moved = true
                        }
                    }
                    var multi = false
                    var travel = 0f
                    var twist = 0f
                    var rotating = false
                    var rawAngle = 0.0 // finger angle before snapping
                    var wasSnapped = false
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
                        if (!rotating && multi && latest.rotation && abs(twist) > ROTATION_THRESHOLD_DEG) {
                            rotating = true
                            rawAngle = t.rotationDeg
                            wasSnapped = true // starting angle counts as "locked", no tick on start
                        }
                        var next = t.panBy(pan.x.toDouble(), pan.y.toDouble())
                        if (centroid != Offset.Unspecified) {
                            next = next.zoomBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                            if (rotating) {
                                // Follow the fingers, but lock near 0/90/180/270°.
                                rawAngle += rot
                                val target = ViewTransform.snapAngle(rawAngle)
                                val delta = ViewTransform.angleDelta(next.rotationDeg, target)
                                if (delta != 0.0) next = next.rotateBy(delta, centroid.x.toDouble(), centroid.y.toDouble())
                                val snapped = target % 90.0 == 0.0
                                if (snapped && !wasSnapped) latest.onSnap()
                                wasSnapped = snapped
                            }
                        }
                        latest.onTransform(next)
                        event.changes.forEach { it.consume() }
                    }
                    val t = latest.transform
                    if (drawing && moved && livePoints.size >= 2 && t != null) {
                        latest.onStroke(livePoints.toList(), (latest.penWidthPx / t.scale).toFloat())
                    } else if (!moved && !multi && t != null) {
                        val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                        latest.onTap(bx, by, TOUCH_SLOP_PX / t.scale, false)
                    }
                    livePoints.clear()
                }
            }
            // S Pen hover observer (does not consume). The side button comes from StylusInput.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser } ?: continue
                        latest.onStylusSeen()
                        when {
                            e.type == PointerEventType.Exit || ch.pressed -> latest.onHover(null)
                            e.type == PointerEventType.Enter || e.type == PointerEventType.Move -> latest.onHover(ch.position)
                        }
                    }
                }
            },
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
                    drawPath(scene.holesWithoutVias, colors.background, style = Fill)
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
                for ((argb, path) in markPaths) {
                    val c = Color(argb)
                    drawPath(path, c.copy(alpha = 0.55f), style = Fill)
                    drawPath(path, c, style = Stroke(width = 2f * px))
                }
            }
            if (highlighted.isNotEmpty()) {
                // Focus: fade the rest of the board (copper, outlines, status paint) toward the
                // background, then redraw only the highlighted parts — pads solid, body tinted.
                drawPath(outlinePath, colors.background.copy(alpha = FOCUS_FADE), style = Fill)
                drawPath(highlightPath, colors.highlight.copy(alpha = 0.22f), style = Fill)
                // Highlighted parts keep their status colour (OK green, Problem red, …).
                if (options.showAnnotations) {
                    for ((argb, path) in highlightedMarkPaths) drawPath(path, Color(argb).copy(alpha = 0.7f), style = Fill)
                }
                drawPath(highlightPadsPath, colors.highlight, style = Fill)
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
        if (options.showDesignators) drawLabels(scene, t, visibleSide, labelPaint, colors, highlightedRefs)
        if (options.showFps) {
            val fps = stats.frame(start, System.nanoTime())
            drawContext.canvas.nativeCanvas.drawText(fps, 16f, size.height - 16f, fpsPaint)
        }
    }
}

private class LatestHolder {
    var transform: ViewTransform? = null
    var onTransform: (ViewTransform) -> Unit = {}
    var onTap: (Double, Double, Double, Boolean) -> Unit = { _, _, _, _ -> }
    var penOnly: Boolean = false
    var onStylusButton: (Boolean) -> Unit = {}
    var onStylusSeen: () -> Unit = {}
    var onErase: (Pair<Double, Double>, Double) -> Unit = { _, _ -> }
    var onHover: (Offset?) -> Unit = {}
    var tool: CanvasTool = CanvasTool.NAVIGATE
    var rotation: Boolean = true
    var penWidthPx: Float = 4f
    var onStroke: (List<Pair<Double, Double>>, Float) -> Unit = { _, _ -> }
    var onSnap: () -> Unit = {}
    var longPressEnabled: Boolean = false
    var onLongPress: (Offset) -> Boolean = { false }
    var onLongPressDrag: (Offset) -> Unit = {}
    var onLongPressRelease: (Offset) -> Unit = {}
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

/** Eraser reach of the S Pen, in screen pixels. */
private const val STYLUS_ERASE_PX = 12.0

/**
 * S Pen side button. Android reports it as BUTTON_STYLUS_PRIMARY/SECONDARY, which Compose exposes
 * as the secondary/tertiary buttons.
 */
@Suppress("UnusedReceiverParameter")
private fun PointerEvent.stylusButton(): Boolean = StylusInput.buttonPressed

private const val LP_RELEASED = 1
private const val LP_MOVED = 2

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
