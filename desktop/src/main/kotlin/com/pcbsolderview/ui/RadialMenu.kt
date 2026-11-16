package com.pcbsolderview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pcbsolderview.Status
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class RadialItem(val label: String, val icon: ImageVector, val color: Color, val status: Status?)

/** Clockwise from the top. */
val RADIAL_ITEMS = listOf(
    RadialItem("OK", Icons.Filled.CheckCircle, Color(Status.OK.argb), Status.OK),
    RadialItem("Problem", Icons.Filled.Warning, Color(Status.PROBLEM.argb), Status.PROBLEM),
    RadialItem("Check", Icons.Filled.Visibility, Color(Status.CHECK.argb), Status.CHECK),
    RadialItem("Info", Icons.Filled.Info, Color(Status.INFO.argb), Status.INFO),
    RadialItem("Clear", Icons.Filled.Block, Color(0xFF9AA4B2), null),
)

val RADIAL_INNER = 44.dp
val RADIAL_OUTER = 124.dp

/** Open ring menu: [center] in screen px (already clamped to stay on screen). */
data class RadialState(val center: Offset, val refDes: String, val current: Int?, val hovered: Int? = null)

object RadialGeometry {
    /**
     * Index of the item in direction [p] from [center], or null inside the centre hole.
     * Items are [count] equal wedges, the first centred straight up. Distance beyond the ring still
     * counts, so a quick flick in a direction works.
     */
    fun itemAt(center: Offset, p: Offset, innerPx: Float, count: Int = RADIAL_ITEMS.size): Int? {
        val dx = p.x - center.x; val dy = p.y - center.y
        if (hypot(dx, dy) < innerPx) return null
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()) // 0 = right, 90 = down
        val wedge = 360.0 / count
        val rel = ((angle + 90.0 + wedge / 2) % 360.0 + 360.0) % 360.0
        return (rel / wedge).toInt().coerceIn(0, count - 1)
    }

    /** Keeps the whole ring inside a w×h area. */
    fun clamp(p: Offset, w: Float, h: Float, outerPx: Float, margin: Float): Offset {
        val r = outerPx + margin
        return Offset(p.x.coerceIn(r, maxOf(r, w - r)), p.y.coerceIn(r, maxOf(r, h - r)))
    }
}

/**
 * Ring of status options around a long-pressed part. Slide + lift selects (handled by the caller
 * via [RadialGeometry]); when open on its own, tap an item to pick it or anywhere else to close.
 */
@Composable
fun RadialStatusMenu(state: RadialState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val innerPx = with(density) { RADIAL_INNER.toPx() }
    val outerPx = with(density) { RADIAL_OUTER.toPx() }
    val c = state.center
    Box(
        Modifier.fillMaxSize().testTag("radial")
            .pointerInput(state.center) {
                detectTapGestures { p ->
                    val d = hypot(p.x - c.x, p.y - c.y)
                    val i = if (d <= outerPx * 1.15f) RadialGeometry.itemAt(c, p, innerPx) else null
                    if (i != null) onPick(i) else onDismiss()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.25f))
            val n = RADIAL_ITEMS.size
            val wedge = 360f / n
            val mid = (innerPx + outerPx) / 2
            val thick = outerPx - innerPx
            drawCircle(Color(0xEE151A21), radius = outerPx + 6f, center = c)
            RADIAL_ITEMS.forEachIndexed { i, item ->
                val start = -90f - wedge / 2 + i * wedge + 2f
                val active = i == state.hovered
                val alpha = when {
                    active -> 1f
                    i == state.current -> 0.75f
                    else -> 0.38f
                }
                val grow = if (active) 10f else 0f
                drawArc(
                    color = item.color.copy(alpha = alpha),
                    startAngle = start, sweepAngle = wedge - 4f, useCenter = false,
                    topLeft = Offset(c.x - mid - grow / 2, c.y - mid - grow / 2),
                    size = Size(2 * mid + grow, 2 * mid + grow),
                    style = Stroke(width = thick + grow),
                )
            }
            drawCircle(Color(0xFF0E1116), radius = innerPx - 4f, center = c)
        }
        // Centre: designator.
        Box(
            Modifier.offset { IntOffset((c.x - innerPx).roundToInt(), (c.y - innerPx).roundToInt()) }
                .size(RADIAL_INNER * 2),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.refDes, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
        }
        // Item icons + labels at the middle of each wedge.
        val mid = (innerPx + outerPx) / 2
        val box = with(density) { 72.dp.toPx() }
        RADIAL_ITEMS.forEachIndexed { i, item ->
            val a = Math.toRadians((-90.0 + i * 360.0 / RADIAL_ITEMS.size))
            val x = c.x + mid * cos(a).toFloat() - box / 2
            val y = c.y + mid * sin(a).toFloat() - box / 2
            val onItem = if (i == state.hovered || item.status == null) Color.Black else Color.White
            Column(
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(item.icon, contentDescription = item.label, tint = onItem, modifier = Modifier.size(26.dp))
                Text(item.label, color = onItem, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (state.current != null) {
            // Small dot on the current status.
            val a = Math.toRadians((-90.0 + state.current * 360.0 / RADIAL_ITEMS.size))
            val r = outerPx + 14f
            Box(
                Modifier.offset { IntOffset((c.x + r * cos(a) - 8).roundToInt(), (c.y + r * sin(a) - 8).roundToInt()) }
                    .size(8.dp).background(Color.White, CircleShape),
            )
        }
    }
}
