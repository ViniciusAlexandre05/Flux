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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
    // S Pen hover observer (does not consume). The side button comes from StylusInput.
    .pointerInput(s) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
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

/**
 * S Pen side button. Compose hides it (`PointerEvent.motionEvent` is internal), so MainActivity
 * forwards raw MotionEvents to [StylusInput].
 */
@Suppress("UnusedReceiverParameter")
private fun PointerEvent.stylusButton(): Boolean = StylusInput.buttonPressed
