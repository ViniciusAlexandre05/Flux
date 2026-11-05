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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.PEN_WIDTHS
import com.pcbsolderview.Status
import com.pcbsolderview.Tool
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.NaturalOrder
import com.pcbsolderview.render.BoardScene
import com.pcbsolderview.render.DisplayOptions
import com.pcbsolderview.render.ViewMode
import androidx.compose.ui.draw.alpha

// ---------------------------------------------------------------------------- toolbox

fun toolIcon(t: Tool): ImageVector = when (t) {
    Tool.COMPONENTS -> Icons.Filled.Memory
    Tool.NETS -> Icons.Filled.Timeline
    Tool.PEN -> Icons.Filled.Brush
    Tool.ERASER -> AppIcons.Eraser
    Tool.PAINT -> Icons.Filled.FormatColorFill
}

fun toolLabel(t: Tool): String = when (t) {
    Tool.COMPONENTS -> "Inspect parts"
    Tool.NETS -> "Inspect tracks & planes"
    Tool.PEN -> "Brush"
    Tool.ERASER -> "Eraser"
    Tool.PAINT -> "Paint status"
}

/** Shown while a drawing tool is active and the menu is closed; tap ✕ to go back to inspecting. */
@Composable
fun ActiveToolChip(tool: Tool, argb: Int?, onOpen: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp, shape = RoundedCornerShape(28.dp), modifier = modifier) {
        Row(Modifier.clickable(onClick = onOpen).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(toolIcon(tool), contentDescription = null)
            if (argb != null) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(18.dp).background(Color(argb), CircleShape)) }
            Spacer(Modifier.width(8.dp))
            Text(toolLabel(tool), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onDone, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Stop ${toolLabel(tool)}") }
        }
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, shape = RoundedCornerShape(16.dp), modifier = modifier.width(360.dp)) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()).testTag("layers")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers & view", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close layers") }
            }
            Text("View style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("CAD layers", "Realistic"), if (display.viewMode == ViewMode.REALISTIC) 1 else 0, tag = "viewmode") { i ->
                onDisplay(display.copy(viewMode = if (i == 1) ViewMode.REALISTIC else ViewMode.CAD))
            }
            if (display.viewMode == ViewMode.REALISTIC) {
                Spacer(Modifier.height(10.dp))
                Text("Solder mask", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayOptions.MASK_COLORS.forEach { (name, argb) ->
                        Box(
                            Modifier.size(38.dp).background(Color(argb), RoundedCornerShape(10.dp))
                                .border(
                                    if (display.maskArgb == argb) 3.dp else 1.dp,
                                    if (display.maskArgb == argb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onDisplay(display.copy(maskArgb = argb)) }
                                .testTag("mask.$name"),
                        )
                    }
                }
                Text(
                    "Shows the board as assembled: mask with the copper showing through, gold pads, silkscreen and part bodies.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // Layer choices only apply to the CAD view; in the realistic view they are greyed out.
            val cad = display.viewMode != ViewMode.REALISTIC
            Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
            ToggleRow("Designators", display.showDesignators) { onDisplay(display.copy(showDesignators = it)) }
            ToggleRow("Component outlines", display.showComponentOutlines, enabled = cad) { onDisplay(display.copy(showComponentOutlines = it)) }
            if (scene.hasGeometry) ToggleRow("Through-hole pads & vias", display.showMultiLayer, enabled = cad) { onDisplay(display.copy(showMultiLayer = it)) }
            ToggleRow("Annotations & status paint", display.showAnnotations) { onDisplay(display.copy(showAnnotations = it)) }

            if (scene.layers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAll(true) }, enabled = cad) { Text("All") }
                    TextButton(onClick = { onAll(false) }, enabled = cad) { Text("None") }
                    TextButton(onClick = onDefaults, enabled = cad) { Text("Default") }
                }
                if (!cad) {
                    Text(
                        "Layers apply to the CAD view. Switch the view style above to change them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp).testTag("layers.disabled"),
                    )
                }
                scene.layers.sortedBy { it.layer.order }.forEach { ls ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .alpha(if (cad) 1f else 0.38f)
                            .clickable(enabled = cad) { onToggleLayer(ls.name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ls.name !in display.hiddenLayers, onCheckedChange = { onToggleLayer(ls.name) }, enabled = cad)
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
internal fun ToggleRow(label: String, checked: Boolean, description: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.38f).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** The gear menu inside a board: app-wide settings, saved. */
@Composable
fun SettingsPanel(appearance: Appearance, onChange: (Appearance) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, shape = RoundedCornerShape(16.dp), modifier = modifier.width(380.dp)) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()).testTag("settings")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close settings") }
            }
            SettingsContent(appearance, onChange)
        }
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
