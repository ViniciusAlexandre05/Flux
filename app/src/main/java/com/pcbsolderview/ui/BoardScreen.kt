package com.pcbsolderview.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.UiState
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.BoardCanvas
import com.pcbsolderview.render.BoardScene
import com.pcbsolderview.render.ViewTransform

class BoardActions(
    val openFiles: () -> Unit,
    val openDemo: (String) -> Unit,
    val closeBoard: () -> Unit,
    val select: (Component?) -> Unit,
    val search: (String) -> Unit,
    val pickResult: (Component?) -> Unit,
    val clearSearch: () -> Unit,
    val dismissError: () -> Unit,
)

@Composable
fun BoardScreen(state: UiState, actions: BoardActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            val scene = state.scene
            if (scene == null) EmptyState(actions) else BoardContent(scene, state, actions)

            state.loading?.let { msg ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(msg, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            state.error?.let { msg ->
                AlertDialog(
                    onDismissRequest = actions.dismissError,
                    confirmButton = { TextButton(onClick = actions.dismissError) { Text("OK") } },
                    title = { Text("Could not open board") },
                    text = { Text(msg) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(actions: BoardActions) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PCB Solder View", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open an Altium export: IPC-2581 (.xml/.cvg), or a Pick & Place CSV together with its BOM. " +
                "You can select several files at once, or a .zip.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = actions.openFiles, modifier = Modifier.height(64.dp).widthIn(min = 280.dp)) {
            Text("Open board files", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { actions.openDemo("simple") }, modifier = Modifier.height(56.dp)) { Text("Demo: simple") }
            OutlinedButton(onClick = { actions.openDemo("double-sided") }, modifier = Modifier.height(56.dp)) { Text("Demo: double-sided") }
        }
    }
}

@Composable
private fun BoardContent(scene: BoardScene, state: UiState, actions: BoardActions) {
    var transform by remember(scene) { mutableStateOf<ViewTransform?>(null) }
    var viewport by remember { mutableStateOf(0 to 0) }
    val visibleSide = Side.TOP

    fun fit(t: ViewTransform?) {
        val (w, h) = viewport
        if (w == 0 || h == 0) return
        transform = t?.fit(scene.extent, w.toDouble(), h.toDouble())
            ?: ViewTransform.fitted(scene.extent, w.toDouble(), h.toDouble())
    }

    LaunchedEffect(scene, viewport) { if (transform == null) fit(null) }
    LaunchedEffect(state.focusRequest) {
        val c = state.selected ?: return@LaunchedEffect
        val t = transform ?: return@LaunchedEffect
        val (w, h) = viewport
        val sizeUm = maxOf(c.bounds.width, c.bounds.height, 2_000L).toDouble()
        val zoom = (minOf(w, h) * 0.25 / sizeUm / t.fitScale).coerceIn(2.0, ViewTransform.MAX_ZOOM)
        transform = t.centerOn(c.position.x.toDouble(), c.position.y.toDouble(), w.toDouble(), h.toDouble(), zoom)
    }
    // Declared later = higher priority: Back first closes the details panel, then leaves the board.
    BackHandler { actions.closeBoard() }
    BackHandler(enabled = state.selected != null) { actions.select(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = scene.board.name,
            state = state,
            actions = actions,
            onFit = { fit(transform) },
            onRotate = {
                val (w, h) = viewport
                transform = transform?.rotate90(w / 2.0, h / 2.0)?.fit(scene.extent, w.toDouble(), h.toDouble())
            },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val panelWidth = maxWidth * 0.3f
            val panelMaxHeight = maxHeight * 0.4f
            val board = @Composable { mod: Modifier ->
                Box(mod) {
                    BoardCanvas(
                        scene = scene,
                        transform = transform,
                        onTransform = { transform = it },
                        onViewport = { w, h -> viewport = w to h },
                        onTap = actions.select,
                        visibleSide = visibleSide,
                        selected = state.selected,
                        highlighted = state.highlighted,
                        modifier = Modifier.fillMaxSize().testTag("board"),
                    )
                    if (state.results.isNotEmpty()) {
                        SearchResults(state.results, actions.pickResult, Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                }
            }
            val selected = state.selected
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    board(Modifier.weight(1f).fillMaxHeight())
                    if (selected != null) {
                        Surface(tonalElevation = 3.dp, modifier = Modifier.width(panelWidth).fillMaxHeight()) {
                            DetailsPanel(scene.board, selected, onClose = { actions.select(null) })
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    board(Modifier.weight(1f).fillMaxWidth())
                    if (selected != null) {
                        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight)) {
                            DetailsPanel(scene.board, selected, onClose = { actions.select(null) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, state: UiState, actions: BoardActions, onFit: () -> Unit, onRotate: () -> Unit) {
    val focus = LocalFocusManager.current
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = actions.closeBoard, modifier = Modifier.size(56.dp).testTag("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to start")
            }
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 220.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.search,
                singleLine = true,
                placeholder = { Text("Search R12, 100nF…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = actions.clearSearch) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.pickResult(null); focus.clearFocus() }),
                modifier = Modifier.weight(1f).testTag("search"),
            )
            FilledTonalButton(onClick = onFit, modifier = Modifier.height(56.dp)) { Text("Fit") }
            FilledTonalButton(onClick = onRotate, modifier = Modifier.height(56.dp)) { Text("Rotate 90°") }
        }
    }
}

@Composable
private fun SearchResults(results: List<Component>, onPick: (Component) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.medium, modifier = modifier.widthIn(max = 420.dp).heightIn(max = 360.dp)) {
        LazyColumn(Modifier.testTag("search.results")) {
            items(results, key = { it.refDes }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(c); focus.clearFocus() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(c.refDes, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                    Text(c.value.orDash(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (c.side == Side.TOP) "Top" else "Bot", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
