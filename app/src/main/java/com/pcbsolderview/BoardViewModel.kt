package com.pcbsolderview

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcbsolderview.data.AppDatabase
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.AppearancePrefs
import com.pcbsolderview.data.BoardStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.pcbsolderview.data.MarkEntity
import com.pcbsolderview.data.RecentBoardEntity
import com.pcbsolderview.render.thumbnail
import com.pcbsolderview.ui.boardColorsFor
import com.pcbsolderview.data.StrokeEntity
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.MountFilter
import com.pcbsolderview.model.NaturalOrder
import com.pcbsolderview.model.SchNet
import com.pcbsolderview.model.SchSymbol
import com.pcbsolderview.model.Schematic
import com.pcbsolderview.model.SchematicConnectivity
import com.pcbsolderview.model.filterByMount
import com.pcbsolderview.model.identicalTo
import com.pcbsolderview.model.matchesQuery
import com.pcbsolderview.model.search
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.InputFile
import com.pcbsolderview.parser.xml.XmlParserFactory
import com.pcbsolderview.render.BoardScene
import com.pcbsolderview.render.ComponentMark
import com.pcbsolderview.render.CopperIndex
import com.pcbsolderview.render.DisplayOptions
import com.pcbsolderview.render.InkStroke
import com.pcbsolderview.render.SchematicScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which highlighted parts a batch status change applies to: parts without a status, parts that
 * already have this status, and [origin] (the part acted on). Others keep their status.
 * Clearing (argb = null) applies to all.
 */
object BatchStatus {
    fun split(refs: List<String>, current: Map<String, Int>, argb: Int?, origin: String?): Pair<List<String>, List<String>> =
        refs.partition { r -> argb == null || r == origin || current[r] == null || current[r] == argb }
}

enum class Tool {
    /** Tap selects a part (designator, value, pins). */
    COMPONENTS,
    /** Tap selects the net of a track, via, pad or copper pour. */
    NETS,
    PEN, ERASER, PAINT;

    val isInspect: Boolean get() = this == COMPONENTS || this == NETS
}

/** Predefined component statuses for the paint tool. */
/**
 * Component statuses. Colours are spread around the hue wheel (green, red, amber, blue) and checked
 * by a test to stay clearly apart, also when painted over the board; each also has a symbol so the
 * status reads without relying on colour alone. [legacyArgb] is the colour used before v0.0.2;
 * stored marks are converted by the database migration.
 */
enum class Status(val label: String, val argb: Int, val glyph: String, val legacyArgb: Int) {
    OK("OK", 0xFF22C55E.toInt(), "✓", 0xFF2ECC40.toInt()),
    PROBLEM("Problem", 0xFFEF2D3D.toInt(), "!", 0xFFFF4136.toInt()),
    CHECK("Check", 0xFFFF9F0A.toInt(), "?", 0xFFFFDC00.toInt()),
    INFO("Info", 0xFF3B82F6.toInt(), "i", 0xFF0074D9.toInt());

    companion object {
        fun of(argb: Int): Status? = entries.firstOrNull { it.argb == argb }
    }
}

/** Pen colours: the status colours plus a few neutral ones. */
val PEN_COLORS: List<Int> = listOf(
    0xFFFFFFFF.toInt(), 0xFF00E5FF.toInt(), 0xFFFF2BD6.toInt(), 0xFFFF851B.toInt(),
) + Status.entries.map { it.argb }

val PEN_WIDTHS: List<Float> = listOf(3f, 6f, 12f)

/** A recent board with its annotation counts, for the start screen. */
data class RecentItem(
    val board: RecentBoardEntity,
    val ok: Int = 0,
    val problems: Int = 0,
    val check: Int = 0,
    val info: Int = 0,
    val drawings: Int = 0,
    val thumbnail: String? = null,
)

data class UiState(
    val recents: List<RecentItem> = emptyList(),
    val scene: BoardScene? = null,
    /** Schematic opened with the board; null when none was chosen. */
    val schematic: Schematic? = null,
    val schematicScene: SchematicScene? = null,
    /** PDF shown view-only when no .SchDoc was opened (Altium Smart PDF). */
    val schematicPdf: ByteArray? = null,
    val connectivity: SchematicConnectivity? = null,
    /** Board net → the schematic net that connects the same pins. */
    val boardToSch: Map<String, SchNet> = emptyMap(),
    /** Board, schematic or both side by side. */
    val viewLayout: ViewLayout = ViewLayout.BOARD,
    /** Schematic sheet shown. */
    val sheetIndex: Int = 0,
    /** Designator of a schematic part that is not on the board, when one is selected. */
    val selectedSymbol: String? = null,
    /** Schematic net in focus (picked on the schematic, or the match of [selectedNet]). */
    val schNet: SchNet? = null,
    /** Incremented to ask the schematic to show the selection (other sheet, pan). */
    val schFocusRequest: Int = 0,
    /** Name to show for the open board (user-chosen), instead of the name from the file. */
    val boardTitle: String? = null,
    val loading: String? = null,
    /** 0..1 while parsing, null when unknown. */
    val progress: Float? = null,
    val error: String? = null,
    val selected: Component? = null,
    /** Net picked by tapping a track/via/pour or a pad row. */
    val selectedNet: String? = null,
    val query: String = "",
    val results: List<Component> = emptyList(),
    /** Components highlighted on the board (identical parts, or search results by value). */
    val highlighted: List<Component> = emptyList(),
    /** What [highlighted] shows, e.g. "8 × 10kΩ · CR1005-0402"; null when nothing is highlighted. */
    val highlightLabel: String? = null,
    /** The whole highlighted group before the chip's filter and search narrow it. */
    val highlightBase: List<Component> = emptyList(),
    /** All / SMD / PTH switch on the highlight chip. */
    val highlightFilter: MountFilter = MountFilter.ALL,
    /** Search typed in the highlight chip, applied only inside [highlightBase]. */
    val highlightQuery: String = "",
    /** True when the group was made with a long press ("all"): batch status overwrites existing statuses. */
    val highlightOverride: Boolean = false,
    /** What made the group, e.g. "identical:R205" for the identical parts of R205; null otherwise. */
    val highlightKey: String? = null,
    /** Incremented to ask the view to zoom to [selected]. */
    val focusRequest: Int = 0,
    val visibleSide: Side = Side.TOP,
    /** The view is mirrored left-right (Mirror button), on either side; independent of [visibleSide]. */
    val mirrored: Boolean = false,
    val display: DisplayOptions = DisplayOptions(),
    val showLayersPanel: Boolean = false,
    val showSettingsPanel: Boolean = false,
    /** Floating tool palette visible (toggled by the Tools button). */
    val showToolsPanel: Boolean = true,
    val tool: Tool = Tool.COMPONENTS,
    /** Inspect tool to return to when a drawing tool is dismissed. */
    val lastInspect: Tool = Tool.COMPONENTS,
    val penArgb: Int = PEN_COLORS.first(),
    val penWidthPx: Float = PEN_WIDTHS[1],
    val paintStatus: Status? = Status.OK,
    val marks: Map<String, ComponentMark> = emptyMap(),
    val strokes: List<InkStroke> = emptyList(),
    val canUndo: Boolean = false,
    /** Short confirmation shown at the bottom of the board, e.g. "OK on 6 parts". */
    val message: String? = null,
) {
    /** Designator selected in either view. */
    val selectedRef: String? get() = selected?.refDes ?: selectedSymbol

    /** There is something to show in the schematic pane (sheets or a PDF). */
    val hasSchematicView: Boolean get() = schematic != null || schematicPdf != null

    /** The board has parts (false when only a schematic was opened). */
    val hasBoard: Boolean get() = scene?.board?.let { it.components.isNotEmpty() || it.outline.isNotEmpty() } == true
}

enum class ViewLayout { BOARD, SPLIT, SCHEMATIC }

class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val importer = BoardImporter(XmlParserFactory { Xml.newPullParser() })
    private val db = AppDatabase.get(app)
    private val dao = db.annotations()
    private val recentDao = db.recents()
    private val store = BoardStore(app)
    private val prefs = AppearancePrefs(app)
    private var loadJob: Job? = null

    /** Theme, accent and board background; persisted. */
    val appearance: StateFlow<Appearance> =
        // Start from the synchronous snapshot so the very first frame already has the right theme.
        prefs.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, AppearancePrefs.snapshot(app))

    fun setAppearance(a: Appearance) {
        viewModelScope.launch { prefs.save(a) }
    }

    init {
        viewModelScope.launch {
            combine(recentDao.recents(), recentDao.markCounts(), recentDao.strokeCounts()) { recents, marks, strokes ->
                val m = marks.groupBy { it.boardKey }
                val s = strokes.associate { it.boardKey to it.n }
                recents.map { r ->
                    val counts = m[r.boardKey].orEmpty().associate { it.argb to it.n }
                    RecentItem(
                        board = r,
                        ok = counts[Status.OK.argb] ?: 0,
                        problems = counts[Status.PROBLEM.argb] ?: 0,
                        check = counts[Status.CHECK.argb] ?: 0,
                        info = counts[Status.INFO.argb] ?: 0,
                        drawings = s[r.boardKey] ?: 0,
                        thumbnail = store.thumbnail(r.dir)?.absolutePath,
                    )
                }
            }.collect { list -> _state.update { it.copy(recents = list) } }
        }
    }

    /** Where the files being opened come from. */
    private sealed interface Origin {
        data object New : Origin
        data object Demo : Origin
        data class Recent(val key: String, val title: String?) : Origin
    }
    private var annotationsJob: Job? = null
    private var boardKey: String? = null

    private sealed interface Undo {
        data class StrokeAdded(val id: Long) : Undo
        data class StrokeRemoved(val stroke: StrokeEntity) : Undo
        data class MarkChanged(val refDes: String, val before: MarkEntity?) : Undo
        /** One batch status change; undone in one step. */
        data class MarksChanged(val before: List<Pair<String, MarkEntity?>>) : Undo
        data class Cleared(val marks: List<MarkEntity>, val strokes: List<StrokeEntity>) : Undo
    }
    private val undo = ArrayDeque<Undo>()

    // ------------------------------------------------------------------ loading

    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        load("Opening…", Origin.New) {
            val resolver = getApplication<Application>().contentResolver
            uris.map { uri ->
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw BoardParseException("Could not read $uri")
                InputFile(displayName(uri), bytes)
            }
        }
    }

    fun openDemo(folder: String) = load("Opening demo…", Origin.Demo) {
        val assets = getApplication<Application>().assets
        assets.list(folder).orEmpty().map { name ->
            InputFile(name, assets.open("$folder/$name").use { it.readBytes() })
        }
    }

    /** Reopens a board from its saved copy; its drawings and status paint come back with it. */
    fun openRecent(r: RecentBoardEntity) = load("Opening ${r.displayName}…", Origin.Recent(r.boardKey, r.customName)) {
        store.load(r.dir).ifEmpty { throw BoardParseException("The saved copy of this board is missing. Open the files again.") }
    }

    /** Gives a recent board a display name (blank = back to the name from the file). */
    fun renameRecent(r: RecentBoardEntity, name: String) {
        viewModelScope.launch { recentDao.rename(r.boardKey, name.trim().takeIf { it.isNotEmpty() }) }
    }

    /** Removes a board from the list and deletes its saved copy. Annotations stay in the database. */
    fun removeRecent(r: RecentBoardEntity) {
        viewModelScope.launch {
            recentDao.delete(r.boardKey)
            withContext(Dispatchers.IO) { store.delete(r.dir) }
        }
    }

    private fun load(message: String, origin: Origin, files: suspend () -> List<InputFile>) {
        loadJob?.cancel()
        _state.update { it.copy(loading = message, progress = null, error = null) }
        loadJob = viewModelScope.launch {
            // Skipped-file notices (e.g. a .PcbDoc next to the IPC-2581); not repeated when reopening a recent.
            val notices = mutableListOf<String>()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val inputs = files()
                    val imported = importer.import(
                        inputs,
                        onProgress = { p -> _state.update { it.copy(progress = p) } },
                        onNotice = { n -> notices += n },
                    )
                    _state.update { it.copy(loading = "Preparing view…", progress = null) }
                    val scene = BoardScene(imported.board)
                    val sch = imported.schematic?.let { s ->
                        val c = SchematicConnectivity(s)
                        Loaded(s, SchematicScene(s), c, c.matchBoard(imported.board))
                    }
                    remember(origin, keyOf(imported.board), scene, inputs)
                    Triple(scene, sch, imported.schematicPdf?.bytes)
                }
            }
            result.fold(
                onSuccess = { (scene, sch, pdf) ->
                    undo.clear()
                    val hasBoard = scene.board.components.isNotEmpty() || scene.board.outline.isNotEmpty()
                    _state.value = UiState(
                        recents = _state.value.recents,
                        scene = scene,
                        schematic = sch?.schematic,
                        schematicScene = sch?.scene,
                        connectivity = sch?.connectivity,
                        boardToSch = sch?.boardToSch.orEmpty(),
                        schematicPdf = pdf,
                        viewLayout = when {
                            sch == null && pdf == null -> ViewLayout.BOARD
                            !hasBoard -> ViewLayout.SCHEMATIC
                            else -> ViewLayout.SPLIT
                        },
                        boardTitle = (origin as? Origin.Recent)?.title?.takeIf { it.isNotBlank() },
                        display = DisplayOptions(hiddenLayers = scene.defaultHiddenLayers),
                        message = notices.takeIf { origin !is Origin.Recent && it.isNotEmpty() }?.joinToString("\n"),
                    )
                    watchAnnotations(scene.board)
                },
                onFailure = { e ->
                    val msg = (e as? BoardParseException)?.message
                        ?: "Could not open the file (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})."
                    _state.update { it.copy(loading = null, progress = null, error = msg) }
                },
            )
        }
    }

    private class Loaded(val schematic: Schematic, val scene: SchematicScene, val connectivity: SchematicConnectivity, val boardToSch: Map<String, SchNet>)

    /** Adds/updates the recent-boards entry. Demos are not listed. */
    private suspend fun remember(origin: Origin, key: String, scene: BoardScene, inputs: List<InputFile>) {
        val now = System.currentTimeMillis()
        when (origin) {
            Origin.Demo -> Unit
            is Origin.Recent -> {
                recentDao.touch(origin.key, now)
                // Thumbnail predates this feature or was lost: rebuild it.
                val dir = store.dirFor(origin.key)
                if (store.thumbnail(dir.path) == null) runCatching { store.save(origin.key, inputs, scene.thumbnail(colors = boardColorsFor(appearance.value))) }
            }
            Origin.New -> {
                val thumb = runCatching { scene.thumbnail(colors = boardColorsFor(appearance.value)) }.getOrNull()
                val dir = withContext(Dispatchers.IO) { store.save(key, inputs, thumb) }
                val b = scene.board
                // Re-opening the same board from files keeps the name the user gave it.
                val customName = recentDao.get(key)?.customName
                recentDao.upsert(RecentBoardEntity(key, b.name, b.source.name, b.components.size, now, dir.absolutePath, customName))
                if (!customName.isNullOrBlank()) _state.update { it.copy(boardTitle = customName) }
            }
        }
    }

    /** Annotations are keyed by board identity, so they survive re-exports of the same board. */
    private fun keyOf(b: Board) = "${b.source}:${b.name}:${b.components.size}"

    private fun watchAnnotations(board: Board) {
        val key = keyOf(board)
        boardKey = key
        annotationsJob?.cancel()
        annotationsJob = viewModelScope.launch {
            combine(dao.marks(key), dao.strokes(key)) { m, s -> m to s }.collect { (m, s) ->
                _state.update { st ->
                    st.copy(marks = m.associate { it.refDes to it.toMark() }, strokes = s.map { it.toStroke() }, canUndo = undo.isNotEmpty())
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Leaves the board and goes back to the start screen. */
    fun closeBoard() {
        loadJob?.cancel(); annotationsJob?.cancel()
        eraserFromButton = false
        boardKey = null; undo.clear()
        _state.value = UiState(recents = _state.value.recents)
    }

    // ------------------------------------------------------------------ selection & search

    fun select(c: Component?) = _state.update {
        withSchematicFollow(it.copy(selected = c, selectedSymbol = null, selectedNet = if (c != null) null else it.selectedNet, schNet = if (c != null) null else it.schNet))
    }

    /** Board net picked (track, pad row…); the schematic shows the net that joins the same pins. */
    fun selectNet(net: String?) = _state.update { withSchematicFollow(it.copy(selectedNet = net, schNet = net?.let { n -> it.boardToSch[n] })) }

    fun clearSelection() = _state.update { it.copy(selected = null, selectedNet = null, selectedSymbol = null, schNet = null) }

    /** With "follow selection" on, asks the schematic to show what was just selected on the board. */
    private fun withSchematicFollow(s: UiState): UiState =
        if (s.schematic != null && appearance.value.followSelection) s.copy(schFocusRequest = s.schFocusRequest + 1) else s

    // ------------------------------------------------------------------ schematic

    fun setViewLayout(l: ViewLayout) = _state.update { s ->
        if (!s.hasSchematicView || (l != ViewLayout.SCHEMATIC && !s.hasBoard)) s else s.copy(viewLayout = l)
    }

    fun setSheet(i: Int) = _state.update { s -> s.copy(sheetIndex = i.coerceIn(0, (s.schematic?.sheets?.size ?: 1) - 1)) }

    /** Schematic net picked in a panel (pin list); the board shows its matching net, if any. */
    fun selectSchNet(n: SchNet?) = _state.update {
        withSchematicFollow(it.copy(schNet = n, selectedNet = n?.let { net -> boardNetsOf(net).firstOrNull() }, selected = null, selectedSymbol = null))
    }

    /** Part picked by designator (net pin list): the board part when there is one, else the schematic part. */
    fun selectRef(ref: String) {
        val c = _state.value.scene?.board?.component(ref)
        if (c != null) select(c)
        else _state.update { withSchematicFollow(it.copy(selected = null, selectedSymbol = ref, selectedNet = null, schNet = null)) }
    }

    /** Board net(s) that match a schematic net. */
    fun boardNetsOf(n: SchNet): List<String> = _state.value.boardToSch.filterValues { it.id == n.id }.keys.sortedWith(NaturalOrder)

    /** Schematic part under a sheet point, for the long-press status ring. */
    fun symbolAt(sheet: Int, x: Double, y: Double, toleranceUm: Double): SchSymbol? =
        _state.value.schematicScene?.sheets?.getOrNull(sheet)?.symbolAt(x, y, toleranceUm)

    /** Tap on a schematic sheet (µm). Parts and nets select in both views; paint works on symbols too. */
    fun onSchematicTap(sheet: Int, x: Double, y: Double, toleranceUm: Double) {
        val s = _state.value
        val sch = s.schematicScene?.sheets?.getOrNull(sheet) ?: return
        val board = s.scene?.board
        val follow = appearance.value.followSelection
        when (if (s.tool.isInspect || s.tool == Tool.PAINT) s.tool else s.lastInspect) {
            Tool.NETS -> {
                val net = s.connectivity?.netAt(sheet, x, y, toleranceUm)
                val next = if (net != null && net.id == s.schNet?.id) null else net
                val boardNet = next?.let { boardNetsOf(it).firstOrNull() }
                _state.update { it.copy(schNet = next, selectedNet = boardNet, selected = null, selectedSymbol = null) }
            }
            Tool.PAINT -> {
                val sym = sch.symbolAt(x, y, toleranceUm) ?: return
                val current = s.marks[sym.refDes]
                setMarks(listOf(sym.refDes), s.paintStatus?.argb?.takeIf { it != current?.argb })
            }
            else -> {
                val sym = sch.symbolAt(x, y, toleranceUm)
                if (sym == null || sym.refDes == s.selectedRef) { _state.update { it.copy(selected = null, selectedSymbol = null) }; return }
                val c = board?.component(sym.refDes)
                _state.update {
                    it.copy(
                        selected = c, selectedSymbol = if (c == null) sym.refDes else null, selectedNet = null, schNet = null,
                        // Show the part on the board too (only when it is there).
                        focusRequest = if (c != null && follow && it.viewLayout == ViewLayout.SPLIT) it.focusRequest + 1 else it.focusRequest,
                    )
                }
            }
        }
    }

    fun search(query: String) {
        val scene = _state.value.scene ?: return
        val r = scene.board.search(query)
        _state.update { it.copy(query = query, results = r.matches) }
    }

    /** User picked a search result (or pressed Enter). */
    fun pickResult(c: Component?) = _state.update { s ->
        val focus = c ?: s.results.firstOrNull()
        // Highlight every component sharing the picked one's value, for batch soldering.
        val sameValue = focus?.value?.let { v -> s.scene?.board?.components?.filter { it.value == v } }.orEmpty()
        val hl = if (sameValue.size > 1) sameValue else emptyList()
        withHighlight(
            withSchematicFollow(s.copy(selected = focus, selectedSymbol = null, selectedNet = null, schNet = null, focusRequest = s.focusRequest + 1)),
            hl, if (hl.isEmpty()) null else "${hl.size} × ${focus?.value}",
        )
    }

    /** "Select all" in the search results: highlights every result so a status can be set on all of them. */
    fun selectAllResults() = _state.update { s ->
        if (s.results.isEmpty()) s
        else withHighlight(
            s.copy(selected = null, selectedSymbol = null, selectedNet = null, schNet = null, results = emptyList()),
            s.results, "${s.results.size} result" + (if (s.results.size == 1) "" else "s") + " for \"${s.query.trim()}\"",
        )
    }

    fun clearSearch() = _state.update { withHighlight(it.copy(query = "", results = emptyList()), emptyList(), null) }

    /** Starts a highlight group; the chip's filter and search start fresh. */
    private fun withHighlight(s: UiState, base: List<Component>, label: String?, override: Boolean = false, key: String? = null): UiState =
        s.copy(
            highlightBase = base, highlightLabel = label, highlightOverride = override, highlightKey = key,
            highlightFilter = MountFilter.ALL, highlightQuery = "", highlighted = base,
        )

    /** Recomputes the visible highlight from the group, the All/SMD/PTH switch and the search. */
    private fun refilter(s: UiState): UiState {
        val board = s.scene?.board ?: return s
        val shown = board.filterByMount(s.highlightBase, s.highlightFilter).filter { it.matchesQuery(s.highlightQuery) }
        return s.copy(highlighted = shown)
    }

    fun setHighlightFilter(f: MountFilter) = _state.update { refilter(it.copy(highlightFilter = f)) }

    fun setHighlightQuery(q: String) = _state.update { refilter(it.copy(highlightQuery = q)) }

    /**
     * Identical parts of [c]. A tap ([all] = false) highlights only those without a status yet,
     * the ones still to do. A long press ([all] = true) highlights every one, and a status set
     * then applies to all of them, overwriting existing statuses. Repeating the same gesture
     * turns the highlight off.
     */
    fun toggleIdentical(c: Component, all: Boolean = false) = _state.update { s ->
        val board = s.scene?.board ?: return@update s
        val identical = board.identicalTo(c)
        val group = if (all) identical else identical.filter { s.marks[it.refDes] == null }
        val same = s.highlightKey == identicalKey(identical) && s.highlightOverride == all
        when {
            same -> withHighlight(s, emptyList(), null)
            group.isEmpty() -> s.copy(message = "All ${identical.size} already have a status · hold to highlight all")
            else -> {
                val what = listOfNotNull(c.value ?: c.description, c.footprint).joinToString(" · ").ifEmpty { c.refDes }
                val label = if (all) "all ${group.size} × $what" else "${group.size} to do × $what"
                withHighlight(s, group, label, override = all, key = identicalKey(identical))
            }
        }
    }

    fun clearHighlight() = _state.update { withHighlight(it, emptyList(), null) }

    /** Key of the "identical parts" group of a part (the group is the same for every member). */
    fun identicalKey(identical: List<Component>): String? = identical.firstOrNull()?.let { "identical:${it.refDes}" }

    /** Box selection: highlights these parts so a status can be set on all of them at once. */
    fun selectMany(parts: List<Component>) = _state.update {
        if (parts.isEmpty()) it.copy(message = "No parts inside the box")
        else withHighlight(
            it.copy(selected = null, selectedNet = null),
            parts, "${parts.size} part" + (if (parts.size == 1) "" else "s") + " selected",
        )
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ view & display

    fun flipSide() = _state.update { it.copy(visibleSide = it.visibleSide.opposite) }

    /** Mirror the view or not (the view state applies it; a new board starts unmirrored). */
    fun setMirrored(on: Boolean) = _state.update { it.copy(mirrored = on) }

    fun setDisplay(d: DisplayOptions) = _state.update { it.copy(display = d) }

    fun toggleLayer(name: String) = _state.update { s ->
        val h = s.display.hiddenLayers
        s.copy(display = s.display.copy(hiddenLayers = if (name in h) h - name else h + name))
    }

    fun setLayersVisible(names: Collection<String>, visible: Boolean) = _state.update { s ->
        val h = s.display.hiddenLayers
        s.copy(display = s.display.copy(hiddenLayers = if (visible) h - names.toSet() else h + names))
    }

    fun resetLayers() = _state.update { s -> s.copy(display = s.display.copy(hiddenLayers = s.scene?.defaultHiddenLayers.orEmpty())) }

    // Layers and Settings share the right side: opening one closes the other.
    fun toggleLayersPanel() = _state.update { it.copy(showLayersPanel = !it.showLayersPanel, showSettingsPanel = false) }

    fun toggleSettingsPanel() = _state.update { it.copy(showSettingsPanel = !it.showSettingsPanel, showLayersPanel = false) }

    fun toggleToolsPanel() = _state.update { it.copy(showToolsPanel = !it.showToolsPanel) }

    /** Where the user dragged the tool palette (px in the board area); kept across boards. */
    var toolboxPos: Pair<Float, Float>? = null

    /** Palette lies horizontally (docked at the top or bottom edge). */
    var toolboxHorizontal: Boolean = false

    // ------------------------------------------------------------------ tools

    fun setTool(t: Tool) = _state.update { s ->
        s.copy(
            tool = t,
            lastInspect = if (t.isInspect) t else s.lastInspect,
            // Switching inspect mode clears the other mode's selection.
            selected = if (t == Tool.NETS) null else s.selected,
            selectedSymbol = if (t == Tool.NETS) null else s.selectedSymbol,
            selectedNet = if (t == Tool.COMPONENTS) null else s.selectedNet,
            schNet = if (t == Tool.COMPONENTS) null else s.schNet,
        )
    }

    /** Leaves a drawing tool and returns to the last inspect tool. */
    fun endDrawing() = _state.update { it.copy(tool = it.lastInspect) }
    fun setPen(argb: Int) = _state.update { it.copy(penArgb = argb) }
    fun setPenWidth(px: Float) = _state.update { it.copy(penWidthPx = px) }
    /** null = eraser for marks ("clear status"). */
    fun setPaintStatus(s: Status?) = _state.update { it.copy(paintStatus = s) }

    // ------------------------------------------------------------------ S Pen

    private var stylusAutoApplied = false
    private var eraserFromButton = false
    private val erasing = HashSet<Long>()

    /**
     * First S Pen contact or hover. Deliberately does NOT switch to "only the pen draws": that made
     * the Pen tool ignore fingers without the user asking. The option stays in the Display panel.
     */
    fun onStylusSeen() {
        stylusAutoApplied = true
    }

    /** Side button held = eraser; released = back to writing. */
    fun onStylusButton(pressed: Boolean) = _state.update { s ->
        when {
            pressed && s.tool != Tool.ERASER -> { eraserFromButton = true; s.copy(tool = Tool.ERASER) }
            !pressed && eraserFromButton -> { eraserFromButton = false; s.copy(tool = Tool.PEN) }
            else -> s
        }
    }

    /** S Pen dragged in eraser mode: delete every drawing it touches. */
    fun eraseAt(x: Double, y: Double, toleranceUm: Double) {
        val hits = _state.value.strokes.filter { s ->
            s.id !in erasing && CopperIndex.distToPolyline(x, y, s.points) - s.widthUm / 2 <= toleranceUm
        }
        hits.forEach { erasing += it.id; deleteStroke(it.id) }
    }

    /** Tap on the board in board coordinates (µm). */
    fun onBoardTap(x: Double, y: Double, toleranceUm: Double, fromStylus: Boolean = false) {
        val s = _state.value
        val scene = s.scene ?: return
        // While writing with the S Pen, taps (pen or finger) inspect instead of doing nothing.
        val effective = when {
            fromStylus && s.tool == Tool.PEN -> s.lastInspect
            !fromStylus && appearance.value.penOnlyDrawing && (s.tool == Tool.PEN || s.tool == Tool.ERASER) -> s.lastInspect
            else -> s.tool
        }
        when (effective) {
            // Tapping the already-selected part/net again deselects it.
            Tool.COMPONENTS -> {
                val c = scene.picker.pickComponent(x, y, toleranceUm, s.visibleSide, s.display::isVisible)
                val next = if (c != null && c.refDes == s.selected?.refDes) null else c
                _state.update { withSchematicFollow(it.copy(selected = next, selectedSymbol = null, selectedNet = null, schNet = null)) }
            }
            Tool.NETS -> {
                val net = scene.picker.pickNet(x, y, toleranceUm, s.visibleSide, s.display::isVisible)?.net
                val next = if (net != null && net == s.selectedNet) null else net
                _state.update { withSchematicFollow(it.copy(selectedNet = next, schNet = next?.let { n -> it.boardToSch[n] }, selected = null, selectedSymbol = null)) }
            }
            Tool.PAINT -> {
                val c = scene.picker.pickComponent(x, y, toleranceUm, s.visibleSide, s.display::isVisible) ?: return
                val current = s.marks[c.refDes]
                // Tapping a part already painted with the same colour removes the paint.
                val target = s.paintStatus?.argb?.takeIf { it != current?.argb }
                // The paint bucket always paints the one part tapped.
                setMarks(listOf(c.refDes), target)
            }
            Tool.ERASER -> {
                val hit = s.strokes.minByOrNull { CopperIndex.distToPolyline(x, y, it.points) - it.widthUm / 2 }
                    ?.takeIf { CopperIndex.distToPolyline(x, y, it.points) - it.widthUm / 2 <= toleranceUm }
                    ?: return
                deleteStroke(hit.id)
            }
            Tool.PEN -> Unit
        }
    }

    /** Component under a board point on the visible side, for the long-press status ring. */
    fun componentAt(x: Double, y: Double, toleranceUm: Double): Component? {
        val s = _state.value
        return s.scene?.picker?.pickComponent(x, y, toleranceUm, s.visibleSide, s.display::isVisible)
    }

    /** Sets (argb) or removes (null) the status colour of a component. */
    fun setMark(refDes: String, argb: Int?) {
        val s = _state.value
        val group = s.highlighted.map { it.refDes }
        if (refDes in group && group.size > 1) applyToHighlighted(argb, origin = refDes) else setMarks(listOf(refDes), argb)
    }

    /**
     * Sets [argb] on every highlighted part. Parts that already carry a different status keep it
     * (a "Problem" is not overwritten by a batch "OK"); [origin], the part acted on, always changes.
     * Clearing (null) clears all highlighted parts.
     */
    fun applyToHighlighted(argb: Int?, origin: String? = null) {
        val s = _state.value
        val refs = s.highlighted.map { it.refDes }
        if (refs.isEmpty()) return
        // A long-press ("all") group overwrites every status; otherwise existing statuses are kept.
        val (change, keep) = if (s.highlightOverride) refs to emptyList()
        else BatchStatus.split(refs, s.marks.mapValues { it.value.argb }, argb, origin)
        setMarks(change, argb)
        val what = if (argb == null) "Status cleared" else (Status.of(argb)?.label ?: "Status") + " set"
        val msg = "$what on ${change.size} part" + (if (change.size == 1) "" else "s") +
            if (keep.isNotEmpty()) " · ${keep.size} kept their status" else ""
        _state.update { it.copy(message = msg) }
    }

    /** Sets (argb) or removes (null) the status of [refs]; one undo step. */
    fun setMarks(refs: List<String>, argb: Int?) {
        val key = boardKey ?: return
        if (refs.isEmpty()) return
        viewModelScope.launch {
            val before = refs.map { it to dao.mark(key, it) }
            if (argb == null) refs.forEach { dao.deleteMark(key, it) }
            else dao.upsertMarks(before.map { (r, b) -> MarkEntity(key, r, argb, b?.note) })
            pushUndo(if (before.size == 1) Undo.MarkChanged(before[0].first, before[0].second) else Undo.MarksChanged(before))
        }
    }

    fun addStroke(points: List<Pair<Double, Double>>, widthUm: Float) {
        val key = boardKey ?: return
        val argb = _state.value.penArgb
        viewModelScope.launch {
            val id = dao.insertStroke(StrokeEntity(boardKey = key, argb = argb, widthUm = widthUm, points = StrokeEntity.encodePoints(points)))
            pushUndo(Undo.StrokeAdded(id))
        }
    }

    private fun deleteStroke(id: Long) = viewModelScope.launch {
        val s = dao.stroke(id)
        if (s != null) {
            dao.deleteStroke(id)
            pushUndo(Undo.StrokeRemoved(s))
        }
        erasing -= id
    }

    fun clearAnnotations() {
        val key = boardKey ?: return
        viewModelScope.launch {
            val m = dao.marksNow(key); val s = dao.strokesNow(key)
            if (m.isEmpty() && s.isEmpty()) return@launch
            dao.clearMarks(key); dao.clearStrokes(key)
            pushUndo(Undo.Cleared(m, s))
        }
    }

    fun undo() {
        val key = boardKey ?: return
        val u = undo.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (u) {
                is Undo.StrokeAdded -> dao.deleteStroke(u.id)
                is Undo.StrokeRemoved -> dao.restoreStrokes(listOf(u.stroke))
                is Undo.MarkChanged -> if (u.before == null) dao.deleteMark(key, u.refDes) else dao.upsertMark(u.before)
                is Undo.MarksChanged -> u.before.forEach { (r, b) -> if (b == null) dao.deleteMark(key, r) else dao.upsertMark(b) }
                is Undo.Cleared -> { dao.upsertMarks(u.marks); dao.restoreStrokes(u.strokes) }
            }
            _state.update { it.copy(canUndo = undo.isNotEmpty()) }
        }
    }

    private fun pushUndo(u: Undo) {
        undo.addLast(u)
        while (undo.size > 100) undo.removeFirst()
        _state.update { it.copy(canUndo = true) }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "board"
    }
}
