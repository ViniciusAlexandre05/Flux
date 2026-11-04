package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.pcbsolderview.Status
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
    /** True while those identical parts are highlighted. */
    identicalHighlighted: Boolean = false,
    onHighlightIdentical: () -> Unit = {},
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
            Text(
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
        val others = identicalCount - 1
        val label = when {
            others <= 0 -> "No identical parts on this board"
            identicalHighlighted -> "Hide the $identicalCount identical parts"
            else -> "Highlight all $identicalCount identical parts"
        }
        if (identicalHighlighted) {
            Button(onClick = onHighlightIdentical, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("identical")) {
                Icon(Icons.Filled.Visibility, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(label)
            }
        } else {
            OutlinedButton(onClick = onHighlightIdentical, enabled = others > 0, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("identical")) {
                Icon(Icons.Filled.Visibility, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(label)
            }
        }
        Spacer(Modifier.height(12.dp))
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
        Field("Description", component.description)
        Field("Footprint", component.footprint)
        Field("Part number", component.partNumber)
        Field("Manufacturer", component.manufacturer)
        Field("Layer", if (component.side == Side.TOP) "Top" else "Bottom")
        Field("Position (mm)", "${formatMm(component.position.x)}, ${formatMm(component.position.y)}")
        Field("Rotation", String.format(Locale.US, "%.1f°", component.rotationDeg))

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

@Composable
private fun Field(label: String, value: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.orDash(), style = MaterialTheme.typography.bodyLarge)
    }
}
