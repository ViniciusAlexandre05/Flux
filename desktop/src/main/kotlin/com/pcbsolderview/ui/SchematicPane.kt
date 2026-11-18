package com.pcbsolderview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pcbsolderview.BoardViewModel
import com.pcbsolderview.Status
import com.pcbsolderview.UiState
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.model.NaturalOrder
import com.pcbsolderview.model.PinRef
import com.pcbsolderview.model.SchNet
import com.pcbsolderview.model.SchSymbol
import com.pcbsolderview.model.Schematic
import com.pcbsolderview.model.SchematicConnectivity
import com.pcbsolderview.render.ComponentMark
import com.pcbsolderview.render.SchematicCanvas
import com.pcbsolderview.render.SchematicFocus
import com.pcbsolderview.render.SchematicNetGeometry
import com.pcbsolderview.render.SchematicScene
import com.pcbsolderview.render.TOUCH_SLOP_PX
import com.pcbsolderview.render.ViewTransform

/**
 * View state of the schematic pane: one transform per sheet (each sheet keeps its own zoom) and
 * the pane size. Hoisted so the top bar's Fit can act on it.
 */
@Stable
class SchematicViewState internal constructor(val scene: SchematicScene, private val viewportState: MutableState<Pair<Int, Int>>) {
    private val transforms = mutableStateOf<Map<Int, ViewTransform>>(emptyMap())

    /** Desktop: see BoardViewState.anchorNextResize (side panel opening or closing). */
    internal var anchorNextResize = false

    /** Pane size; when it changes every sheet keeps its centre point. */
    var viewport: Pair<Int, Int>
        get() = viewportState.value
        set(v) {
            val (w0, h0) = viewportState.value
            if (v != viewportState.value) {
                if (w0 > 0 && h0 > 0 && !anchorNextResize) {
                    transforms.value = transforms.value.mapValues { it.value.panBy((v.first - w0) / 2.0, (v.second - h0) / 2.0) }
                }
                anchorNextResize = false
            }
            viewportState.value = v
        }

    fun transform(sheet: Int): ViewTransform? = transforms.value[sheet]

    fun setTransform(sheet: Int, t: ViewTransform) { transforms.value = transforms.value + (sheet to t) }

    /** Fits [sheet] to the pane; keeps the current rotation. */
    fun fit(sheet: Int) {
        val (w, h) = viewport
        val extent = scene.sheets.getOrNull(sheet)?.extent ?: return
        if (w == 0 || h == 0) return
        val t = transform(sheet)?.fit(extent, w.toDouble(), h.toDouble()) ?: ViewTransform.fitted(extent, w.toDouble(), h.toDouble())
        setTransform(sheet, t)
    }

    /** Centres [sheet] on a sheet point, zoomed in at least [minZoom]× of fit. */
    fun centerOn(sheet: Int, x: Double, y: Double, minZoom: Double = 2.5) {
        val (w, h) = viewport
        if (w == 0 || h == 0) return
        if (transform(sheet) == null) fit(sheet)
        val t = transform(sheet) ?: return
        setTransform(sheet, t.centerOn(x, y, w.toDouble(), h.toDouble(), maxOf(t.zoomFactor, minZoom)))
    }
}

@Composable
fun rememberSchematicViewState(scene: SchematicScene): SchematicViewState {
    val viewport = remember { mutableStateOf(0 to 0) }
    return remember(scene) { SchematicViewState(scene, viewport) }
}

/**
 * The schematic with sheet tabs. Taps select parts and nets (shared with the board); a long
 * press on a part opens the status ring. Parts that are not on the board are drawn faded.
 */
@Composable
fun SchematicPane(
    view: SchematicViewState,
    state: UiState,
    vm: BoardViewModel,
    appearance: Appearance,
    modifier: Modifier = Modifier,
) {
    val scene = view.scene
    val sheetIndex = state.sheetIndex.coerceIn(0, (scene.sheets.size - 1).coerceAtLeast(0))
    val sheet = scene.sheets.getOrNull(sheetIndex) ?: return
    val board = state.scene?.board
    var radial by remember(scene) { mutableStateOf<RadialState?>(null) }
    val density = LocalDensity.current
    val innerPx = with(density) { RADIAL_INNER.toPx() }
    val outerPx = with(density) { RADIAL_OUTER.toPx() }

    LaunchedEffect(scene, sheetIndex, view.viewport) { if (view.transform(sheetIndex) == null) view.fit(sheetIndex) }
    // Follow selection: open the sheet of the selected part / net and pan to it.
    LaunchedEffect(state.schFocusRequest) {
        if (state.schFocusRequest == 0) return@LaunchedEffect
        val target = focusTarget(state) ?: return@LaunchedEffect
        if (target.first != state.sheetIndex) vm.setSheet(target.first)
        view.centerOn(target.first, target.second.toDouble(), target.third.toDouble())
    }

    val notOnBoard = remember(scene, board) {
        if (state.hasBoard && board != null) scene.schematic.symbols.map { it.refDes }.filter { board.component(it) == null }.map { it.uppercase() }.toSet() else emptySet()
    }
    val focus = SchematicFocus(
        selected = setOfNotNull(state.selectedRef?.uppercase()),
        highlighted = remember(state.highlighted) { state.highlighted.map { it.refDes.uppercase() }.toSet() },
        faded = notOnBoard,
        marks = if (state.display.showAnnotations) state.marks else emptyMap(),
        net = remember(state.schNet, sheetIndex) {
            state.schNet?.let { n -> SchematicNetGeometry(n.wires[sheetIndex].orEmpty(), n.points[sheetIndex].orEmpty()) }
        },
    )

    Column(modifier) {
        if (scene.sheets.size > 1) SheetTabs(scene.schematic, sheetIndex, state.schNet, vm::setSheet)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SchematicCanvas(
                sheet = sheet,
                transform = view.transform(sheetIndex),
                onTransform = { view.setTransform(sheetIndex, it) },
                onViewport = { w, h -> view.viewport = w to h },
                onTap = { x, y, tol, _ -> vm.onSchematicTap(sheetIndex, x, y, tol) },
                colors = LocalSchematicColors.current,
                focus = focus,
                rotationGesture = appearance.rotationGesture,
                onStylusSeen = vm::onStylusSeen,
                onDoubleTap = { view.fit(sheetIndex) },
                onLongPress = { p ->
                    val t = view.transform(sheetIndex) ?: return@SchematicCanvas false
                    val (x, y) = t.toBoard(p.x.toDouble(), p.y.toDouble())
                    val sym = vm.symbolAt(sheetIndex, x, y, TOUCH_SLOP_PX / t.scale) ?: return@SchematicCanvas false
                    val current = state.marks[sym.refDes]?.let { m -> RADIAL_ITEMS.indexOfFirst { it.status?.argb == m.argb }.takeIf { it >= 0 } }
                    val (w, h) = view.viewport
                    radial = RadialState(RadialGeometry.clamp(p, w.toFloat(), h.toFloat(), outerPx, 12f), sym.refDes, current)
                    true
                },
                onLongPressDrag = { p -> radial = radial?.let { it.copy(hovered = RadialGeometry.itemAt(it.center, p, innerPx)) } },
                onLongPressRelease = { p ->
                    radial?.let { r ->
                        val i = RadialGeometry.itemAt(r.center, p, innerPx)
                        if (i != null) { vm.setMark(r.refDes, RADIAL_ITEMS[i].status?.argb); radial = null } else radial = r.copy(hovered = null)
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("schematic"),
            )
            radial?.let { r ->
                RadialStatusMenu(r, onPick = { vm.setMark(r.refDes, RADIAL_ITEMS[it].status?.argb); radial = null }, onDismiss = { radial = null })
            }
        }
    }
}

/** Sheet, x, y of what the schematic should show for the current selection. */
private fun focusTarget(state: UiState): Triple<Int, Long, Long>? {
    val sch = state.schematic ?: return null
    state.selectedRef?.let { ref ->
        val sym = sch.symbolsOf(ref).firstOrNull() ?: return null
        val c = sym.bounds.center
        return Triple(sch.sheetOf(sym).coerceAtLeast(0), c.x, c.y)
    }
    state.schNet?.let { n ->
        val sheet = if (state.sheetIndex in n.wires || state.sheetIndex in n.points) state.sheetIndex else (n.points.keys + n.wires.keys).minOrNull() ?: return null
        val p = n.points[sheet]?.firstOrNull() ?: n.wires[sheet]?.firstOrNull()?.firstOrNull() ?: return null
        return Triple(sheet, p.x, p.y)
    }
    return null
}

@Composable
private fun SheetTabs(schematic: Schematic, current: Int, net: SchNet?, onPick: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            schematic.sheets.forEachIndexed { i, s ->
                val on = i == current
                // A dot marks sheets the focused net runs through.
                val netHere = net != null && (i in net.wires || i in net.points)
                Row(
                    Modifier.heightIn(min = 40.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraLarge)
                        .clickable { onPick(i) }.padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("sheet.$i"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (netHere) {
                        Box(Modifier.size(8.dp).background(Color(LocalSchematicColors.current.net), RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(s.name, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- side panels

/** Details of a schematic part that is not on the board. */
@Composable
fun SymbolPanel(
    schematic: Schematic,
    connectivity: SchematicConnectivity?,
    refDes: String,
    mark: ComponentMark?,
    onStatus: (Status?) -> Unit,
    onNet: (SchNet) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val symbols = schematic.symbolsOf(refDes)
    val first = symbols.firstOrNull()
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()).testTag("symbol")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(refDes, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close details") }
        }
        Text(first?.comment.orDash(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Not on the board",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(10.dp))
        schematic.duplicateWarning(refDes)?.let { PanelWarning(it); Spacer(Modifier.height(6.dp)) }
        StatusRow(mark, onStatus)
        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
        Field("Description", first?.description)
        Field("Footprint", first?.footprint)
        Field("Library part", first?.libRef)
        val partNumber = first?.parameters?.entries?.firstOrNull { it.key.contains("part number", true) }?.value
        Field("Part number", partNumber)
        Field("Manufacturer", first?.parameters?.entries?.firstOrNull { it.key.equals("Manufacturer", true) }?.value)
        if (symbols.size > 1) Field("Parts", symbols.joinToString(", ") { it.label })
        PinNets(symbols, refDes, connectivity, onNet)
    }
}

/** A schematic net with no counterpart on the board (or picked on the schematic). */
@Composable
fun SchNetPanel(net: SchNet, boardNets: List<String>, onComponent: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val byPart = net.pins.groupBy { it.refDes }.mapValues { (_, ps) -> ps.map { it.pin }.sortedWith(NaturalOrder) }.toSortedMap(NaturalOrder)
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()).testTag("schnet")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Schematic net", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(net.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close net") }
        }
        Text(if (boardNets.isEmpty()) "Not on the board" else "On the board: " + boardNets.joinToString(", "))
        Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
        Text("Connected pins", style = MaterialTheme.typography.titleMedium)
        if (byPart.isEmpty()) Text(DASH)
        byPart.forEach { (ref, pins) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onComponent(ref) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ref, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                Text("pin " + pins.joinToString(", "), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PinNets(symbols: List<SchSymbol>, refDes: String, connectivity: SchematicConnectivity?, onNet: (SchNet) -> Unit) {
    val pins = symbols.flatMap { it.pins }.distinctBy { it.designator }.sortedWith(compareBy(NaturalOrder) { it.designator })
    if (pins.isEmpty()) return
    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
    Text("Pins", style = MaterialTheme.typography.titleMedium)
    pins.forEach { p ->
        val net = connectivity?.netOf(PinRef(refDes, p.designator))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).then(if (net != null) Modifier.clickable { onNet(net) } else Modifier).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.designator, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
            Text(p.name.orDash(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(net?.name.orDash(), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.orDash())
    }
}

@Composable
private fun StatusRow(mark: ComponentMark?, onStatus: (Status?) -> Unit) {
    Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        val current = mark?.let { Status.of(it.argb) }
        Status.entries.forEach { s ->
            val selected = current == s
            Box(
                Modifier.size(width = 64.dp, height = 44.dp)
                    .background(Color(s.argb).copy(alpha = if (selected) 1f else 0.35f), RoundedCornerShape(8.dp))
                    .clickable { onStatus(if (selected) null else s) },
                contentAlignment = Alignment.Center,
            ) { Text(s.label, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
