package com.pcbsolderview.render

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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs

/**
 * Inputs and callbacks of [viewGestures], shared by every pannable view (board now, schematic in
 * v0.1). The view writes the latest values on each recomposition; the gesture coroutine reads them
 * from here, so it never restarts.
 *
 * Coordinates handed to callbacks are content units (µm) unless they say screen px.
 */
class ViewGestureState internal constructor(internal var haptic: HapticFeedback?) {
    var transform: ViewTransform? = null
    var onTransform: (ViewTransform) -> Unit = {}
    /** Content point, touch tolerance (content units), and whether the tap came from the S Pen. */
    var onTap: (x: Double, y: Double, tolerance: Double, fromStylus: Boolean) -> Unit = { _, _, _, _ -> }
    var tool: CanvasTool = CanvasTool.NAVIGATE
    /** Two-finger rotation allowed (after [ROTATION_THRESHOLD_DEG] of twist, with snapping). */
    var rotation: Boolean = true
    /** Fingers never draw, even with [CanvasTool.PEN]. */
    var penOnly: Boolean = false
    var penWidthPx: Float = 4f
    var onStroke: (points: List<Pair<Double, Double>>, widthUm: Float) -> Unit = { _, _ -> }
    var onErase: (x: Double, y: Double, tolerance: Double) -> Unit = { _, _, _ -> }
    /**
     * Finger held still on a screen point. Return true to take over the gesture: moves and the
     * release then go to [onLongPressDrag] / [onLongPressRelease] instead of panning. Null: off.
     */
    var onLongPress: ((Offset) -> Boolean)? = null
    var onLongPressDrag: (Offset) -> Unit = {}
    var onLongPressRelease: (Offset) -> Unit = {}
    /** S Pen hovering at a screen point, or null when it leaves / touches. */
    var onHover: (Offset?) -> Unit = {}
    /** Called once, the first time an S Pen touches or hovers this view. */
    var onStylusSeen: () -> Unit = {}
    /** Desktop: double click with the mouse (fit). Null: off. */
    var onDoubleTap: (() -> Unit)? = null
    /**
     * Desktop: Ctrl+drag with the left button starts a box selection at a screen point (return true
     * to take it; the drag and release then go to [onLongPressDrag] / [onLongPressRelease]). Null: off.
     */
    var onBoxStart: ((Offset) -> Boolean)? = null
    /** Desktop: Ctrl+click (content point, tolerance): add or remove a part from the selection. Null: off. */
    var onCtrlTap: ((x: Double, y: Double, tolerance: Double) -> Unit)? = null
    internal var lastClick: Pair<Long, Offset>? = null

    /** Stroke being drawn (content units); empty when not drawing. Snapshot state: the view redraws. */
    val livePoints = mutableStateListOf<Pair<Double, Double>>()

    private var stylusSeen = false

    internal fun stylusSeen() {
        if (!stylusSeen) { stylusSeen = true; onStylusSeen() }
    }

    internal fun snapped() = haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    internal fun longPress(p: Offset): Boolean =
        (onLongPress?.invoke(p) == true).also { if (it) haptic?.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberViewGestureState(): ViewGestureState {
    val haptic = LocalHapticFeedback.current
    return remember { ViewGestureState(haptic) }.also { it.haptic = haptic }
}

/**
 * Every touch gesture of a pannable view, in one `awaitEachGesture`:
 * - S Pen: draws (or erases with the side button / eraser tool), never pans or zooms; a quick pen
 *   tap is a tap.
 * - Finger long-press: [ViewGestureState.onLongPress] may take over the gesture.
 * - Finger drag with [CanvasTool.PEN] (unless pen-only): draws; a second finger switches to navigation.
 * - Otherwise: pan + pinch-zoom and guarded two-finger rotation with snapping; a tap reports
 *   content coordinates.
 * A second, non-consuming pointer input observes S Pen hover.
 *
 * Pitfall kept in mind here: measure movement **before** `consume()`
 * (`positionChangeIgnoreConsumed()`), otherwise every stroke looks like a tap.
 */
fun Modifier.viewGestures(s: ViewGestureState): Modifier = this
    .pointerInput(s) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val livePoints = s.livePoints

            // ---- Mouse (desktop only): see mouseGesture.
            if (down.type == PointerType.Mouse) {
                mouseGesture(s, down, currentEvent, slop)
                return@awaitEachGesture
            }

            // ---- S Pen: writes (or erases with the side button), never pans or zooms.
            if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                s.stylusSeen()
                fun content(p: Offset) = s.transform?.toBoard(p.x.toDouble(), p.y.toDouble())
                fun erase(p: Pair<Double, Double>) = s.onErase(p.first, p.second, STYLUS_ERASE_PX / s.transform!!.scale)
                // Pen or eraser is decided once, at touch-down, so an eraser can never remove
                // the stroke that is being drawn.
                val erasing = down.type == PointerType.Eraser || s.tool == CanvasTool.ERASER || currentEvent.stylusButton()
                var penMoved = false
                var penTravel = 0f
                livePoints.clear()
                content(down.position)?.let { if (erasing) erase(it) else livePoints += it }
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    // Measure BEFORE consuming: positionChange() reports zero for consumed changes,
                    // which made every stroke look like a tap and threw the ink away.
                    penTravel += ch.positionChangeIgnoreConsumed().getDistance()
                    e.changes.forEach { it.consume() } // palm/fingers touching meanwhile are ignored
                    if (!ch.pressed) break
                    if (penTravel > slop / 2) penMoved = true
                    val p = content(ch.position) ?: continue
                    if (erasing) erase(p) else livePoints += p
                }
                val t = s.transform
                if (t != null) {
                    if (!erasing && penMoved && livePoints.size >= 2) {
                        s.onStroke(livePoints.toList(), (s.penWidthPx / t.scale).toFloat())
                    } else if (!penMoved && !erasing) {
                        // A quick tap with the pen selects, like a finger.
                        val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                        s.onTap(bx, by, TOUCH_SLOP_PX / t.scale, true)
                    }
                }
                livePoints.clear()
                return@awaitEachGesture
            }

            // ---- Fingers. With penOnly, fingers never draw.
            var drawing = s.tool == CanvasTool.PEN && !s.penOnly
            if (drawing) { livePoints.clear(); s.transform?.let { livePoints += it.toBoard(down.position.x.toDouble(), down.position.y.toDouble()) } }
            var moved = false

            // Long press (finger held still): hand the gesture to the caller, e.g. a radial menu.
            if (!drawing && s.onLongPress != null) {
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
                    null -> if (s.longPress(down.position)) {
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                            ch.consume()
                            if (!ch.pressed) { s.onLongPressRelease(ch.position); break }
                            s.onLongPressDrag(ch.position)
                        }
                        return@awaitEachGesture
                    }
                    LP_RELEASED -> {
                        s.transform?.let { t ->
                            val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                            s.onTap(bx, by, TOUCH_SLOP_PX / t.scale, false)
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
                val t = s.transform ?: continue
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
                if (!rotating && multi && s.rotation && abs(twist) > ROTATION_THRESHOLD_DEG) {
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
                        if (snapped && !wasSnapped) s.snapped()
                        wasSnapped = snapped
                    }
                }
                s.onTransform(next)
                event.changes.forEach { it.consume() }
            }
            val t = s.transform
            if (drawing && moved && livePoints.size >= 2 && t != null) {
                s.onStroke(livePoints.toList(), (s.penWidthPx / t.scale).toFloat())
            } else if (!moved && !multi && t != null) {
                val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                s.onTap(bx, by, TOUCH_SLOP_PX / t.scale, false)
            }
            livePoints.clear()
        }
    }
    // Mouse wheel (desktop only): zoom at the cursor; Ctrl+wheel rotates in 90° steps.
    .pointerInput(s) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type != PointerEventType.Scroll) continue
                val ch = e.changes.firstOrNull() ?: continue
                val t = s.transform ?: continue
                val dy = ch.scrollDelta.y
                if (dy == 0f) continue
                val p = ch.position
                val next = if (e.keyboardModifiers.isCtrlPressed && s.rotation) {
                    val target = (Math.round(t.rotationDeg / 90.0) * 90.0 + if (dy > 0) 90.0 else -90.0)
                    t.rotateBy(ViewTransform.angleDelta(t.rotationDeg, ViewTransform.normalize(target)), p.x.toDouble(), p.y.toDouble())
                } else {
                    t.zoomBy(Math.pow(WHEEL_ZOOM_STEP, -dy.toDouble().coerceIn(-5.0, 5.0)), p.x.toDouble(), p.y.toDouble())
                }
                s.onTransform(next)
                e.changes.forEach { it.consume() }
            }
        }
    }
    // S Pen hover observer (does not consume); on desktop the mouse hovers too (label of the part
    // under the cursor). The side button comes from StylusInput on Android.
    .pointerInput(s) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val mouse = e.changes.firstOrNull { it.type == PointerType.Mouse }
                if (mouse != null) {
                    when {
                        e.type == PointerEventType.Exit || e.buttons.areAnyPressed -> s.onHover(null)
                        e.type == PointerEventType.Enter || e.type == PointerEventType.Move -> s.onHover(mouse.position)
                    }
                    continue
                }
                val ch = e.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser } ?: continue
                s.stylusSeen()
                when {
                    e.type == PointerEventType.Exit || ch.pressed -> s.onHover(null)
                    e.type == PointerEventType.Enter || e.type == PointerEventType.Move -> s.onHover(ch.position)
                }
            }
        }
    }

/** Touch tolerance for taps, in screen pixels. */
const val TOUCH_SLOP_PX = 24.0

/** Twist (degrees) two fingers must make before the view starts rotating. */
const val ROTATION_THRESHOLD_DEG = 12f

/** Eraser reach of the S Pen, in screen pixels. */
private const val STYLUS_ERASE_PX = 12.0

private const val LP_RELEASED = 1
private const val LP_MOVED = 2

/** Desktop: there is no S Pen side button (Android reads it from raw MotionEvents). */
@Suppress("UnusedReceiverParameter", "FunctionOnlyReturningConstant")
private fun PointerEvent.stylusButton(): Boolean = false

/** Zoom per mouse-wheel notch. */
private const val WHEEL_ZOOM_STEP = 1.2

/** Two clicks closer than this (ms) and [DOUBLE_CLICK_PX] apart are a double click (fit). */
private const val DOUBLE_CLICK_MS = 400L
private const val DOUBLE_CLICK_PX = 8f

/**
 * Desktop mouse, one press to release:
 * - right button: [ViewGestureState.onLongPress] (status ring on a part, box selection elsewhere),
 *   or pan when the view does not take it;
 * - middle button: pan;
 * - left button: Pen tool draws, Eraser tool erases; Shift+drag rotates (snapping near 90° steps);
 *   otherwise drag pans and a click is a tap. A double click calls [ViewGestureState.onDoubleTap].
 * - Ctrl + left button: drag = box selection ([ViewGestureState.onBoxStart]), click = add / remove the
 *   part under the mouse ([ViewGestureState.onCtrlTap]).
 */
private suspend fun AwaitPointerEventScope.mouseGesture(s: ViewGestureState, down: PointerInputChange, first: PointerEvent, slop: Float) {
    val buttons = first.buttons
    val livePoints = s.livePoints
    fun content(p: Offset) = s.transform?.toBoard(p.x.toDouble(), p.y.toDouble())

    fun pan(delta: Offset) { s.transform?.let { s.onTransform(it.panBy(delta.x.toDouble(), delta.y.toDouble())) } }

    when {
        buttons.isSecondaryPressed -> {
            if (s.longPress(down.position)) {
                val (_, end) = trackPress(down, slop) { ch, _ -> s.onLongPressDrag(ch.position) }
                s.onLongPressRelease(end)
            } else trackPress(down, slop) { _, d -> pan(d) }
        }
        buttons.isTertiaryPressed -> trackPress(down, slop) { _, d -> pan(d) }
        first.keyboardModifiers.isCtrlPressed && s.tool == CanvasTool.NAVIGATE && (s.onBoxStart != null || s.onCtrlTap != null) -> {
            var boxing = false
            val (moved, end) = trackPress(down, slop) { ch, _ ->
                if (!boxing) boxing = s.onBoxStart?.invoke(down.position) == true
                if (boxing) s.onLongPressDrag(ch.position)
            }
            if (boxing) s.onLongPressRelease(end)
            else if (!moved) s.transform?.let { t ->
                val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                s.onCtrlTap?.invoke(bx, by, MOUSE_SLOP_PX / t.scale)
            }
        }
        s.tool == CanvasTool.ERASER -> {
            fun erase(p: Offset) { val t = s.transform ?: return; content(p)?.let { s.onErase(it.first, it.second, STYLUS_ERASE_PX / t.scale) } }
            erase(down.position)
            trackPress(down, slop) { ch, _ -> erase(ch.position) }
        }
        s.tool == CanvasTool.PEN -> {
            livePoints.clear()
            content(down.position)?.let { livePoints += it }
            val (moved, _) = trackPress(down, slop) { ch, _ -> content(ch.position)?.let { livePoints += it } }
            val t = s.transform
            if (t != null) {
                if (moved && livePoints.size >= 2) s.onStroke(livePoints.toList(), (s.penWidthPx / t.scale).toFloat())
                else {
                    val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                    s.onTap(bx, by, MOUSE_SLOP_PX / t.scale, false)
                }
            }
            livePoints.clear()
        }
        else -> {
            val rotate = first.keyboardModifiers.isShiftPressed && s.rotation
            var rawAngle = s.transform?.rotationDeg ?: 0.0
            var lastPos = down.position
            val (moved, _) = trackPress(down, slop) { ch, d ->
                val t = s.transform ?: return@trackPress
                if (rotate) {
                    // Angle swept around the view centre follows the mouse; locks near 0/90/180/270°.
                    val cx = (size.width / 2f).toDouble(); val cy = (size.height / 2f).toDouble()
                    val a0 = Math.toDegrees(kotlin.math.atan2(lastPos.y - cy, lastPos.x - cx))
                    val a1 = Math.toDegrees(kotlin.math.atan2(ch.position.y - cy, ch.position.x - cx))
                    rawAngle += ViewTransform.angleDelta(a0, a1)
                    val target = ViewTransform.snapAngle(rawAngle)
                    val delta = ViewTransform.angleDelta(t.rotationDeg, target)
                    if (delta != 0.0) s.onTransform(t.rotateBy(delta, cx, cy))
                } else pan(d)
                lastPos = ch.position
            }
            if (!moved) {
                val t = s.transform ?: return
                val now = System.currentTimeMillis()
                val prev = s.lastClick
                if (prev != null && now - prev.first <= DOUBLE_CLICK_MS && (prev.second - down.position).getDistance() <= DOUBLE_CLICK_PX && s.onDoubleTap != null) {
                    s.lastClick = null
                    s.onDoubleTap?.invoke()
                } else {
                    s.lastClick = now to down.position
                    val (bx, by) = t.toBoard(down.position.x.toDouble(), down.position.y.toDouble())
                    s.onTap(bx, by, MOUSE_SLOP_PX / t.scale, false)
                }
            }
        }
    }
}

/** Click tolerance for the mouse, in screen pixels (finer than a finger). */
private const val MOUSE_SLOP_PX = 8.0

/** Follows a mouse press until release; returns (moved more than half the slop, last position). */
private suspend fun AwaitPointerEventScope.trackPress(down: PointerInputChange, slop: Float, onMove: (PointerInputChange, Offset) -> Unit): Pair<Boolean, Offset> {
    var travel = 0f
    var last = down.position
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
        val delta = ch.positionChangeIgnoreConsumed()
        travel += delta.getDistance()
        ch.consume()
        last = ch.position
        if (!e.buttons.areAnyPressed || !ch.pressed) break
        if (delta != Offset.Zero) onMove(ch, delta)
    }
    return (travel > slop / 2) to last
}
