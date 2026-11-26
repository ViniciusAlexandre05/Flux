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
import com.pcbsolderview.data.SchematicInk
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.pcbsolderview.data.DetailField
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import com.pcbsolderview.render.GridStyle
import com.pcbsolderview.render.Grid
import com.pcbsolderview.render.GridUnit
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import kotlin.math.roundToInt

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

    }
}

/** PCB view: colour behind the board. */
@Composable
private fun BoardBackground(a: Appearance, onChange: (Appearance) -> Unit) {
    Text("Board background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    SwatchRow(Appearance.BACKGROUNDS, a.boardBackground, tag = "bg") { onChange(a.copy(boardBackground = it)) }
}

/** Labelled colour squares; a value that is not a colour (alpha 0, e.g. "Theme") shows as a striped "auto" square. */
@Composable
private fun SwatchRow(choices: List<Pair<String, Int>>, selected: Int, tag: String, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("swatches.$tag")) {
        choices.forEach { (name, argb) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (argb ushr 24 == 0) AutoChoice(name, selected == argb) { onPick(argb) }
                else ColorChoice(argb, name, selected == argb, RoundedCornerShape(10.dp)) { onPick(argb) }
                Spacer(Modifier.height(2.dp))
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** "Theme" / "File": not a fixed colour. */
@Composable
private fun AutoChoice(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .testTag("color.$name"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = "$name selected", tint = MaterialTheme.colorScheme.primary)
        else Text("A", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Schematic view: paper, ink, traced-net colour, grid and text size. */
@Composable
private fun SchematicAppearance(a: Appearance, onChange: (Appearance) -> Unit) {
    val label = @Composable { s: String -> Text(s, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val hint = @Composable { s: String -> Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    Column(Modifier.testTag("appearance.schematic")) {
        label("Paper")
        Spacer(Modifier.height(6.dp))
        SwatchRow(Appearance.SCH_PAPERS, a.schPaper, tag = "paper") { onChange(a.copy(schPaper = it)) }
        hint("Theme: matches the app theme. File: the colour saved in the schematic.")

        Spacer(Modifier.height(12.dp))
        label("Lines and text")
        Spacer(Modifier.height(6.dp))
        Segmented(SchematicInk.entries.map { it.label }, a.schInk.ordinal, tag = "sch.ink") { onChange(a.copy(schInk = SchematicInk.entries[it])) }
        hint("Altium: the file's own colours (best on light paper). Inverted: same hues made light for dark paper.")

        Spacer(Modifier.height(12.dp))
        label("Traced net")
        Spacer(Modifier.height(6.dp))
        SwatchRow(Appearance.SCH_NET_COLORS, a.schNetColor, tag = "net") { onChange(a.copy(schNetColor = it)) }

        Spacer(Modifier.height(12.dp))
        label("Grid")
        Spacer(Modifier.height(6.dp))
        Segmented(GridStyle.entries.map { it.label }, GridStyle.entries.indexOf(a.schGrid), tag = "sch.grid") { onChange(a.copy(schGrid = GridStyle.entries[it])) }
        if (a.schGrid != GridStyle.NONE) {
            Spacer(Modifier.height(8.dp))
            val steps = Appearance.SCH_GRID_STEPS
            val idx = steps.indexOfFirst { it.second == a.schGridStepUm }.takeIf { it >= 0 } ?: 1
            Segmented(steps.map { it.first }, idx, tag = "sch.gridstep") { onChange(a.copy(schGridStepUm = steps[it].second)) }
            hint("Drawn on the paper from the sheet origin; 100 mil is Altium's usual grid.")
        }

        Spacer(Modifier.height(12.dp))
        label("Text size")
        val scales = Appearance.SCH_TEXT_SCALES
        val sIdx = scales.indices.minByOrNull { kotlin.math.abs(scales[it] - a.schTextScale) } ?: 1
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = sIdx.toFloat(),
                onValueChange = { v ->
                    val s = scales[v.roundToInt().coerceIn(0, scales.lastIndex)]
                    if (s != a.schTextScale) onChange(a.copy(schTextScale = s))
                },
                valueRange = 0f..scales.lastIndex.toFloat(),
                steps = scales.size - 2,
                modifier = Modifier.weight(1f).testTag("sch.textsize"),
            )
            Spacer(Modifier.width(10.dp))
            Text("${(scales[sIdx] * 100).roundToInt()} %", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
        }
        hint("Bigger text is easier to read at the bench; it may overlap other text on busy sheets.")
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
        Spacer(Modifier.height(12.dp))
        // Which view the options below configure. Theme and accent above apply to the whole app.
        var target by rememberSaveable { mutableStateOf(0) }
        Segmented(listOf("PCB view", "Schematic view"), target, tag = "appearance.target") { target = it }
        Spacer(Modifier.height(10.dp))
        if (target == 1) {
            SchematicAppearance(a, onChange)
        } else {
        BoardBackground(a, onChange)
        Spacer(Modifier.height(10.dp))
        Text("Background grid", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Segmented(GridStyle.entries.map { it.label }, GridStyle.entries.indexOf(a.grid), tag = "grid") { i ->
            onChange(a.copy(grid = GridStyle.entries[i]))
        }
        if (a.grid != GridStyle.NONE) {
            Spacer(Modifier.height(10.dp))
            Text("Grid spacing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Segmented(listOf("Manual", "Automatic"), if (a.gridAuto) 1 else 0, tag = "gridmode") { i -> onChange(a.copy(gridAuto = i == 1)) }
            if (a.gridAuto) {
                Text(
                    "Spacing follows the zoom (0.1 mm to 50 mm) so lines stay readable.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Segmented(GridUnit.entries.map { it.label }, GridUnit.entries.indexOf(a.gridUnit), tag = "gridunit") { i ->
                    val unit = GridUnit.entries[i]
                    // Keep roughly the same spacing when switching units.
                    val idx = Grid.nearestIndex(a.gridStepUm, unit)
                    onChange(a.copy(gridUnit = unit, gridStepUm = Grid.toMicrons(Grid.steps(unit)[idx], unit)))
                }
                val steps = Grid.steps(a.gridUnit)
                val idx = Grid.nearestIndex(a.gridStepUm, a.gridUnit)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = idx.toFloat(),
                        onValueChange = { v ->
                            val i = v.roundToInt().coerceIn(0, steps.lastIndex)
                            val um = Grid.toMicrons(steps[i], a.gridUnit)
                            if (um != a.gridStepUm) onChange(a.copy(gridStepUm = um))
                        },
                        valueRange = 0f..steps.lastIndex.toFloat(),
                        steps = steps.size - 2,
                        modifier = Modifier.weight(1f).testTag("grid.slider"),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(Grid.label(steps[idx], a.gridUnit), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                }
                Text(
                    "Fixed spacing; every 5th line is brighter. When zoomed far out only the bright lines are drawn.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
        Spacer(Modifier.height(12.dp)); HorizontalDivider()
        SectionTitle("Touch & S Pen")
        ToggleRow("Two-finger rotation", a.rotationGesture, "Twist with two fingers to rotate; locks near 0°, 90°, 180° and 270°.") {
            onChange(a.copy(rotationGesture = it))
        }
        ToggleRow("Only the S Pen draws", a.penOnlyDrawing, "Fingers always move the board, even with the Pen tool.") {
            onChange(a.copy(penOnlyDrawing = it))
        }
        ToggleRow("Keep the screen on", a.keepScreenOn, "While a board is open the screen does not turn off by itself.") {
            onChange(a.copy(keepScreenOn = it))
        }
        Spacer(Modifier.height(8.dp)); HorizontalDivider()
        SectionTitle("Schematic")
        ToggleRow("Follow selection", a.followSelection, "In split view, selecting a part or net on one side pans the other side to it.") {
            onChange(a.copy(followSelection = it))
        }
        Spacer(Modifier.height(8.dp)); HorizontalDivider()
        SectionTitle("Part details")
        Text(
            "Choose what the panel shows when you select a part.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        DetailFieldChips(a, onChange)
        Spacer(Modifier.height(8.dp)); HorizontalDivider()
        SectionTitle("Diagnostics")
        ToggleRow("Frame rate (FPS)", a.showFps, "Shows frames drawn per second while you pan and zoom.") {
            onChange(a.copy(showFps = it))
        }
        Spacer(Modifier.height(8.dp)); HorizontalDivider()
        SectionTitle("Updates")
        UpdatesSection(a.showTestVersions, onShowTest = { onChange(a.copy(showTestVersions = it)) })
    }
}

/** One chip per detail row; selected = shown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailFieldChips(a: Appearance, onChange: (Appearance) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailField.entries.forEach { f ->
            val on = a.shows(f)
            FilterChip(
                selected = on,
                onClick = { onChange(a.copy(hiddenDetails = if (on) a.hiddenDetails + f else a.hiddenDetails - f)) },
                label = { Text(f.label) },
                leadingIcon = if (on) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                modifier = Modifier.height(40.dp).testTag("detail.${f.name}"),
            )
        }
    }
    if (a.hiddenDetails.isNotEmpty()) {
        TextButton(onClick = { onChange(a.copy(hiddenDetails = emptySet())) }) { Text("Show all") }
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
