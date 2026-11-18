package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.pcbsolderview.Status
import com.pcbsolderview.data.DetailField
import androidx.compose.material.icons.filled.Visibility
import com.pcbsolderview.render.ComponentMark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.NaturalOrder
import com.pcbsolderview.model.Side
import java.util.Locale

const val DASH = "—"

fun String?.orDash(): String = if (this.isNullOrBlank()) DASH else this

fun formatMm(um: Long): String = String.format(Locale.US, "%.3f", um / 1000.0)

/** A problem with the design shown at the top of a part panel (e.g. a duplicate designator). */
@Composable
fun PanelWarning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp).testTag("panel.warning"),
    )
}

@Composable
fun DetailsPanel(
    board: Board,
    component: Component,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mark: ComponentMark? = null,
    onStatus: (Status?) -> Unit = {},
    onNet: (String) -> Unit = {},
    /** How many parts on the board are identical to this one (including it). */
    identicalCount: Int = 1,
    /** How many of those have no status yet (the ones a tap highlights). */
    identicalTodo: Int = identicalCount,
    /** True while those identical parts are highlighted. */
    identicalHighlighted: Boolean = false,
    /** Tap: false (parts without a status). Press and hold: true (all of them). */
    onHighlightIdentical: (all: Boolean) -> Unit = {},
    /** Which rows to show (Settings › Part details). */
    shows: (DetailField) -> Boolean = { true },
    /** Design problem to point out (duplicate designator in the schematic), null when none. */
    warning: String? = null,
) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()).testTag("details")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                component.refDes,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).testTag("details.refdes"),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close details")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (shows(DetailField.VALUE)) Text(
                component.value.orDash(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f, fill = false).testTag("details.value"),
            )
            Spacer(Modifier.width(12.dp))
            val top = component.side == Side.TOP
            Text(
                if (top) "TOP" else "BOTTOM",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (top) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .background(if (top) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        warning?.let { PanelWarning(it); Spacer(Modifier.height(6.dp)) }
        if (shows(DetailField.IDENTICAL)) {
        IdenticalButton(identicalCount, identicalTodo, identicalHighlighted, onHighlightIdentical)
        Spacer(Modifier.height(12.dp))
        }
        if (shows(DetailField.STATUS)) {
        Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            val current = mark?.let { Status.of(it.argb) }
            Status.entries.forEach { s ->
                val selected = current == s
                Box(
                    Modifier.size(width = 64.dp, height = 44.dp)
                        .background(Color(s.argb).copy(alpha = if (selected) 1f else 0.35f), RoundedCornerShape(8.dp))
                        .border(if (selected) 3.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
                        .clickable { onStatus(if (selected) null else s) }
                        .testTag("status.${s.name}"),
                    contentAlignment = Alignment.Center,
                ) { Text(s.label, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            }
        }
        }
        val compPads = board.padsOf(component)
        if (shows(DetailField.DESCRIPTION)) Field("Description", component.description)
        if (shows(DetailField.PART_NUMBER)) Field(
            "Part number", component.partNumber,
            note = if (component.partNumberFromLibrary) "From the library name; open a BOM with the board for the exact part number" else null,
        )
        if (shows(DetailField.MANUFACTURER)) Field("Manufacturer", component.manufacturer)
        if (shows(DetailField.FOOTPRINT)) Field("Footprint", component.footprint)
        if (shows(DetailField.LIBRARY)) Field("Library", component.libraryRef)
        if (shows(DetailField.LAYER)) Field("Layer", if (component.side == Side.TOP) "Top" else "Bottom")
        if (shows(DetailField.MOUNT)) Field("Mounting", mountingText(compPads))
        if (shows(DetailField.HEIGHT)) Field("Height", component.heightUm?.let { String.format(Locale.US, "%.2f mm", it / 1000.0) })
        if (shows(DetailField.POSITION)) Field("Position (mm)", "${formatMm(component.position.x)}, ${formatMm(component.position.y)}")
        if (shows(DetailField.ROTATION)) Field("Rotation", String.format(Locale.US, "%.1f°", component.rotationDeg))

        if (shows(DetailField.NETS)) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Pads / nets", style = MaterialTheme.typography.titleMedium)
        // Through-hole pins appear once per copper layer; list each pin once, in natural order.
        val pads = board.padsOf(component).distinctBy { it.name to it.net }.sortedWith(compareBy(NaturalOrder) { it.name })
        if (pads.isEmpty()) {
            Text(DASH, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            Text("Tap a pin to highlight its net", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pads.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp)
                        .then(if (p.net != null) Modifier.clickable { onNet(p.net!!) } else Modifier)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.name, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                    Text(p.net.orDash())
                }
            }
        }
        }
    }
}

/**
 * Highlight identical parts. Tap: the ones without a status yet. Press and hold: all of them, so a
 * status can be set on every one (existing statuses are overwritten).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdenticalButton(total: Int, todo: Int, highlighted: Boolean, onHighlight: (all: Boolean) -> Unit) {
    val enabled = total > 1
    val title = when {
        !enabled -> "No identical parts on this board"
        highlighted -> "Hide the highlight"
        todo == 0 -> "All $total already have a status"
        else -> "Highlight $todo without status"
    }
    val subtitle = if (enabled && !highlighted) "Hold to highlight all $total and set a status on every one" else null
    val bg = if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        highlighted -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(bg, RoundedCornerShape(28.dp))
            .border(1.dp, if (highlighted) Color.Transparent else MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = { onHighlight(false) },
                onLongClick = { onHighlight(true) },
                onLongClickLabel = "Highlight all $total",
            )
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .testTag("identical"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Visibility, contentDescription = null, tint = fg)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = fg, style = MaterialTheme.typography.labelLarge)
            if (subtitle != null) Text(subtitle, color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** "SMD · 16 pins", "Through-hole · 3 pins", "Mixed …", or null when the part has no pads. */
fun mountingText(pads: List<com.pcbsolderview.model.Pad>): String? {
    val pins = pads.map { it.name }.distinct().size
    if (pins == 0) return null
    val th = pads.any { it.isThroughHole }; val smd = pads.any { !it.isThroughHole }
    val kind = when { th && smd -> "Mixed SMD / through-hole"; th -> "Through-hole"; else -> "SMD" }
    return "$kind · $pins pin" + if (pins == 1) "" else "s"
}

@Composable
private fun Field(label: String, value: String?, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.orDash(), style = MaterialTheme.typography.bodyLarge)
        if (note != null && value != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
