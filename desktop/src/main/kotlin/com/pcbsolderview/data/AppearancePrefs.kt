package com.pcbsolderview.data

import com.pcbsolderview.platform.AppDirs
import com.pcbsolderview.platform.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.pcbsolderview.render.GridStyle
import com.pcbsolderview.render.GridUnit

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), HIGH_CONTRAST("High contrast") }

/** Rows of the part details panel; each can be shown or hidden in Settings. */
enum class DetailField(val label: String) {
    VALUE("Value"),
    DESCRIPTION("Description"),
    PART_NUMBER("Part number"),
    MANUFACTURER("Manufacturer"),
    FOOTPRINT("Footprint"),
    LIBRARY("Library"),
    LAYER("Layer"),
    MOUNT("Mounting & pins"),
    HEIGHT("Height"),
    POSITION("Position"),
    ROTATION("Rotation"),
    STATUS("Status buttons"),
    IDENTICAL("Identical parts"),
    NETS("Pins & nets list"),
}

/**
 * App-wide settings (the gear menu): look, plus interaction options that apply to every board.
 * Colours are 0xAARRGGBB.
 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: Int = ACCENTS.first().second,
    val boardBackground: Int = BACKGROUNDS[1].second,
    /** Two-finger twist rotates the board. */
    val rotationGesture: Boolean = true,
    /** Only the S Pen draws; fingers always move the board. */
    val penOnlyDrawing: Boolean = false,
    /** Frame-rate meter on the board. */
    val showFps: Boolean = false,
    /** Background grid behind the board. */
    val grid: GridStyle = GridStyle.NONE,
    /** Grid spacing follows the zoom (auto) or stays at [gridStepUm] (manual). */
    val gridAuto: Boolean = false,
    /** Unit for the manual spacing slider. */
    val gridUnit: GridUnit = GridUnit.MM,
    /** Manual grid spacing in µm. */
    val gridStepUm: Long = 1_000,
    /** Part detail rows the user turned off. */
    val hiddenDetails: Set<DetailField> = setOf(DetailField.LIBRARY),
    /** Selecting on the board pans the schematic to the part (and the other way round). */
    val followSelection: Boolean = true,
) {
    fun shows(f: DetailField) = f !in hiddenDetails

    companion object {
        fun encodeFields(s: Set<DetailField>) = s.joinToString(",") { it.name }
        fun decodeFields(s: String?): Set<DetailField>? = s?.split(',')?.mapNotNull { n -> runCatching { DetailField.valueOf(n.trim()) }.getOrNull() }?.toSet()

        val ACCENTS: List<Pair<String, Int>> = listOf(
            "Solder" to 0xFFF5B83D.toInt(),
            "Cyan" to 0xFF4DD8E6.toInt(),
            "Green" to 0xFF5CD08A.toInt(),
            "Purple" to 0xFFB39DDB.toInt(),
            "Red" to 0xFFFF6B5E.toInt(),
        )
        val BACKGROUNDS: List<Pair<String, Int>> = listOf(
            "Black" to 0xFF000000.toInt(),
            "Slate" to 0xFF0B0F14.toInt(),
            "Dark gray" to 0xFF2B2F36.toInt(),
            "Gray" to 0xFF6B7280.toInt(),
            "Light gray" to 0xFFD9DDE3.toInt(),
            "White" to 0xFFFFFFFF.toInt(),
        )
    }
}

/**
 * Desktop: settings live in one JSON file (%APPDATA%\Flux\settings.json), read synchronously at
 * start ([snapshot], the Android SharedPreferences mirror's role) and written on every change.
 * Same keys as the Android DataStore.
 */
class AppearancePrefs(private val dirs: AppDirs) {
    private val state = MutableStateFlow(snapshot(dirs))

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** Last saved settings, read synchronously (needed for the first frame). */
        fun snapshot(dirs: AppDirs): Appearance {
            val d = Appearance()
            val p = runCatching { json.parseToJsonElement(dirs.settings.readText()).jsonObject }.getOrNull() ?: return d
            fun str(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull
            fun int(k: String) = (p[k] as? JsonPrimitive)?.intOrNull
            fun long(k: String) = (p[k] as? JsonPrimitive)?.longOrNull
            fun bool(k: String) = (p[k] as? JsonPrimitive)?.booleanOrNull
            return Appearance(
                mode = str("mode")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.mode,
                accent = int("accent") ?: d.accent,
                boardBackground = int("board_background") ?: d.boardBackground,
                rotationGesture = bool("rotation_gesture") ?: d.rotationGesture,
                penOnlyDrawing = bool("pen_only_drawing") ?: d.penOnlyDrawing,
                showFps = bool("show_fps") ?: d.showFps,
                grid = str("grid")?.let { runCatching { GridStyle.valueOf(it) }.getOrNull() } ?: d.grid,
                gridAuto = bool("grid_auto") ?: d.gridAuto,
                gridUnit = str("grid_unit")?.let { runCatching { GridUnit.valueOf(it) }.getOrNull() } ?: d.gridUnit,
                gridStepUm = long("grid_step_um") ?: d.gridStepUm,
                hiddenDetails = Appearance.decodeFields(str("hidden_details")) ?: d.hiddenDetails,
                followSelection = bool("follow_selection") ?: d.followSelection,
            )
        }

        internal fun encode(a: Appearance): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("version", 1)
            put("mode", a.mode.name)
            put("accent", a.accent)
            put("board_background", a.boardBackground)
            put("rotation_gesture", a.rotationGesture)
            put("pen_only_drawing", a.penOnlyDrawing)
            put("show_fps", a.showFps)
            put("grid", a.grid.name)
            put("grid_auto", a.gridAuto)
            put("grid_unit", a.gridUnit.name)
            put("grid_step_um", a.gridStepUm)
            put("hidden_details", Appearance.encodeFields(a.hiddenDetails))
            put("follow_selection", a.followSelection)
        })
    }

    val appearance: Flow<Appearance> = state

    suspend fun save(a: Appearance) {
        withContext(NonCancellable + Dispatchers.IO) { dirs.settings.writeAtomically(encode(a)) }
        state.value = a
    }
}
