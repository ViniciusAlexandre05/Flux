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
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import com.pcbsolderview.render.ViewMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import com.pcbsolderview.Status
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.model.identicalTo
import com.pcbsolderview.model.filterByMount
import com.pcbsolderview.model.matchesQuery
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.BoardViewModel
import com.pcbsolderview.Tool
import com.pcbsolderview.UiState
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.BoardScene

@Composable
fun BoardScreen(state: UiState, vm: BoardViewModel, openFiles: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            val scene = state.scene
            if (scene == null) {
                val appearance by vm.appearance.collectAsState()
                StartScreen(
                    state.recents, openFiles, vm::openDemo, vm::openRecent, vm::removeRecent,
                    renameRecent = vm::renameRecent,
                    appearance = appearance, onAppearance = vm::setAppearance,
                )
            }
            else BoardContent(scene, state, vm)

            state.loading?.let { msg ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val p = state.progress
                        if (p != null) {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.width(320.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("$msg ${(p * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(msg, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            state.error?.let { msg ->
                AlertDialog(
                    onDismissRequest = vm::dismissError,
                    confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                    title = { Text("Could not open board") },
                    text = { Text(msg) },
                )
            }
        }
    }
}

@Composable
private fun BoardContent(scene: BoardScene, state: UiState, vm: BoardViewModel) {
    val view = rememberBoardViewState(scene)
    var confirmClear by remember { mutableStateOf(false) }
    val appearance by vm.appearance.collectAsState()

    // Declared later = higher priority: Back closes panels/selection first, then leaves the board.
    BackHandler { vm.closeBoard() }
    BackHandler(enabled = !state.tool.isInspect) { vm.endDrawing() }
    BackHandler(enabled = state.showToolsPanel) { vm.toggleToolsPanel() }
    BackHandler(enabled = state.showLayersPanel) { vm.toggleLayersPanel() }
    BackHandler(enabled = state.showSettingsPanel) { vm.toggleSettingsPanel() }
    BackHandler(enabled = state.selected != null || state.selectedNet != null) { vm.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = state.boardTitle ?: scene.board.name,
            subtitle = "${scene.board.components.size} parts · " + when (scene.board.source) {
                SourceFormat.IPC2581 -> "IPC-2581"
                SourceFormat.PICK_AND_PLACE -> "Pick & Place"
                SourceFormat.PCBDOC -> "PcbDoc"
            } + if (state.marks.isNotEmpty()) " · ${state.marks.count { it.value.argb == Status.OK.argb }}/${scene.board.components.size} OK" else "",
            state = state,
            vm = vm,
            onFit = { view.fit() },
            onFlip = {
                view.mirror()
                vm.flipSide()
            },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val panelWidth = maxWidth * 0.3f
            val panelMaxHeight = maxHeight * 0.4f
            val layersMaxHeight = maxHeight - 16.dp
            val boardArea = @Composable { mod: Modifier ->
                Box(mod) {
                    BoardPane(
                        view, state, vm, appearance,
                        onClearRequest = { confirmClear = true },
                        panelMaxHeight = layersMaxHeight,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Screen-wide overlays, above the pane(s).
                    if (state.results.isNotEmpty()) {
                        SearchResults(state.results, vm::pickResult, vm::selectAllResults, Modifier.align(Alignment.TopCenter).padding(8.dp))
                    }
                    if (state.showSettingsPanel) {
                        SettingsPanel(
                            appearance, vm::setAppearance, onClose = vm::toggleSettingsPanel,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).heightIn(max = layersMaxHeight),
                        )
                    }
                    state.highlightLabel?.let { label ->
                        val counts = remember(state.highlightBase, state.highlightQuery, scene) {
                            com.pcbsolderview.model.MountFilter.entries.associateWith { f ->
                                scene.board.filterByMount(state.highlightBase, f).count { it.matchesQuery(state.highlightQuery) }
                            }
                        }
                        HighlightChip(
                            label = label,
                            shown = state.highlighted.size,
                            total = state.highlightBase.size,
                            override = state.highlightOverride,
                            filter = state.highlightFilter,
                            counts = counts,
                            query = state.highlightQuery,
                            highlightColor = LocalBoardColors.current.highlight,
                            onFilter = vm::setHighlightFilter,
                            onQuery = vm::setHighlightQuery,
                            onStatus = { argb -> vm.applyToHighlighted(argb) },
                            onClose = vm::clearHighlight,
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        )
                    }
                    state.message?.let { msg ->
                        // Long notices (skipped files) stay up long enough to read.
                        LaunchedEffect(msg) { kotlinx.coroutines.delay((msg.length * 60L).coerceIn(2800L, 9000L)); vm.dismissMessage() }
                        Text(
                            msg, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                                .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
                                .padding(horizontal = 14.dp, vertical = 8.dp).testTag("message"),
                        )
                    }
                }
            }
            val side = @Composable { mod: Modifier ->
                val sel = state.selected
                val net = state.selectedNet
                val identical = remember(scene, sel) { sel?.let { scene.board.identicalTo(it) }.orEmpty() }
                when {
                    sel != null -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = mod) {
                        DetailsPanel(
                            scene.board, sel, onClose = vm::clearSelection,
                            mark = state.marks[sel.refDes],
                            onStatus = { s -> vm.setMark(sel.refDes, s?.argb) },
                            onNet = vm::selectNet,
                            identicalCount = identical.size,
                            identicalTodo = identical.count { state.marks[it.refDes] == null },
                            identicalHighlighted = identical.size > 1 && state.highlightKey == vm.identicalKey(identical),
                            onHighlightIdentical = { all -> vm.toggleIdentical(sel, all) },
                            shows = appearance::shows,
                        )
                    }
                    net != null -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = mod) {
                        NetPanel(scene.board, net, onClose = vm::clearSelection, onComponent = { ref -> scene.board.component(ref)?.let(vm::select) })
                    }
                }
            }
            val panelOpen = state.selected != null || state.selectedNet != null
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    boardArea(Modifier.weight(1f).fillMaxHeight())
                    if (panelOpen) side(Modifier.width(panelWidth).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    boardArea(Modifier.weight(1f).fillMaxWidth())
                    if (panelOpen) side(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear annotations?") },
            text = { Text("Removes every drawing and status paint on this board. You can undo it.") },
            confirmButton = { TextButton(onClick = { vm.clearAnnotations(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Icon button for the top bar; long-press shows [label] as a tooltip. [checked] = toggle look. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit, checked: Boolean = false, tag: String? = null) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        val mod = Modifier.size(52.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        if (checked) FilledIconButton(onClick = onClick, modifier = mod) { Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp)) }
        else IconButton(onClick = onClick, modifier = mod) { Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp)) }
    }
}

/** Parts / Nets as one two-option control with labels. */
@Composable
private fun InspectModeToggle(tool: Tool, onTool: (Tool) -> Unit, showLabels: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Tool.COMPONENTS to "Parts", Tool.NETS to "Nets").forEach { (t, label) ->
                val on = tool == t
                Row(
                    Modifier.height(44.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.extraLarge)
                        .clickable { onTool(t) }
                        .padding(horizontal = 14.dp)
                        .testTag(if (t == Tool.COMPONENTS) "mode.parts" else "mode.nets"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(toolIcon(t), contentDescription = toolLabel(t), tint = fg, modifier = Modifier.size(22.dp))
                    if (showLabels) {
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BarDivider() {
    Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Below this width the search field moves to its own row (portrait tablet, phones). */
private val SEARCH_INLINE_MIN_WIDTH = 1040.dp

@Composable
private fun TopBar(title: String, subtitle: String, state: UiState, vm: BoardViewModel, onFit: () -> Unit, onFlip: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inline = maxWidth >= SEARCH_INLINE_MIN_WIDTH
            val compactToggle = maxWidth < 640.dp
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = vm::closeBoard, modifier = Modifier.size(52.dp).testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to start")
                    }
                    Column(if (inline) Modifier.widthIn(max = 220.dp) else Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (inline) SearchField(state, vm, Modifier.weight(1f))
                    InspectModeToggle(state.tool, vm::setTool, showLabels = !compactToggle)
                    BarDivider()
                    BarIcon(Icons.Filled.Handyman, "Tools", vm::toggleToolsPanel, checked = state.showToolsPanel, tag = "tools.button")
                    BarIcon(Icons.Filled.FitScreen, "Fit to screen", onFit)
            val realistic = state.display.viewMode == ViewMode.REALISTIC
            BarIcon(
                Icons.Filled.ViewInAr,
                if (realistic) "Realistic view — tap for CAD layers" else "CAD layers — tap for realistic view",
                { vm.setDisplay(state.display.copy(viewMode = if (realistic) ViewMode.CAD else ViewMode.REALISTIC)) },
                checked = realistic, tag = "viewmode.button",
            )
                    BarIcon(
                        Icons.Filled.Flip,
                        if (state.visibleSide == Side.TOP) "Showing top — tap for bottom view" else "Showing bottom — tap for top view",
                        onFlip, checked = state.visibleSide == Side.BOTTOM, tag = "flip",
                    )
                    BarIcon(Icons.Filled.Layers, "Layers & view", vm::toggleLayersPanel, checked = state.showLayersPanel)
            BarIcon(Icons.Filled.Settings, "Settings", vm::toggleSettingsPanel, checked = state.showSettingsPanel, tag = "settings.button")
                }
                if (!inline) SearchField(state, vm, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SearchField(state: UiState, vm: BoardViewModel, modifier: Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = Color.Transparent,
        ),
        value = state.query,
        onValueChange = vm::search,
        singleLine = true,
        // One line only: a squeezed placeholder used to wrap word by word and grow the bar.
        placeholder = { Text("Search R12, 100nF…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(24.dp)) },
        trailingIcon = {
            if (state.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { vm.pickResult(null); focus.clearFocus() }),
        modifier = modifier.heightIn(max = 64.dp).testTag("search"),
    )
}

@Composable
private fun SearchResults(results: List<Component>, onPick: (Component) -> Unit, onSelectAll: () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, shape = MaterialTheme.shapes.medium, modifier = modifier.widthIn(max = 420.dp).heightIn(max = 360.dp)) {
        LazyColumn(Modifier.testTag("search.results")) {
            // First option: highlight every result at once.
            item(key = "select-all") {
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectAll(); focus.clearFocus() }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("search.selectAll"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Filled.SelectAll, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(72.dp))
                    Text(
                        "Select all ${results.size} " + if (results.size == 1) "result" else "results",
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
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