package com.pcbsolderview.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.RecentItem
import com.pcbsolderview.Status
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.RecentBoardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(16.dp)

/**
 * Start screen: a grid whose first cell opens new board files, followed by recent boards
 * (preview, details, annotation progress). Demos and settings sit in the header.
 */
@Composable
fun StartScreen(
    recents: List<RecentItem>,
    openFiles: () -> Unit,
    openDemo: (String) -> Unit,
    openRecent: (RecentBoardEntity) -> Unit,
    removeRecent: (RecentBoardEntity) -> Unit,
    renameRecent: (RecentBoardEntity, String) -> Unit = { _, _ -> },
    appearance: Appearance = Appearance(),
    onAppearance: (Appearance) -> Unit = {},
) {
    var showSettings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RecentBoardEntity?>(null) }
    if (showSettings) SettingsDialog(appearance, onAppearance, onDismiss = { showSettings = false })
    renaming?.let { r -> RenameDialog(r, onSave = { renameRecent(r, it); renaming = null }, onDismiss = { renaming = null }) }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Boards", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (recents.isEmpty()) "Open an Altium export to get started"
                    else "${recents.size} recent · pick up where you left off",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DemoButton(openDemo)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { showSettings = true }, modifier = Modifier.size(52.dp).testTag("settings.button")) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().testTag("recents"),
        ) {
            item(key = "open") { OpenCard(openFiles) }
            items(recents, key = { it.board.boardKey }) { item ->
                RecentCard(item, onOpen = { openRecent(item.board) }, onRename = { renaming = item.board }, onRemove = { removeRecent(item.board) })
            }
            if (recents.isEmpty()) {
                item(key = "demo.simple") { DemoCard("Simple board", "Pick & Place + BOM · 10 parts") { openDemo("simple") } }
                item(key = "demo.double") { DemoCard("Double-sided board", "Pick & Place + BOM · parts on both sides") { openDemo("double-sided") } }
                item(key = "demo.schematic") { DemoCard("Board + schematic", "IPC-2581 + .SchDoc · split view") { openDemo("ipc-mini") } }
            }
        }
    }
}

@Composable
private fun DemoButton(openDemo: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Demo")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Simple board") }, onClick = { open = false; openDemo("simple") })
            DropdownMenuItem(text = { Text("Double-sided board") }, onClick = { open = false; openDemo("double-sided") })
            DropdownMenuItem(text = { Text("Board + schematic") }, onClick = { open = false; openDemo("ipc-mini") })
        }
    }
}

/** Card frame shared by every cell, so the grid reads as one set. */
@Composable
private fun CardFrame(onClick: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = CardShape,
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) { content() }
}

/** First cell: open new board files. Same footprint as a board card. */
@Composable
private fun OpenCard(onOpen: () -> Unit) {
    val dash = MaterialTheme.colorScheme.outline
    CardFrame(onOpen, "open") {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(10.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = dash, cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
                }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
                Text("Open new board", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "IPC-2581 (.cvg / .xml), or Pick & Place + BOM, or a .zip",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Select several files at once to combine them.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DemoCard(title: String, subtitle: String, onOpen: () -> Unit) {
    CardFrame(onOpen, "demo.$title") {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.Ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            }
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("Demo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RecentCard(item: RecentItem, onOpen: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val b = item.board
    var thumb by remember(item.thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.thumbnail) {
        val path = item.thumbnail ?: return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    var menu by remember { mutableStateOf(false) }
    CardFrame(onOpen, "recent.${b.displayName}") {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.Ink), contentAlignment = Alignment.Center) {
                thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(10.dp)) }
            }
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val source = when (b.source) { "IPC2581" -> "IPC-2581"; "PICK_AND_PLACE" -> "Pick & Place"; else -> b.source }
                        val ago = DateUtils.getRelativeTimeSpanString(b.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        Text("$source · ${b.components} parts · $ago", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${b.displayName}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; onRemove() })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val progress = if (b.components > 0) item.ok.toFloat() / b.components else 0f
                Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = Color(Status.OK.argb),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f).height(5.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${item.ok}/${b.components} OK", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (item.problems > 0) Count(Color(Status.PROBLEM.argb), "${item.problems} problem" + if (item.problems > 1) "s" else "")
                    if (item.check > 0) Count(Color(Status.CHECK.argb), "${item.check} to check")
                    if (item.info > 0) Count(Color(Status.INFO.argb), "${item.info} info")
                    if (item.drawings > 0) Count(MaterialTheme.colorScheme.onSurfaceVariant, "${item.drawings} drawing" + if (item.drawings > 1) "s" else "")
                    if (item.problems + item.check + item.info + item.drawings == 0 && item.ok == 0) {
                        Text("No annotations yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(r: RecentBoardEntity, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(r.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename board") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().testTag("rename.field"),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "File name: ${r.name}. Leave empty to use it again. Drawings and status stay with the board.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Count(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
