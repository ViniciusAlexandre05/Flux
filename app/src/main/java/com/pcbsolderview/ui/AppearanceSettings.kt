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
import androidx.compose.material3.AlertDialog
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

/** Appearance as a dialog (start screen). */
@Composable
fun AppearanceDialog(appearance: Appearance, onChange: (Appearance) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = { AppearanceSettings(appearance, onChange, Modifier.fillMaxWidth().padding(top = 4.dp)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
