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
import com.pcbsolderview.model.identicalTo
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tool {
    /** Tap selects a part (designator, value, pins). */
    COMPONENTS,
    /** Tap selects the net of a track, via, pad or copper pour. */
    NETS,
    PEN, ERASER, PAINT;

    val isInspect: Boolean get() = this == COMPONENTS || this == NETS
}

/** Predefined component statuses for the paint tool. */
enum class Status(val label: String, val argb: Int) {
    OK("OK", 0xFF2ECC40.toInt()),
    PROBLEM("Problem", 0xFFFF4136.toInt()),
    CHECK("Check", 0xFFFFDC00.toInt()),
    INFO("Info", 0xFF0074D9.toInt());

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
    /** Incremented to ask the view to zoom to [selected]. */
    val focusRequest: Int = 0,
    val visibleSide: Side = Side.TOP,
    val display: DisplayOptions = DisplayOptions(),
    val showLayersPanel: Boolean = false,
    val showToolsPanel: Boolean = false,
    val tool: Tool = Tool.COMPONENTS,
    /** Inspect tool to return to when a drawing tool is dismissed. */
    val lastInspect: Tool = Tool.COMPONENTS,
    val penArgb: Int = PEN_COLORS.first(),
    val penWidthPx: Float = PEN_WIDTHS[1],
    val paintStatus: Status? = Status.OK,
    val marks: Map<String, ComponentMark> = emptyMap(),
    val strokes: List<InkStroke> = emptyList(),
    val canUndo: Boolean = false,
)

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
        data class Recent(val key: String) : Origin
    }
    private var annotationsJob: Job? = null
    private var boardKey: String? = null

    private sealed interface Undo {
        data class StrokeAdded(val id: Long) : Undo
        data class StrokeRemoved(val stroke: StrokeEntity) : Undo
        data class MarkChanged(val refDes: String, val before: MarkEntity?) : Undo
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
    fun openRecent(r: RecentBoardEntity) = load("Opening ${r.name}…", Origin.Recent(r.boardKey)) {
        store.load(r.dir).ifEmpty { throw BoardParseException("The saved copy of this board is missing. Open the files again.") }
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
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val inputs = files()
                    val board = importer.import(inputs) { p -> _state.update { it.copy(progress = p) } }
                    _state.update { it.copy(loading = "Preparing view…", progress = null) }
                    val scene = BoardScene(board)
                    remember(origin, keyOf(board), scene, inputs)
                    scene
                }
            }
            result.fold(
                onSuccess = { scene ->
                    undo.clear()
                    _state.value = UiState(
                        recents = _state.value.recents,
                        scene = scene,
                        display = DisplayOptions(hiddenLayers = scene.defaultHiddenLayers, penOnlyDrawing = penOnlyPref),
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
                recentDao.upsert(RecentBoardEntity(key, b.name, b.source.name, b.components.size, now, dir.absolutePath))
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

    fun select(c: Component?) = _state.update { it.copy(selected = c, selectedNet = if (c != null) null else it.selectedNet) }

    fun selectNet(net: String?) = _state.update { it.copy(selectedNet = net) }

    fun clearSelection() = _state.update { it.copy(selected = null, selectedNet = null) }

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
        s.copy(
            selected = focus, selectedNet = null,
            highlighted = hl, highlightLabel = if (hl.isEmpty()) null else "${hl.size} × ${focus?.value}",
            focusRequest = s.focusRequest + 1,
        )
    }

    fun clearSearch() = _state.update { it.copy(query = "", results = emptyList(), highlighted = emptyList(), highlightLabel = null) }

    /** Highlights every part identical to [c]; calling it again for the same group turns it off. */
    fun toggleIdentical(c: Component) = _state.update { s ->
        val board = s.scene?.board ?: return@update s
        val group = board.identicalTo(c)
        if (s.highlighted.map { it.refDes }.toSet() == group.map { it.refDes }.toSet()) {
            s.copy(highlighted = emptyList(), highlightLabel = null)
        } else {
            val what = listOfNotNull(c.value ?: c.description, c.footprint).joinToString(" · ").ifEmpty { c.refDes }
            s.copy(highlighted = group, highlightLabel = "${group.size} × $what")
        }
    }

    fun clearHighlight() = _state.update { it.copy(highlighted = emptyList(), highlightLabel = null) }

    // ------------------------------------------------------------------ view & display

    fun flipSide() = _state.update { it.copy(visibleSide = it.visibleSide.opposite) }

    fun setDisplay(d: DisplayOptions) {
        penOnlyPref = d.penOnlyDrawing
        _state.update { it.copy(display = d) }
    }

    fun toggleLayer(name: String) = _state.update { s ->
        val h = s.display.hiddenLayers
        s.copy(display = s.display.copy(hiddenLayers = if (name in h) h - name else h + name))
    }

    fun setLayersVisible(names: Collection<String>, visible: Boolean) = _state.update { s ->
        val h = s.display.hiddenLayers
        s.copy(display = s.display.copy(hiddenLayers = if (visible) h - names.toSet() else h + names))
    }

    fun resetLayers() = _state.update { s -> s.copy(display = s.display.copy(hiddenLayers = s.scene?.defaultHiddenLayers.orEmpty())) }

    fun toggleLayersPanel() = _state.update { it.copy(showLayersPanel = !it.showLayersPanel) }

    fun toggleToolsPanel() = _state.update { it.copy(showToolsPanel = !it.showToolsPanel) }

    // ------------------------------------------------------------------ tools

    fun setTool(t: Tool) = _state.update { s ->
        s.copy(
            tool = t,
            lastInspect = if (t.isInspect) t else s.lastInspect,
            // Switching inspect mode clears the other mode's selection.
            selected = if (t == Tool.NETS) null else s.selected,
            selectedNet = if (t == Tool.COMPONENTS) null else s.selectedNet,
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
    /** Kept across boards: once the pen is in use, fingers don't draw (until the user turns it off). */
    private var penOnlyPref = false
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
            !fromStylus && s.display.penOnlyDrawing && (s.tool == Tool.PEN || s.tool == Tool.ERASER) -> s.lastInspect
            else -> s.tool
        }
        when (effective) {
            // Tapping the already-selected part/net again deselects it.
            Tool.COMPONENTS -> {
                val c = scene.picker.pickComponent(x, y, toleranceUm, s.visibleSide, s.display::isVisible)
                val next = if (c != null && c.refDes == s.selected?.refDes) null else c
                _state.update { it.copy(selected = next, selectedNet = null) }
            }
            Tool.NETS -> {
                val net = scene.picker.pickNet(x, y, toleranceUm, s.visibleSide, s.display::isVisible)?.net
                val next = if (net != null && net == s.selectedNet) null else net
                _state.update { it.copy(selectedNet = next, selected = null) }
            }
            Tool.PAINT -> {
                val c = scene.picker.pickComponent(x, y, toleranceUm, s.visibleSide, s.display::isVisible) ?: return
                val current = s.marks[c.refDes]
                // Tapping a part already painted with the same colour removes the paint.
                val target = s.paintStatus?.argb?.takeIf { it != current?.argb }
                setMark(c.refDes, target)
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
        val key = boardKey ?: return
        viewModelScope.launch {
            val before = dao.mark(key, refDes)
            if (argb == null) dao.deleteMark(key, refDes)
            else dao.upsertMark(MarkEntity(key, refDes, argb, before?.note))
            pushUndo(Undo.MarkChanged(refDes, before))
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
