package com.pcbsolderview.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import com.pcbsolderview.data.Appearance
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.R
import com.pcbsolderview.RecentItem
import com.pcbsolderview.Status
import com.pcbsolderview.data.RecentBoardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Start screen: recent boards (with their annotation progress), open a new board, or a demo. */
@Composable
fun StartScreen(
    recents: List<RecentItem>,
    openFiles: () -> Unit,
    openDemo: (String) -> Unit,
    openRecent: (RecentBoardEntity) -> Unit,
    removeRecent: (RecentBoardEntity) -> Unit,
    appearance: Appearance = Appearance(),
    onAppearance: (Appearance) -> Unit = {},
) {
    var showAppearance by remember { mutableStateOf(false) }
    if (showAppearance) SettingsDialog(appearance, onAppearance, onDismiss = { showAppearance = false })
    val gear = @Composable {
        IconButton(onClick = { showAppearance = true }, modifier = Modifier.size(52.dp).testTag("settings.button")) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
    if (recents.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(openFiles, openDemo)
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { gear() }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FluxLogo(56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text("Pick up where you left off", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            gear()
            Spacer(Modifier.width(8.dp))
            DemoButton(openDemo)
            Spacer(Modifier.width(12.dp))
            Button(onClick = openFiles, modifier = Modifier.height(56.dp).testTag("open")) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Open new board")
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Recent boards", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text("${recents.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().testTag("recents"),
        ) {
            items(recents, key = { it.board.boardKey }) { item ->
                RecentCard(item, onOpen = { openRecent(item.board) }, onRemove = { removeRecent(item.board) })
            }
        }
    }
}

@Composable
private fun DemoButton(openDemo: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.height(56.dp)) {
            Icon(Icons.Filled.Science, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Demo")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Simple board") }, onClick = { open = false; openDemo("simple") })
            DropdownMenuItem(text = { Text("Double-sided board") }, onClick = { open = false; openDemo("double-sided") })
        }
    }
}

@Composable
private fun RecentCard(item: RecentItem, onOpen: () -> Unit, onRemove: () -> Unit) {
    val b = item.board
    var thumb by remember(item.thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.thumbnail) {
        val path = item.thumbnail ?: return@LaunchedEffect
        thumb = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("recent.${b.name}"),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.Ink), contentAlignment = Alignment.Center) {
                val img = thumb
                if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
                else Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
            }
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val source = if (b.source == "IPC2581") "IPC-2581" else if (b.source == "PICK_AND_PLACE") "Pick & Place" else b.source
                        val ago = DateUtils.getRelativeTimeSpanString(b.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        Text("$source · ${b.components} parts · $ago", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${b.name}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; onRemove() })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val progress = if (b.components > 0) item.ok.toFloat() / b.components else 0f
                Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = Color(Status.OK.argb),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f).height(6.dp),
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
private fun Count(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
