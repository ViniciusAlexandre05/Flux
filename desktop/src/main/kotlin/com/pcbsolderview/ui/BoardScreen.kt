package com.pcbsolderview.ui

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
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.BoardViewModel
import com.pcbsolderview.Tool
import com.pcbsolderview.UiState
import com.pcbsolderview.ViewLayout
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
    val schView = state.schematicScene?.let { rememberSchematicViewState(it) }
    var confirmClear by remember { mutableStateOf(false) }
    val appearance by vm.appearance.collectAsState()

    // Declared later = higher priority: Back closes panels/selection first, then leaves the board.
    BackHandler { vm.closeBoard() }
    BackHandler(enabled = !state.tool.isInspect) { vm.endDrawing() }
    BackHandler(enabled = state.showToolsPanel) { vm.toggleToolsPanel() }
    BackHandler(enabled = state.showLayersPanel) { vm.toggleLayersPanel() }
    BackHandler(enabled = state.showSettingsPanel) { vm.toggleSettingsPanel() }
    BackHandler(enabled = state.selectedRef != null || state.selectedNet != null || state.schNet != null) { vm.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = state.boardTitle ?: scene.board.name,
            subtitle = "${scene.board.components.size} parts · " + when (scene.board.source) {
                SourceFormat.IPC2581 -> "IPC-2581"
                SourceFormat.PICK_AND_PLACE -> "Pick & Place"
                SourceFormat.PCBDOC -> "PcbDoc"
                SourceFormat.SCHEMATIC_ONLY -> "schematic only"
            } + if (state.marks.isNotEmpty()) " · ${state.marks.count { it.value.argb == Status.OK.argb }}/${scene.board.components.size} OK" else "",
            state = state,
            vm = vm,
            onFit = {
                if (state.viewLayout != ViewLayout.SCHEMATIC) view.fit()
                if (state.viewLayout != ViewLayout.BOARD) schView?.fit(state.sheetIndex)
            },
            // Flip only changes which side is in front (parts, draw order); Mirror is separate.
            onFlip = vm::flipSide,
            onMirror = { toggleMirror(view, state, vm) },
        )
        // Desktop: the menu bar and keyboard shortcuts (Main.kt) reach Fit and Flip through here.
        RegisterDesktopCommands(
            fit = {
                if (state.viewLayout != ViewLayout.SCHEMATIC) view.fit()
                if (state.viewLayout != ViewLayout.BOARD) schView?.fit(state.sheetIndex)
            },
            flip = {
                if (state.viewLayout != ViewLayout.SCHEMATIC) vm.flipSide()
            },
            mirror = { toggleMirror(view, state, vm) },
            focusSearch = null,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val panelWidth = maxWidth * 0.3f
            val panelMaxHeight = maxHeight * 0.4f
            val layersMaxHeight = maxHeight - 16.dp
            val boardArea = @Composable { mod: Modifier ->
                Box(mod) {
                    val boardPane = @Composable { m: Modifier ->
                        BoardPane(view, state, vm, appearance, onClearRequest = { confirmClear = true }, panelMaxHeight = layersMaxHeight, modifier = m)
                    }
                    val pdf = state.schematicPdf
                    val schematicPane: (@Composable (Modifier) -> Unit)? = when {
                        schView != null -> { m -> SchematicPane(schView, state, vm, appearance, m) }
                        pdf != null -> { m -> PdfPane(pdf, appearance.rotationGesture, m) }
                        else -> null
                    }
                    when {
                        schematicPane == null || state.viewLayout == ViewLayout.BOARD -> boardPane(Modifier.fillMaxSize())
                        state.viewLayout == ViewLayout.SCHEMATIC -> schematicPane(Modifier.fillMaxSize())
                        else -> SplitPanes(vertical = !landscape, first = boardPane, second = schematicPane)
                    }
                    // Screen-wide overlays, above the pane(s).
                    if (state.results.isNotEmpty()) {
                        SearchResults(
                            state.results, vm::pickResult, vm::selectAllResults,
                            Modifier.align(Alignment.TopStart).offset { androidx.compose.ui.unit.IntOffset(searchFieldLeft.floatValue.toInt(), 0) }.padding(8.dp),
                        )
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
                val symbol = state.selectedSymbol
                val schNet = state.schNet
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
                        NetPanel(scene.board, net, onClose = vm::clearSelection, onComponent = vm::selectRef)
                    }
                    symbol != null && state.schematic != null -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = mod) {
                        SymbolPanel(
                            state.schematic, state.connectivity, symbol, state.marks[symbol],
                            onStatus = { s -> vm.setMark(symbol, s?.argb) }, onNet = vm::selectSchNet, onClose = vm::clearSelection,
                        )
                    }
                    schNet != null -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = mod) {
                        SchNetPanel(schNet, vm.boardNetsOf(schNet), onComponent = vm::selectRef, onClose = vm::clearSelection)
                    }
                }
            }
            val panelOpen = state.selectedRef != null || state.selectedNet != null || state.schNet != null
            // Desktop: opening or closing the panel must not move the board (or schematic) on screen.
            // Set during composition, so it is in place before the panes report their new size.
            val lastPanelOpen = remember { booleanArrayOf(panelOpen) }
            if (lastPanelOpen[0] != panelOpen) {
                lastPanelOpen[0] = panelOpen
                if (state.viewLayout != ViewLayout.SCHEMATIC || !state.hasSchematicView) view.anchorNextResize = true
                if (state.viewLayout != ViewLayout.BOARD) schView?.anchorNextResize = true
            }
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
    // Desktop: the tooltip appears when the mouse rests on the button (Android: long-press).
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.small, shadowElevation = 4.dp) {
                Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        },
        delayMillis = 400,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter, alignment = Alignment.BottomCenter, offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp),
        ),
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

/** Board / Split / Schematic, shown when a schematic is open. Board and Split need a board. */
@Composable
private fun LayoutToggle(layout: ViewLayout, hasBoard: Boolean, onLayout: (ViewLayout) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(
                Triple(ViewLayout.BOARD, Icons.Filled.DeveloperBoard, "Board"),
                Triple(ViewLayout.SPLIT, Icons.Filled.VerticalSplit, "Board and schematic"),
                Triple(ViewLayout.SCHEMATIC, Icons.Filled.Schema, "Schematic"),
            ).forEach { (l, icon, label) ->
                val on = layout == l
                val enabled = hasBoard || l == ViewLayout.SCHEMATIC
                Box(
                    Modifier.size(width = 52.dp, height = 44.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.extraLarge)
                        .clickable(enabled = enabled) { onLayout(l) }
                        .testTag("layout.${l.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    val fg = when {
                        on -> MaterialTheme.colorScheme.onPrimary
                        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    }
                    Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(24.dp))
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
// Desktop: the bar has more buttons (Mirror), so the field gets its own row sooner than on Android.
private val SEARCH_INLINE_MIN_WIDTH = 1240.dp

/** Desktop: left edge of the search field in the window, so the results open right under it. */
private val searchFieldLeft = androidx.compose.runtime.mutableFloatStateOf(0f)

@Composable
private fun TopBar(title: String, subtitle: String, state: UiState, vm: BoardViewModel, onFit: () -> Unit, onFlip: () -> Unit, onMirror: () -> Unit) {
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
                    if (state.hasSchematicView) LayoutToggle(state.viewLayout, state.hasBoard, vm::setViewLayout)
                    BarDivider()
                    BarIcon(Icons.Filled.Handyman, "Tools", vm::toggleToolsPanel, checked = state.showToolsPanel, tag = "tools.button")
                    BarIcon(Icons.Filled.FitScreen, "Fit to screen", onFit)
                    // Board-only controls are hidden while only the schematic is shown.
                    if (state.viewLayout != ViewLayout.SCHEMATIC) {
                        val realistic = state.display.viewMode == ViewMode.REALISTIC
                        BarIcon(
                            Icons.Filled.ViewInAr,
                            if (realistic) "Realistic view — click for CAD layers" else "CAD layers — click for realistic view",
                            { vm.setDisplay(state.display.copy(viewMode = if (realistic) ViewMode.CAD else ViewMode.REALISTIC)) },
                            checked = realistic, tag = "viewmode.button",
                        )
                        BarIcon(
                            Icons.Filled.Flip,
                            if (state.visibleSide == Side.TOP) "Showing top — click for bottom view" else "Showing bottom — click for top view",
                            onFlip, checked = state.visibleSide == Side.BOTTOM, tag = "flip",
                        )
                        // Mirror is independent of the side: either side can be shown mirrored or not.
                        BarIcon(
                            Icons.Filled.SwapHoriz,
                            if (state.mirrored) "Mirrored — click for the normal view" else "Mirror the view left-right",
                            onMirror, checked = state.mirrored, tag = "mirror",
                        )
                        BarIcon(Icons.Filled.Layers, "Layers & view", vm::toggleLayersPanel, checked = state.showLayersPanel)
                    }
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
    // Desktop: Ctrl+F focuses this field.
    val requester = remember { FocusRequester() }
    SideEffect { DesktopCommands.focusSearch = { runCatching { requester.requestFocus() } } }
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
        modifier = modifier.heightIn(max = 64.dp).focusRequester(requester)
            .onGloballyPositioned { searchFieldLeft.floatValue = it.positionInWindow().x }.testTag("search"),
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
/** Mirror button: mirrors the view left-right, whichever side is shown. */
private fun toggleMirror(view: BoardViewState, state: UiState, vm: BoardViewModel) {
    view.mirror()
    vm.setMirrored(!state.mirrored)
}
