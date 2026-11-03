package com.pcbsolderview

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcbsolderview.data.AppDatabase
import com.pcbsolderview.data.MarkEntity
import com.pcbsolderview.data.StrokeEntity
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side
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

data class UiState(
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
    /** Components highlighted on the board (search results by value). */
    val highlighted: List<Component> = emptyList(),
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
    private val dao = AppDatabase.get(app).annotations()
    private var loadJob: Job? = null
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
        load("Opening…") {
            val resolver = getApplication<Application>().contentResolver
            uris.map { uri ->
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw BoardParseException("Could not read $uri")
                InputFile(displayName(uri), bytes)
            }
        }
    }

    fun openDemo(folder: String) = load("Opening demo…") {
        val assets = getApplication<Application>().assets
        assets.list(folder).orEmpty().map { name ->
            InputFile(name, assets.open("$folder/$name").use { it.readBytes() })
        }
    }

    private fun load(message: String, files: suspend () -> List<InputFile>) {
        loadJob?.cancel()
        _state.update { it.copy(loading = message, progress = null, error = null) }
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val board = importer.import(files()) { p -> _state.update { it.copy(progress = p) } }
                    _state.update { it.copy(loading = "Preparing view…", progress = null) }
                    BoardScene(board)
                }
            }
            result.fold(
                onSuccess = { scene ->
                    undo.clear()
                    _state.value = UiState(scene = scene, display = DisplayOptions(hiddenLayers = scene.defaultHiddenLayers))
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
        boardKey = null; undo.clear()
        _state.value = UiState()
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
        s.copy(
            selected = focus, selectedNet = null,
            highlighted = if (sameValue.size > 1) sameValue else emptyList(), focusRequest = s.focusRequest + 1,
        )
    }

    fun clearSearch() = _state.update { it.copy(query = "", results = emptyList(), highlighted = emptyList()) }

    // ------------------------------------------------------------------ view & display

    fun flipSide() = _state.update { it.copy(visibleSide = it.visibleSide.opposite) }

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

    /** Tap on the board in board coordinates (µm). */
    fun onBoardTap(x: Double, y: Double, toleranceUm: Double) {
        val s = _state.value
        val scene = s.scene ?: return
        when (s.tool) {
            Tool.COMPONENTS -> {
                val c = scene.picker.pickComponent(x, y, toleranceUm, s.visibleSide, s.display::isVisible)
                _state.update { it.copy(selected = c, selectedNet = null) }
            }
            Tool.NETS -> {
                val hit = scene.picker.pickNet(x, y, toleranceUm, s.visibleSide, s.display::isVisible)
                _state.update { it.copy(selectedNet = hit?.net, selected = null) }
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
        val s = dao.stroke(id) ?: return@launch
        dao.deleteStroke(id)
        pushUndo(Undo.StrokeRemoved(s))
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
