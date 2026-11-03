package com.pcbsolderview

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.search
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.InputFile
import com.pcbsolderview.parser.xml.XmlParserFactory
import com.pcbsolderview.render.BoardScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val scene: BoardScene? = null,
    val loading: String? = null,
    /** 0..1 while parsing, null when unknown. */
    val progress: Float? = null,
    val error: String? = null,
    val selected: Component? = null,
    val query: String = "",
    val results: List<Component> = emptyList(),
    /** Components highlighted on the board (search results by value). */
    val highlighted: List<Component> = emptyList(),
    /** Incremented to ask the view to zoom to [selected]. */
    val focusRequest: Int = 0,
)

class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val importer = BoardImporter(XmlParserFactory { Xml.newPullParser() })
    private var loadJob: Job? = null

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
                onSuccess = { scene -> _state.value = UiState(scene = scene) },
                onFailure = { e ->
                    val msg = (e as? BoardParseException)?.message
                        ?: "Could not open the file (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})."
                    _state.update { it.copy(loading = null, progress = null, error = msg) }
                },
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Leaves the board and goes back to the start screen. */
    fun closeBoard() {
        loadJob?.cancel()
        _state.value = UiState()
    }

    fun select(c: Component?) = _state.update { it.copy(selected = c) }

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
        s.copy(selected = focus, highlighted = if (sameValue.size > 1) sameValue else emptyList(), focusRequest = s.focusRequest + 1)
    }

    fun clearSearch() = _state.update { it.copy(query = "", results = emptyList(), highlighted = emptyList()) }

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
