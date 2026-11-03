package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.PEN_COLORS
import com.pcbsolderview.PEN_WIDTHS
import com.pcbsolderview.Status
import com.pcbsolderview.Tool
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.NaturalOrder
import com.pcbsolderview.render.BoardScene
import com.pcbsolderview.render.DisplayOptions

// ---------------------------------------------------------------------------- toolbox

/** Big buttons for use with gloves / tweezers in hand. */
@Composable
fun ToolRail(
    tool: Tool,
    canUndo: Boolean,
    onTool: (Tool) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolButton("Select", tool == Tool.SELECT) { onTool(Tool.SELECT) }
            ToolButton("Pen", tool == Tool.PEN) { onTool(Tool.PEN) }
            ToolButton("Eraser", tool == Tool.ERASER) { onTool(Tool.ERASER) }
            ToolButton("Paint", tool == Tool.PAINT) { onTool(Tool.PAINT) }
            HorizontalDivider(Modifier.width(88.dp))
            OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.width(88.dp).height(52.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Undo") }
            OutlinedButton(onClick = onClear, modifier = Modifier.width(88.dp).height(52.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Clear") }
        }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val mod = Modifier.width(88.dp).height(52.dp).testTag("tool.$label")
    if (selected) Button(onClick = onClick, modifier = mod, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = mod, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(label) }
}

@Composable
fun PenOptions(argb: Int, widthPx: Float, onColor: (Int) -> Unit, onWidth: (Float) -> Unit, modifier: Modifier = Modifier) {
    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pen colour", style = MaterialTheme.typography.labelLarge)
            PEN_COLORS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Swatch(it, it == argb) { onColor(it) } } }
            }
            Text("Width", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PEN_WIDTHS.zip(listOf("Thin", "Med", "Thick")).forEach { (w, l) ->
                    if (w == widthPx) Button(onClick = { onWidth(w) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(l) }
                    else OutlinedButton(onClick = { onWidth(w) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(l) }
                }
            }
            Text("Draw with one finger or the S Pen.\nTwo fingers still move the board.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PaintOptions(status: Status?, onStatus: (Status?) -> Unit, modifier: Modifier = Modifier) {
    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tap a part to paint it", style = MaterialTheme.typography.labelLarge)
            Status.entries.forEach { s -> StatusRow(s.label, Color(s.argb), status == s) { onStatus(s) } }
            StatusRow("Remove status", null, status == null) { onStatus(null) }
            Text("Tapping a part again with the same\ncolour removes it.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusRow(label: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.width(220.dp).height(48.dp).background(bg, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).background(color ?: Color.Transparent, RoundedCornerShape(6.dp))
                .border(2.dp, if (color == null) MaterialTheme.colorScheme.outline else Color.Transparent, RoundedCornerShape(6.dp)),
        )
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).background(Color(argb), CircleShape)
            .border(if (selected) 4.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
            .clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------------------- layers / display

@Composable
fun LayersPanel(
    scene: BoardScene,
    display: DisplayOptions,
    onDisplay: (DisplayOptions) -> Unit,
    onToggleLayer: (String) -> Unit,
    onAll: (Boolean) -> Unit,
    onDefaults: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp, shape = RoundedCornerShape(16.dp), modifier = modifier.width(340.dp)) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()).testTag("layers")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Display", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close layers") }
            }
            ToggleRow("Designators", display.showDesignators) { onDisplay(display.copy(showDesignators = it)) }
            ToggleRow("Component outlines", display.showComponentOutlines) { onDisplay(display.copy(showComponentOutlines = it)) }
            if (scene.hasGeometry) ToggleRow("Through-hole pads & vias", display.showMultiLayer) { onDisplay(display.copy(showMultiLayer = it)) }
            ToggleRow("Annotations & status paint", display.showAnnotations) { onDisplay(display.copy(showAnnotations = it)) }
            ToggleRow("Two-finger rotation", display.rotationGesture) { onDisplay(display.copy(rotationGesture = it)) }
            ToggleRow("Frame rate (FPS)", display.showFps) { onDisplay(display.copy(showFps = it)) }

            if (scene.layers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAll(true) }) { Text("All") }
                    TextButton(onClick = { onAll(false) }) { Text("None") }
                    TextButton(onClick = onDefaults) { Text("Default") }
                }
                scene.layers.sortedBy { it.layer.order }.forEach { ls ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onToggleLayer(ls.name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ls.name !in display.hiddenLayers, onCheckedChange = { onToggleLayer(ls.name) })
                        Box(Modifier.size(18.dp).background(ls.color, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ls.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(layerKind(ls.layer.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun layerKind(t: LayerType) = when (t) {
    LayerType.COPPER -> "Copper"
    LayerType.SILKSCREEN -> "Silkscreen"
    LayerType.SOLDER_MASK -> "Solder mask"
    LayerType.PASTE -> "Paste"
    LayerType.ASSEMBLY -> "Assembly"
    LayerType.OUTLINE -> "Outline"
    LayerType.DRILL -> "Drill"
    LayerType.DOCUMENT -> "Mechanical / document"
    LayerType.OTHER -> "Other"
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------- net details

@Composable
fun NetPanel(board: Board, net: String, onClose: () -> Unit, onComponent: (String) -> Unit, modifier: Modifier = Modifier) {
    val pads = board.nets[net]?.padIds?.mapNotNull { board.pads.getOrNull(it) }.orEmpty()
    val byComponent = pads.filter { it.componentRef != null }
        .groupBy { it.componentRef!! }
        .mapValues { (_, ps) -> ps.map { it.name }.distinct().sortedWith(NaturalOrder) }
        .toSortedMap(NaturalOrder)
    val tracks = board.tracks.count { it.net == net }
    val vias = board.vias.count { it.net == net }
    val pours = board.fills.count { it.net == net }
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()).testTag("net")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Net", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(net, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("net.name"))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close net") }
        }
        Text("${byComponent.size} parts · $tracks track segments · $vias vias" + if (pours > 0) " · $pours pours" else "")
        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
        Text("Connected pins", style = MaterialTheme.typography.titleMedium)
        if (byComponent.isEmpty()) Text(DASH)
        byComponent.forEach { (ref, pins) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onComponent(ref) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ref, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                Text("pin " + pins.joinToString(", "), modifier = Modifier.weight(1f))
                Text(board.component(ref)?.value.orDash(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
