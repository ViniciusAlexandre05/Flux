package com.pcbsolderview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.CopperIndex
import com.pcbsolderview.render.ViewTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rubber-band box in screen px; [done] once the finger lifts and the bubbles show. */
data class Marquee(val start: Offset, val end: Offset, val done: Boolean = false) {
    val left get() = min(start.x, end.x)
    val top get() = min(start.y, end.y)
    val right get() = max(start.x, end.x)
    val bottom get() = max(start.y, end.y)
    val isTiny get() = abs(end.x - start.x) < MIN_SIDE_PX || abs(end.y - start.y) < MIN_SIDE_PX

    companion object { const val MIN_SIDE_PX = 16f }
}

object MarqueeGeometry {
    /** The box's four corners in board coordinates (a rotated view makes it any quadrilateral). */
    fun boardCorners(m: Marquee, t: ViewTransform): List<Pair<Double, Double>> =
        listOf(Offset(m.left, m.top), Offset(m.right, m.top), Offset(m.right, m.bottom), Offset(m.left, m.bottom))
            .map { t.toBoard(it.x.toDouble(), it.y.toDouble()) }

    /** Parts on [side] whose centre lies inside the box. */
    fun partsInside(components: List<Component>, m: Marquee, t: ViewTransform, side: Side): List<Component> {
        val ring = boardCorners(m, t).map { (x, y) -> Point(Math.round(x), Math.round(y)) }
        return components.filter { it.side == side && CopperIndex.pointInRing(it.position.x.toDouble(), it.position.y.toDouble(), ring) }
    }

    /** Closed outline (corners + first again) for a rectangle annotation. */
    fun annotationOutline(m: Marquee, t: ViewTransform): List<Pair<Double, Double>> =
        boardCorners(m, t).let { it + it.first() }
}

/**
 * Draws the box while dragging; once [Marquee.done], shows two bubbles: select the parts inside,
 * or keep the box as an annotation. Tapping elsewhere dismisses it.
 */
@Composable
fun MarqueeOverlay(
    m: Marquee,
    partCount: Int,
    onSelect: () -> Unit,
    onAnnotate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stroke = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (m.done) Modifier.pointerInput(m) { detectTapGestures { onDismiss() } }.testTag("marquee") else Modifier.testTag("marquee"),
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(stroke.copy(alpha = 0.12f), topLeft = Offset(m.left, m.top), size = Size(m.right - m.left, m.bottom - m.top))
            drawRect(
                stroke, topLeft = Offset(m.left, m.top), size = Size(m.right - m.left, m.bottom - m.top),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
            )
        }
        // Live count next to the box while dragging.
        if (!m.done) {
            Text(
                "$partCount part" + if (partCount == 1) "" else "s",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.offset { IntOffset(m.left.roundToInt(), (m.top - 30.dp.toPx()).roundToInt().coerceAtLeast(0)) }
                    .background(stroke, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        } else {
            // Bubbles under the box, or above it when the box reaches the bottom edge; kept inside the right edge.
            val bubbleRowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
            val bubbleWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 330.dp.toPx() }
            val areaW = constraints.maxWidth.toFloat()
            val areaH = constraints.maxHeight.toFloat()
            Row(
                Modifier.offset {
                    val y = if (m.bottom + 8f + bubbleRowPx > areaH) m.top - bubbleRowPx else m.bottom + 8f
                    val x = m.left.coerceAtMost(areaW - bubbleWidthPx).coerceAtLeast(0f)
                    IntOffset(x.roundToInt(), y.coerceAtLeast(0f).roundToInt())
                },
            ) {
                Bubble(Icons.Filled.SelectAll, "Select $partCount part" + if (partCount == 1) "" else "s", enabled = partCount > 0, tag = "marquee.select", onClick = onSelect)
                Spacer(Modifier.width(8.dp))
                Bubble(Icons.Filled.CropSquare, "Annotate", enabled = true, tag = "marquee.annotate", onClick = onAnnotate)
            }
        }
    }
}

@Composable
private fun Bubble(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp,
        modifier = Modifier.height(48.dp).clickable(enabled = enabled, onClick = onClick).testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
