package com.pcbsolderview.ui

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
fun DetailsPanel(board: Board, component: Component, onClose: () -> Unit, modifier: Modifier = Modifier) {
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
        Text(component.value.orDash(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("details.value"))
        Spacer(Modifier.height(12.dp))
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
            pads.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
