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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.ThemeMode
import com.pcbsolderview.render.GridStyle

/** Theme mode, accent colour and board background. Changes apply immediately and are saved. */
@Composable
fun AppearanceSettings(appearance: Appearance, onChange: (Appearance) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("appearance"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { m ->
                val on = appearance.mode == m
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                        .clickable { onChange(appearance.copy(mode = m)) }
                        .testTag("theme.${m.name}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        m.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Text("Accent colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Appearance.ACCENTS.forEach { (name, argb) ->
                ColorChoice(argb, name, appearance.accent == argb, CircleShape) { onChange(appearance.copy(accent = argb)) }
            }
        }

        Text("Board background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.BACKGROUNDS.forEach { (name, argb) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ColorChoice(argb, name, appearance.boardBackground == argb, RoundedCornerShape(10.dp)) {
                        onChange(appearance.copy(boardBackground = argb))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ColorChoice(argb: Int, name: String, selected: Boolean, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val c = Color(argb)
    Box(
        Modifier.size(42.dp).background(c, shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .testTag("color.$name"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = "$name selected", tint = if (c.luminance() > 0.5f) Color.Black else Color.White)
    }
}

/** Everything in the gear menu: appearance, then interaction and diagnostics. */
@Composable
fun SettingsContent(a: Appearance, onChange: (Appearance) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle("Appearance")
        AppearanceSettings(a, onChange, Modifier.padding(vertical = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text("Background grid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Segmented(GridStyle.entries.map { it.label }, GridStyle.entries.indexOf(a.grid), tag = "grid") { i ->
            onChange(a.copy(grid = GridStyle.entries[i]))
        }
        Text(
            "Spacing adapts to the zoom (0.1 mm to 50 mm); every 5th line is brighter.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp)); HorizontalDivider()
        SectionTitle("Touch & S Pen")
        ToggleRow("Two-finger rotation", a.rotationGesture, "Twist with two fingers to rotate; locks near 0°, 90°, 180° and 270°.") {
            onChange(a.copy(rotationGesture = it))
        }
        ToggleRow("Only the S Pen draws", a.penOnlyDrawing, "Fingers always move the board, even with the Pen tool.") {
            onChange(a.copy(penOnlyDrawing = it))
        }
        Spacer(Modifier.height(8.dp)); HorizontalDivider()
        SectionTitle("Diagnostics")
        ToggleRow("Frame rate (FPS)", a.showFps, "Shows frames drawn per second while you pan and zoom.") {
            onChange(a.copy(showFps = it))
        }
    }
}

/** Row of equal options, one selected (used for theme, grid, view style). */
@Composable
internal fun Segmented(labels: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(44.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .clickable { onSelect(i) }
                    .testTag("$tag.$i"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

/** The gear menu as a dialog (start screen). */
@Composable
fun SettingsDialog(appearance: Appearance, onChange: (Appearance) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            SettingsContent(
                appearance, onChange,
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
