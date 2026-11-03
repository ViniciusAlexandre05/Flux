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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
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
import com.pcbsolderview.render.BoardCanvas
import com.pcbsolderview.render.BoardScene
import com.pcbsolderview.render.CanvasTool
import com.pcbsolderview.render.ViewTransform

@Composable
fun BoardScreen(state: UiState, vm: BoardViewModel, openFiles: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            val scene = state.scene
            if (scene == null) EmptyState(openFiles, vm::openDemo) else BoardContent(scene, state, vm)

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
private fun EmptyState(openFiles: () -> Unit, openDemo: (String) -> Unit) {
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
        Button(onClick = openFiles, modifier = Modifier.height(64.dp).widthIn(min = 280.dp)) {
            Text("Open board files", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { openDemo("simple") }, modifier = Modifier.height(56.dp)) { Text("Demo: simple") }
            OutlinedButton(onClick = { openDemo("double-sided") }, modifier = Modifier.height(56.dp)) { Text("Demo: double-sided") }
        }
    }
}

@Composable
private fun BoardContent(scene: BoardScene, state: UiState, vm: BoardViewModel) {
    var transform by remember(scene) { mutableStateOf<ViewTransform?>(null) }
    var viewport by remember { mutableStateOf(0 to 0) }
    var confirmClear by remember { mutableStateOf(false) }
    var radial by remember(scene) { mutableStateOf<RadialState?>(null) }
    val density = LocalDensity.current
    val innerPx = with(density) { RADIAL_INNER.toPx() }
    val outerPx = with(density) { RADIAL_OUTER.toPx() }

    fun applyRadial(r: RadialState, index: Int) {
        vm.setMark(r.refDes, RADIAL_ITEMS[index].status?.argb)
        radial = null
    }

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
    // Declared later = higher priority: Back closes panels/selection first, then leaves the board.
    BackHandler { vm.closeBoard() }
    BackHandler(enabled = !state.tool.isInspect) { vm.endDrawing() }
    BackHandler(enabled = state.showToolsPanel) { vm.toggleToolsPanel() }
    BackHandler(enabled = state.showLayersPanel) { vm.toggleLayersPanel() }
    BackHandler(enabled = state.selected != null || state.selectedNet != null) { vm.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = scene.board.name,
            state = state,
            vm = vm,
            onFit = { fit(transform) },
            onFlip = {
                val (w, _) = viewport
                transform = transform?.mirror(w / 2.0)
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
                    BoardCanvas(
                        scene = scene,
                        transform = transform,
                        onTransform = { transform = it },
                        onViewport = { w, h -> viewport = w to h },
                        onTap = vm::onBoardTap,
                        visibleSide = state.visibleSide,
                        options = state.display,
                        selected = state.selected,
                        highlighted = state.highlighted,
                        selectedNet = state.selectedNet,
                        marks = state.marks,
                        strokes = state.strokes,
                        tool = if (state.tool == Tool.PEN) CanvasTool.PEN else CanvasTool.NAVIGATE,
                        penArgb = state.penArgb,
                        penWidthPx = state.penWidthPx,
                        onStroke = vm::addStroke,
                        // Long-press a part: status ring. Slide to an option and lift to apply.
                        onLongPress = if (state.tool == Tool.PEN || state.tool == Tool.ERASER) null else { p ->
                            val t = transform
                            val c = t?.let {
                                val (bx, by) = it.toBoard(p.x.toDouble(), p.y.toDouble())
                                vm.componentAt(bx, by, com.pcbsolderview.render.TOUCH_SLOP_PX / it.scale)
                            }
                            if (c == null) false else {
                                val current = state.marks[c.refDes]?.let { m -> RADIAL_ITEMS.indexOfFirst { it.status?.argb == m.argb }.takeIf { it >= 0 } }
                                val (w, h) = viewport
                                radial = RadialState(RadialGeometry.clamp(p, w.toFloat(), h.toFloat(), outerPx, 12f), c.refDes, current)
                                true
                            }
                        },
                        onLongPressDrag = { p -> radial = radial?.let { it.copy(hovered = RadialGeometry.itemAt(it.center, p, innerPx)) } },
                        onLongPressRelease = { p ->
                            radial?.let { r ->
                                val i = RadialGeometry.itemAt(r.center, p, innerPx)
                                // Lifted on an option: apply. Lifted in the middle: keep the ring open for a tap.
                                if (i != null) applyRadial(r, i) else radial = r.copy(hovered = null)
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("board"),
                    )
                    radial?.let { r -> RadialStatusMenu(r, onPick = { applyRadial(r, it) }, onDismiss = { radial = null }) }
                    if (state.showToolsPanel) {
                        ToolsMenu(
                            tool = state.tool, penArgb = state.penArgb, penWidthPx = state.penWidthPx,
                            paintStatus = state.paintStatus, canUndo = state.canUndo,
                            onTool = vm::setTool, onPenColor = vm::setPen, onPenWidth = vm::setPenWidth,
                            onPaintStatus = vm::setPaintStatus, onUndo = vm::undo, onClear = { confirmClear = true },
                            onClose = vm::toggleToolsPanel,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).heightIn(max = layersMaxHeight),
                        )
                    } else if (!state.tool.isInspect) {
                        ActiveToolChip(
                            tool = state.tool,
                            argb = when (state.tool) {
                                Tool.PEN -> state.penArgb
                                Tool.PAINT -> state.paintStatus?.argb
                                else -> null
                            },
                            onOpen = vm::toggleToolsPanel,
                            onDone = vm::endDrawing,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                    }
                    if (state.results.isNotEmpty()) {
                        SearchResults(state.results, vm::pickResult, Modifier.align(Alignment.TopCenter).padding(8.dp))
                    }
                    if (state.showLayersPanel) {
                        LayersPanel(
                            scene, state.display, vm::setDisplay, vm::toggleLayer,
                            onAll = { vm.setLayersVisible(scene.layers.map { it.name }, it) },
                            onDefaults = vm::resetLayers,
                            onClose = vm::toggleLayersPanel,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).heightIn(max = layersMaxHeight),
                        )
                    }
                    if (state.visibleSide == Side.BOTTOM) {
                        Text(
                            "BOTTOM VIEW (mirrored)",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            val side = @Composable { mod: Modifier ->
                val sel = state.selected
                val net = state.selectedNet
                when {
                    sel != null -> Surface(tonalElevation = 3.dp, modifier = mod) {
                        DetailsPanel(
                            scene.board, sel, onClose = vm::clearSelection,
                            mark = state.marks[sel.refDes],
                            onStatus = { s -> vm.setMark(sel.refDes, s?.argb) },
                            onNet = vm::selectNet,
                        )
                    }
                    net != null -> Surface(tonalElevation = 3.dp, modifier = mod) {
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
        val mod = Modifier.size(56.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        if (checked) FilledIconButton(onClick = onClick, modifier = mod) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
        else FilledTonalIconButton(onClick = onClick, modifier = mod) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun TopBar(title: String, state: UiState, vm: BoardViewModel, onFit: () -> Unit, onFlip: () -> Unit) {
    val focus = LocalFocusManager.current
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = vm::closeBoard, modifier = Modifier.size(56.dp).testTag("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to start")
            }
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 200.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::search,
                singleLine = true,
                placeholder = { Text("Search R12, 100nF…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.pickResult(null); focus.clearFocus() }),
                modifier = Modifier.weight(1f).testTag("search"),
            )
            BarIcon(toolIcon(Tool.COMPONENTS), toolLabel(Tool.COMPONENTS), { vm.setTool(Tool.COMPONENTS) }, checked = state.tool == Tool.COMPONENTS, tag = "mode.parts")
            BarIcon(toolIcon(Tool.NETS), toolLabel(Tool.NETS), { vm.setTool(Tool.NETS) }, checked = state.tool == Tool.NETS, tag = "mode.nets")
            BarIcon(Icons.Filled.Handyman, "Tools", vm::toggleToolsPanel, checked = state.showToolsPanel, tag = "tools.button")
            BarIcon(Icons.Filled.FitScreen, "Fit to screen", onFit)
            BarIcon(
                Icons.Filled.Flip,
                if (state.visibleSide == Side.TOP) "Showing top — tap for bottom view" else "Showing bottom — tap for top view",
                onFlip, checked = state.visibleSide == Side.BOTTOM, tag = "flip",
            )
            BarIcon(Icons.Filled.Layers, "Layers & display", vm::toggleLayersPanel, checked = state.showLayersPanel)
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
