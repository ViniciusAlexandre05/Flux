package com.pcbsolderview.data

import com.pcbsolderview.platform.AppDirs
import com.pcbsolderview.platform.writeAtomically
import com.pcbsolderview.render.ComponentMark
import com.pcbsolderview.render.InkStroke
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Desktop replacement of the Android Room database: the same entities and DAO functions, stored as
// JSON files under AppDirs (annotations/<sha1(boardKey)>.json, recents.json). No synchronisation
// with the tablet. Every file has a "version" field for future format changes.

@Serializable
data class MarkEntity(val boardKey: String, val refDes: String, val argb: Int, val note: String? = null) {
    fun toMark() = ComponentMark(refDes, argb, note, glyph = com.pcbsolderview.Status.of(argb)?.glyph)
}

@Serializable
data class StrokeEntity(
    val id: Long = 0,
    val boardKey: String,
    val argb: Int,
    val widthUm: Float,
    /** "x,y;x,y;…" in whole micrometres. */
    val points: String,
) {
    fun toStroke() = InkStroke(id, argb, widthUm, decodePoints(points))

    companion object {
        fun encodePoints(pts: List<Pair<Double, Double>>): String =
            pts.joinToString(";") { "${Math.round(it.first)},${Math.round(it.second)}" }

        fun decodePoints(s: String): List<Pair<Double, Double>> =
            if (s.isEmpty()) emptyList() else s.split(';').mapNotNull { p ->
                val i = p.indexOf(',')
                if (i < 0) null else (p.substring(0, i).toDoubleOrNull() ?: return@mapNotNull null) to (p.substring(i + 1).toDoubleOrNull() ?: return@mapNotNull null)
            }
    }
}

/** A board opened before; its files are copied into app storage at [dir]. */
@Serializable
data class RecentBoardEntity(
    val boardKey: String,
    val name: String,
    val source: String,
    val components: Int,
    val lastOpened: Long,
    /** Absolute path of the folder holding the board's files and thumb.png. */
    val dir: String,
    /** Name chosen by the user; [name] (from the file) is kept because it identifies the board. */
    val customName: String? = null,
) {
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: name
}

data class MarkCount(val boardKey: String, val argb: Int, val n: Int)
data class StrokeCount(val boardKey: String, val n: Int)

@Serializable
private data class AnnotationFile(
    val version: Int = 1,
    val boardKey: String,
    val marks: List<MarkEntity> = emptyList(),
    val strokes: List<StrokeEntity> = emptyList(),
)

@Serializable
private data class RecentsFile(val version: Int = 1, val boards: List<RecentBoardEntity> = emptyList())

internal data class BoardAnnotations(val marks: List<MarkEntity> = emptyList(), val strokes: List<StrokeEntity> = emptyList())

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

class RecentDao internal constructor(private val db: AppDatabase) {
    fun recents(): Flow<List<RecentBoardEntity>> = db.recents.map { l -> l.sortedByDescending { it.lastOpened } }

    suspend fun upsert(r: RecentBoardEntity) = db.editRecents { l -> l.filter { it.boardKey != r.boardKey } + r }

    suspend fun touch(key: String, time: Long) = db.editRecents { l -> l.map { if (it.boardKey == key) it.copy(lastOpened = time) else it } }

    suspend fun delete(key: String) = db.editRecents { l -> l.filter { it.boardKey != key } }

    suspend fun get(key: String): RecentBoardEntity? = db.recents.value.firstOrNull { it.boardKey == key }

    suspend fun rename(key: String, name: String?) = db.editRecents { l -> l.map { if (it.boardKey == key) it.copy(customName = name) else it } }

    fun markCounts(): Flow<List<MarkCount>> = db.annotations.map { all ->
        all.flatMap { (key, a) -> a.marks.groupBy { it.argb }.map { (argb, l) -> MarkCount(key, argb, l.size) } }
    }.distinctUntilChanged()

    fun strokeCounts(): Flow<List<StrokeCount>> = db.annotations.map { all ->
        all.filterValues { it.strokes.isNotEmpty() }.map { (key, a) -> StrokeCount(key, a.strokes.size) }
    }.distinctUntilChanged()
}

class AnnotationDao internal constructor(private val db: AppDatabase) {
    fun marks(board: String): Flow<List<MarkEntity>> = db.annotations.map { it[board]?.marks.orEmpty() }.distinctUntilChanged()

    fun strokes(board: String): Flow<List<StrokeEntity>> =
        db.annotations.map { it[board]?.strokes.orEmpty().sortedBy { s -> s.id } }.distinctUntilChanged()

    suspend fun marksNow(board: String): List<MarkEntity> = db.annotations.value[board]?.marks.orEmpty()

    suspend fun strokesNow(board: String): List<StrokeEntity> = db.annotations.value[board]?.strokes.orEmpty()

    suspend fun mark(board: String, refDes: String): MarkEntity? = marksNow(board).firstOrNull { it.refDes == refDes }

    suspend fun upsertMark(mark: MarkEntity) = upsertMarks(listOf(mark))

    suspend fun upsertMarks(marks: List<MarkEntity>) {
        marks.groupBy { it.boardKey }.forEach { (board, l) ->
            val refs = l.map { it.refDes }.toSet()
            db.editBoard(board) { a -> a.copy(marks = a.marks.filter { it.refDes !in refs } + l) }
        }
    }

    suspend fun deleteMark(board: String, refDes: String) = db.editBoard(board) { a -> a.copy(marks = a.marks.filter { it.refDes != refDes }) }

    suspend fun insertStroke(stroke: StrokeEntity): Long {
        var id = 0L
        // The id is taken inside the store lock, so two strokes added at once never share it.
        db.editBoard(stroke.boardKey) { a -> id = db.nextStrokeId(); a.copy(strokes = a.strokes + stroke.copy(id = id)) }
        return id
    }

    /** Puts strokes back with their ids (undo), replacing strokes with the same id. */
    suspend fun restoreStrokes(strokes: List<StrokeEntity>) {
        strokes.groupBy { it.boardKey }.forEach { (board, l) ->
            val ids = l.map { it.id }.toSet()
            db.editBoard(board) { a -> a.copy(strokes = a.strokes.filter { it.id !in ids } + l) }
        }
    }

    suspend fun stroke(id: Long): StrokeEntity? = db.annotations.value.values.firstNotNullOfOrNull { a -> a.strokes.firstOrNull { it.id == id } }

    suspend fun deleteStroke(id: Long) {
        val board = stroke(id)?.boardKey ?: return
        db.editBoard(board) { a -> a.copy(strokes = a.strokes.filter { it.id != id }) }
    }

    suspend fun clearStrokes(board: String) = db.editBoard(board) { it.copy(strokes = emptyList()) }

    suspend fun clearMarks(board: String) = db.editBoard(board) { it.copy(marks = emptyList()) }
}

/** JSON-file store with the Android database's DAOs. State is in memory; every change is written at once. */
class AppDatabase private constructor(private val dirs: AppDirs) {
    private val annotationDir = File(dirs.root, "annotations").apply { mkdirs() }
    private val mutex = Mutex()

    internal val recents = MutableStateFlow(readRecents())
    internal val annotations = MutableStateFlow(readAnnotations())

    private val annotationDao = AnnotationDao(this)
    private val recentDao = RecentDao(this)
    fun annotations(): AnnotationDao = annotationDao
    fun recents(): RecentDao = recentDao

    private fun readRecents(): List<RecentBoardEntity> =
        runCatching { json.decodeFromString<RecentsFile>(dirs.recents.readText()).boards }.getOrDefault(emptyList())

    private fun readAnnotations(): Map<String, BoardAnnotations> =
        annotationDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString<AnnotationFile>(f.readText()) }.getOrNull()
                ?.let { it.boardKey to BoardAnnotations(it.marks, it.strokes) }
        }.toMap()

    internal fun nextStrokeId(): Long = (annotations.value.values.flatMap { it.strokes }.maxOfOrNull { it.id } ?: 0L) + 1

    internal suspend fun editRecents(change: (List<RecentBoardEntity>) -> List<RecentBoardEntity>) = mutex.withLock {
        val next = change(recents.value)
        // Written before it is published (and not cancellable), so closing the window right after a
        // change never loses it.
        withContext(NonCancellable + Dispatchers.IO) { dirs.recents.writeAtomically(json.encodeToString(RecentsFile.serializer(), RecentsFile(boards = next))) }
        recents.value = next
    }

    internal suspend fun editBoard(board: String, change: (BoardAnnotations) -> BoardAnnotations) = mutex.withLock {
        val next = change(annotations.value[board] ?: BoardAnnotations())
        withContext(NonCancellable + Dispatchers.IO) {
            val file = File(annotationDir, sha1(board) + ".json")
            if (next.marks.isEmpty() && next.strokes.isEmpty()) file.delete()
            else file.writeAtomically(json.encodeToString(AnnotationFile.serializer(), AnnotationFile(boardKey = board, marks = next.marks, strokes = next.strokes)))
        }
        annotations.value = annotations.value + (board to next)
    }

    companion object {
        private val instances = HashMap<String, AppDatabase>()

        fun get(dirs: AppDirs): AppDatabase = synchronized(instances) {
            instances.getOrPut(dirs.root.absolutePath) { AppDatabase(dirs) }
        }

        /** Tests: drops the in-memory copy so the next [get] reads the files again (a restart). */
        internal fun forget(dirs: AppDirs) = synchronized(instances) { instances.remove(dirs.root.absolutePath) }

        internal fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
