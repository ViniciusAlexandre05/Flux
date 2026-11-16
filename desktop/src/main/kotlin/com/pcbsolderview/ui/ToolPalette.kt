package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pcbsolderview.PEN_COLORS
import com.pcbsolderview.PEN_WIDTHS
import com.pcbsolderview.Status
import com.pcbsolderview.Tool

val PALETTE_WIDTH = 64.dp
val OPTIONS_WIDTH = 264.dp

/**
 * Floating tool palette (Photoshop-style): icons only, drag the grip to move it.
 * [horizontal] lays it out as a bar (docked at the top/bottom) instead of a column (left/right).
 * The colour well shows the current pen / status colour and opens the options flyout.
 */
@Composable
fun ToolPalette(
    tool: Tool,
    wellArgb: Int?,
    canUndo: Boolean,
    onTool: (Tool) -> Unit,
    onWell: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
        modifier = modifier.then(if (horizontal) Modifier.height(PALETTE_WIDTH) else Modifier.width(PALETTE_WIDTH)).testTag("palette"),
    ) {
        val items: @Composable () -> Unit = {
            // Grip: drag to move the palette.
            Box(
                (if (horizontal) Modifier.width(26.dp).height(48.dp) else Modifier.fillMaxWidth().height(26.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, amount -> change.consume(); onDrag(amount) }
                    }
                    .testTag("palette.grip"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.DragHandle, contentDescription = "Move toolbox", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (horizontal) Modifier.rotate(90f) else Modifier,
                )
            }

            PaletteButton(toolIcon(Tool.COMPONENTS), "Inspect parts", tool == Tool.COMPONENTS) { onTool(Tool.COMPONENTS) }
            PaletteButton(toolIcon(Tool.NETS), "Inspect tracks & planes", tool == Tool.NETS) { onTool(Tool.NETS) }
            PaletteDivider(horizontal)
            PaletteButton(toolIcon(Tool.PEN), "Brush (draw)", tool == Tool.PEN) { onTool(Tool.PEN) }
            PaletteButton(toolIcon(Tool.ERASER), "Eraser", tool == Tool.ERASER) { onTool(Tool.ERASER) }
            PaletteButton(toolIcon(Tool.PAINT), "Paint status", tool == Tool.PAINT) { onTool(Tool.PAINT) }
            PaletteDivider(horizontal)
            // Colour well.
            Tip("Colour & size") {
                Box(
                    Modifier.size(44.dp).padding(4.dp)
                        .background(wellArgb?.let { Color(it) } ?: Color.Transparent, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable(onClick = onWell)
                        .testTag("palette.well"),
                )
            }
            PaletteDivider(horizontal)
            PaletteButton(Icons.AutoMirrored.Filled.Undo, "Undo", selected = false, enabled = canUndo, onClick = onUndo)
            PaletteButton(Icons.Filled.DeleteSweep, "Clear all annotations", selected = false, onClick = onClear)
        }
        if (horizontal) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { items() }
        } else {
            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
        }
    }
}

@Composable
private fun PaletteDivider(horizontal: Boolean) {
    if (horizontal) {
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
    } else {
        HorizontalDivider(Modifier.width(36.dp).padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Which way the palette lies, from where it is: near the top/bottom edge it becomes a bar. */
object PaletteDock {
    enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    /** Edge nearest to point (x, y) in a w×h area. */
    fun nearestEdge(x: Float, y: Float, w: Float, h: Float): Edge {
        val d = mapOf(Edge.LEFT to x, Edge.RIGHT to w - x, Edge.TOP to y, Edge.BOTTOM to h - y)
        return d.minBy { it.value }.key
    }

    /**
     * New orientation for a palette whose grip is at (x, y). Changes only when the other kind of
     * edge is closer by more than [hysteresis] px, so it doesn't flicker near corners.
     */
    fun horizontalFor(x: Float, y: Float, w: Float, h: Float, current: Boolean, hysteresis: Float): Boolean {
        val toSide = minOf(x, w - x)
        val toTopBottom = minOf(y, h - y)
        return when {
            current && toSide + hysteresis < toTopBottom -> false
            !current && toTopBottom + hysteresis < toSide -> true
            else -> current
        }
    }

    /** Position flush against [edge] (with [margin]), keeping the other coordinate. */
    fun snap(pos: Offset, size: Offset, w: Float, h: Float, edge: Edge, margin: Float): Offset = when (edge) {
        Edge.LEFT -> Offset(margin, pos.y)
        Edge.RIGHT -> Offset(w - size.x - margin, pos.y)
        Edge.TOP -> Offset(pos.x, margin)
        Edge.BOTTOM -> Offset(pos.x, h - size.y - margin)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun PaletteButton(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Tip(label) {
        val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
        val fg = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            selected -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Box(
            Modifier.size(48.dp).background(bg, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .testTag("palette.$label"),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(26.dp)) }
    }
}

/** Flyout next to the palette: pen colour + size, or status colour for the paint bucket. */
@Composable
fun ToolOptions(
    tool: Tool,
    penArgb: Int,
    penWidthPx: Float,
    paintStatus: Status?,
    onPenColor: (Int) -> Unit,
    onPenWidth: (Float) -> Unit,
    onPaintStatus: (Status?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
        modifier = modifier.width(OPTIONS_WIDTH).testTag("palette.options"),
    ) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(tool), contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (tool == Tool.PAINT) "Paint status" else "Brush", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close options") }
            }
            if (tool == Tool.PAINT) {
                Text("Tap a part to fill it. Tap it again with the same colour to remove.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Status.entries.forEach { s -> OptionRow(s.label, Color(s.argb), paintStatus == s) { onPaintStatus(s) } }
                OptionRow("Remove status", null, paintStatus == null) { onPaintStatus(null) }
            } else {
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                PEN_COLORS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { Swatch(it, it == penArgb) { onPenColor(it) } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Size", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PEN_WIDTHS.forEach { w ->
                        val on = w == penWidthPx
                        val mod = Modifier.size(52.dp).testTag("palette.size.$w")
                        val dot = @Composable { Box(Modifier.size((w * 1.6f).dp).background(Color(penArgb), CircleShape).border(1.dp, Color.Gray, CircleShape)) }
                        if (on) Button(onClick = { onPenWidth(w) }, modifier = mod, contentPadding = ButtonDefaults.TextButtonContentPadding) { dot() }
                        else OutlinedButton(onClick = { onPenWidth(w) }, modifier = mod, contentPadding = ButtonDefaults.TextButtonContentPadding) { dot() }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Draw with a finger or the S Pen. Hold the S Pen button to erase.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(26.dp).background(color ?: Color.Transparent, RoundedCornerShape(6.dp))
                .border(2.dp, if (color == null) MaterialTheme.colorScheme.outline else Color.Transparent, RoundedCornerShape(6.dp)),
        )
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
