package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pcbsolderview.BoardViewModel
import com.pcbsolderview.Tool
import com.pcbsolderview.UiState
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.model.Side
import com.pcbsolderview.render.BoardCanvas
import com.pcbsolderview.render.BoardScene
import com.pcbsolderview.render.CanvasTool
import com.pcbsolderview.render.TOUCH_SLOP_PX
import com.pcbsolderview.render.ViewTransform
import kotlin.math.roundToInt

/**
 * View state of one board pane: the transform and the pane size. Hoisted so the top bar (Fit,
 * Flip) and, in v0.1, the split screen can act on it. A new [scene] starts unfitted; the viewport
 * is kept across scenes (the canvas does not report its size again when only the board changes).
 */
@Stable
class BoardViewState internal constructor(val scene: BoardScene, private val viewportState: MutableState<Pair<Int, Int>>) {
    /**
     * Desktop: set when the side panel opens or closes; the next resize then leaves the board where
     * it is on screen (top-left anchored) instead of keeping the centre, even if the panel covers
     * the selected part.
     */
    internal var anchorNextResize = false

    var transform by mutableStateOf<ViewTransform?>(null)

    /** Pane size; when it changes (split divider, layout switch) the view keeps its centre point. */
    var viewport: Pair<Int, Int>
        get() = viewportState.value
        set(v) {
            val (w0, h0) = viewportState.value
            if (v != viewportState.value) {
                if (w0 > 0 && h0 > 0 && !anchorNextResize) transform = transform?.panBy((v.first - w0) / 2.0, (v.second - h0) / 2.0)
                anchorNextResize = false
            }
            viewportState.value = v
        }

    /** Fits the board to the pane; keeps the rotation of [from] when given. */
    fun fit(from: ViewTransform? = transform) {
        val (w, h) = viewport
        if (w == 0 || h == 0) return
        transform = from?.fit(scene.extent, w.toDouble(), h.toDouble())
            ?: ViewTransform.fitted(scene.extent, w.toDouble(), h.toDouble())
    }

    /** Mirrors the view around the pane's vertical centre line (top ↔ bottom view). */
    fun mirror() {
        transform = transform?.mirror(viewport.first / 2.0)
    }
}

@Composable
fun rememberBoardViewState(scene: BoardScene): BoardViewState {
    val viewport = remember { mutableStateOf(0 to 0) }
    return remember(scene) { BoardViewState(scene, viewport) }
}

/**
 * The board and its own overlays: status ring, box selection, S Pen hover label, tool palette,
 * layers panel and the "MIRRORED" label. Screen-wide overlays (search results, settings,
 * highlight chip, messages) belong to the caller, so a schematic pane can sit beside this one.
 */
@Composable
fun BoardPane(
    view: BoardViewState,
    state: UiState,
    vm: BoardViewModel,
    appearance: Appearance,
    /** The palette's "clear annotations" button; the caller asks for confirmation. */
    onClearRequest: () -> Unit,
    /** Tallest a floating panel may grow inside the pane. */
    panelMaxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val scene = view.scene
    var radial by remember(scene) { mutableStateOf<RadialState?>(null) }
    var marquee by remember(scene) { mutableStateOf<Marquee?>(null) }
    var hover by remember(scene) { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val innerPx = with(density) { RADIAL_INNER.toPx() }
    val outerPx = with(density) { RADIAL_OUTER.toPx() }

    fun applyRadial(r: RadialState, index: Int) {
        vm.setMark(r.refDes, RADIAL_ITEMS[index].status?.argb)
        radial = null
    }

    LaunchedEffect(scene, view.viewport) { if (view.transform == null) view.fit(null) }
    LaunchedEffect(state.focusRequest) {
        val c = state.selected ?: return@LaunchedEffect
        val t = view.transform ?: return@LaunchedEffect
        val (w, h) = view.viewport
        val sizeUm = maxOf(c.bounds.width, c.bounds.height, 2_000L).toDouble()
        val zoom = (minOf(w, h) * 0.25 / sizeUm / t.fitScale).coerceIn(2.0, ViewTransform.MAX_ZOOM)
        view.transform = t.centerOn(c.position.x.toDouble(), c.position.y.toDouble(), w.toDouble(), h.toDouble(), zoom)
    }

    // Part under the mouse (or S Pen tip): outlined on the board (desktop) and named in a label.
    val hovered = remember(hover, view.transform, state.visibleSide) {
        val t = view.transform
        val p = hover
        if (p == null || t == null) null else {
            val (bx, by) = t.toBoard(p.x.toDouble(), p.y.toDouble())
            vm.componentAt(bx, by, 8.0 / t.scale)
        }
    }

    Box(modifier) {
        BoardCanvas(
            scene = scene,
            transform = view.transform,
            onTransform = { view.transform = it },
            onViewport = { w, h -> view.viewport = w to h },
            onTap = vm::onBoardTap,
            visibleSide = state.visibleSide,
            // App-wide settings (gear menu) override the per-board view options.
            options = state.display.copy(
                rotationGesture = appearance.rotationGesture,
                penOnlyDrawing = appearance.penOnlyDrawing,
                showFps = appearance.showFps,
                grid = appearance.grid,
                gridAuto = appearance.gridAuto,
                gridStepUm = appearance.gridStepUm,
            ),
            selected = state.selected,
            highlighted = state.highlighted,
            selectedNet = state.selectedNet,
            marks = state.marks,
            strokes = state.strokes,
            tool = when (state.tool) {
                Tool.PEN -> CanvasTool.PEN
                Tool.ERASER -> CanvasTool.ERASER
                else -> CanvasTool.NAVIGATE
            },
            onStylusSeen = vm::onStylusSeen,
            onDoubleTap = { view.fit() },
            hovered = hovered.takeIf { state.tool.isInspect && radial == null && marquee == null },
            onErase = vm::eraseAt,
            onHover = { hover = it },
            penArgb = state.penArgb,
            penWidthPx = state.penWidthPx,
            onStroke = vm::addStroke,
            // Long-press a part: status ring. Slide to an option and lift to apply.
            onLongPress = if (state.tool == Tool.PEN || state.tool == Tool.ERASER) null else { p ->
                val t = view.transform
                val c = t?.let {
                    val (bx, by) = it.toBoard(p.x.toDouble(), p.y.toDouble())
                    vm.componentAt(bx, by, TOUCH_SLOP_PX / it.scale)
                }
                if (c == null) {
                    // Parts mode, empty spot: start a box selection.
                    if (state.tool == Tool.COMPONENTS && t != null) { marquee = Marquee(p, p); true } else false
                } else {
                    val current = state.marks[c.refDes]?.let { m -> RADIAL_ITEMS.indexOfFirst { it.status?.argb == m.argb }.takeIf { it >= 0 } }
                    val (w, h) = view.viewport
                    radial = RadialState(RadialGeometry.clamp(p, w.toFloat(), h.toFloat(), outerPx, 12f), c.refDes, current)
                    true
                }
            },
            onLongPressDrag = { p ->
                if (radial != null) radial = radial?.let { it.copy(hovered = RadialGeometry.itemAt(it.center, p, innerPx)) }
                else marquee = marquee?.copy(end = p)
            },
            onLongPressRelease = { p ->
                radial?.let { r ->
                    val i = RadialGeometry.itemAt(r.center, p, innerPx)
                    // Lifted on an option: apply. Lifted in the middle: keep the ring open for a tap.
                    if (i != null) applyRadial(r, i) else radial = r.copy(hovered = null)
                }
                marquee?.let { m -> val done = m.copy(end = p, done = true); marquee = if (done.isTiny) null else done }
            },
            colors = LocalBoardColors.current,
            modifier = Modifier.fillMaxSize().testTag("board"),
        )
        radial?.let { r -> RadialStatusMenu(r, onPick = { applyRadial(r, it) }, onDismiss = { radial = null }) }
        marquee?.let { m ->
            val t = view.transform
            val inside = remember(m, t, state.visibleSide) {
                if (t == null) emptyList() else MarqueeGeometry.partsInside(scene.board.components, m, t, state.visibleSide)
            }
            MarqueeOverlay(
                m, inside.size,
                onSelect = { vm.selectMany(inside); marquee = null },
                onAnnotate = {
                    t?.let { vm.addStroke(MarqueeGeometry.annotationOutline(m, it), (state.penWidthPx / it.scale).toFloat()) }
                    marquee = null
                },
                onDismiss = { marquee = null },
            )
        }
        // S Pen / mouse hover: designator + value of the part under the pointer, without touching.
        val hoverPos = hover
        if (hoverPos != null && hovered != null) {
            Row(
                Modifier.offset { IntOffset(hoverPos.x.roundToInt() + 24, (hoverPos.y - 72).roundToInt().coerceAtLeast(0)) }
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hovered.refDes, style = Mono, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(hovered.value.orDash(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (state.showToolsPanel) {
            // Floating, draggable palette. Near the top/bottom edge it turns into a horizontal
            // bar, near the left/right edge a column; on release it snaps flush to that edge.
            val (vw, vh) = view.viewport
            val margin = with(density) { 8.dp.toPx() }
            val thick = with(density) { PALETTE_WIDTH.toPx() }
            val gripInset = with(density) { 13.dp.toPx() }
            val optW = with(density) { OPTIONS_WIDTH.toPx() }
            var horizontal by remember { mutableStateOf(vm.toolboxHorizontal) }
            var palSize by remember { mutableStateOf(Offset(thick, thick * 8)) }
            var optH by remember { mutableFloatStateOf(with(density) { 420.dp.toPx() }) }
            var pos by remember { mutableStateOf(vm.toolboxPos?.let { Offset(it.first, it.second) } ?: Offset(margin, margin)) }
            var showOptions by remember { mutableStateOf(false) }
            fun clamp(p: Offset) = Offset(
                p.x.coerceIn(0f, maxOf(0f, vw - palSize.x)),
                p.y.coerceIn(0f, maxOf(0f, vh - palSize.y)),
            )
            // The grip sits at the top of a column and at the left end of a bar.
            fun grip(p: Offset) = if (horizontal) Offset(p.x + gripInset, p.y + palSize.y / 2) else Offset(p.x + palSize.x / 2, p.y + gripInset)
            val at = clamp(pos)
            val hasOptions = state.tool == Tool.PEN || state.tool == Tool.PAINT
            ToolPalette(
                tool = state.tool,
                wellArgb = if (state.tool == Tool.PAINT) state.paintStatus?.argb else state.penArgb,
                canUndo = state.canUndo,
                onTool = { t -> vm.setTool(t); showOptions = t == Tool.PEN || t == Tool.PAINT },
                onWell = {
                    if (!hasOptions) { vm.setTool(Tool.PEN); showOptions = true } else showOptions = !showOptions
                },
                onUndo = vm::undo,
                onClear = onClearRequest,
                onDrag = { d ->
                    pos = clamp(pos + d)
                    val g = grip(pos)
                    horizontal = PaletteDock.horizontalFor(g.x, g.y, vw.toFloat(), vh.toFloat(), horizontal, hysteresis = thick)
                },
                onDragEnd = {
                    val g = grip(pos)
                    val edge = if (horizontal) {
                        if (g.y < vh / 2f) PaletteDock.Edge.TOP else PaletteDock.Edge.BOTTOM
                    } else {
                        if (g.x < vw / 2f) PaletteDock.Edge.LEFT else PaletteDock.Edge.RIGHT
                    }
                    pos = clamp(PaletteDock.snap(pos, palSize, vw.toFloat(), vh.toFloat(), edge, margin))
                    vm.toolboxPos = pos.x to pos.y
                    vm.toolboxHorizontal = horizontal
                },
                horizontal = horizontal,
                modifier = Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .onSizeChanged { palSize = Offset(it.width.toFloat(), it.height.toFloat()) },
            )
            if (showOptions && hasOptions) {
                val (ox, oy) = if (horizontal) {
                    // Bar: options below it, or above when docked at the bottom.
                    val below = at.y + palSize.y + margin + optH <= vh
                    val x = at.x.coerceIn(0f, maxOf(0f, vw - optW))
                    x to (if (below) at.y + palSize.y + margin else (at.y - margin - optH).coerceAtLeast(0f))
                } else {
                    val right = at.x + palSize.x + margin + optW <= vw
                    (if (right) at.x + palSize.x + margin else (at.x - margin - optW).coerceAtLeast(0f)) to at.y
                }
                ToolOptions(
                    tool = state.tool, penArgb = state.penArgb, penWidthPx = state.penWidthPx, paintStatus = state.paintStatus,
                    onPenColor = vm::setPen, onPenWidth = vm::setPenWidth, onPaintStatus = vm::setPaintStatus,
                    onClose = { showOptions = false },
                    modifier = Modifier.offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                        .heightIn(max = panelMaxHeight)
                        .onSizeChanged { optH = it.height.toFloat() },
                )
            }
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
        if (state.showLayersPanel) {
            LayersPanel(
                scene, state.display, vm::setDisplay, vm::toggleLayer,
                onAll = { vm.setLayersVisible(scene.layers.map { it.name }, it) },
                onDefaults = vm::resetLayers,
                onClose = vm::toggleLayersPanel,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).heightIn(max = panelMaxHeight),
            )
        }
        // Warning while the view is mirrored (Mirror button), whichever side is shown.
        if (state.mirrored) {
            Text(
                if (state.visibleSide == Side.BOTTOM) "MIRRORED · bottom side" else "MIRRORED",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("mirrored.label"),
            )
        }
    }
}
