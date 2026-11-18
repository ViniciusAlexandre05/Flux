package com.pcbsolderview.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.pcbsolderview.render.GridStyle
import com.pcbsolderview.render.GridUnit
import androidx.datastore.preferences.core.longPreferencesKey

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
    /** Keep the screen on while a board is open (bench work with dirty hands). */
    val keepScreenOn: Boolean = true,
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

private val Context.dataStore by preferencesDataStore(name = "appearance")

class AppearancePrefs(private val context: Context) {
    private val kMode = stringPreferencesKey("mode")
    private val kAccent = intPreferencesKey("accent")
    private val kBackground = intPreferencesKey("board_background")
    private val kRotation = booleanPreferencesKey("rotation_gesture")
    private val kPenOnly = booleanPreferencesKey("pen_only_drawing")
    private val kFps = booleanPreferencesKey("show_fps")
    private val kGrid = stringPreferencesKey("grid")
    private val kGridAuto = booleanPreferencesKey("grid_auto")
    private val kGridUnit = stringPreferencesKey("grid_unit")
    private val kGridStep = longPreferencesKey("grid_step_um")
    private val kHiddenDetails = stringPreferencesKey("hidden_details")
    private val kFollow = booleanPreferencesKey("follow_selection")
    private val kKeepOn = booleanPreferencesKey("keep_screen_on")

    companion object {
        private const val SYNC = "appearance_sync"

        /**
         * Last saved settings, read synchronously. DataStore loads asynchronously, which is too
         * late for the window theme and the first frame; this small mirror avoids a flash of the
         * wrong theme at launch.
         */
        fun snapshot(context: Context): Appearance {
            val p = context.getSharedPreferences(SYNC, Context.MODE_PRIVATE)
            val d = Appearance()
            return Appearance(
                mode = p.getString("mode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.mode,
                accent = p.getInt("accent", d.accent),
                boardBackground = p.getInt("board_background", d.boardBackground),
                rotationGesture = p.getBoolean("rotation_gesture", d.rotationGesture),
                penOnlyDrawing = p.getBoolean("pen_only_drawing", d.penOnlyDrawing),
                showFps = p.getBoolean("show_fps", d.showFps),
                grid = p.getString("grid", null)?.let { runCatching { GridStyle.valueOf(it) }.getOrNull() } ?: d.grid,
                gridAuto = p.getBoolean("grid_auto", d.gridAuto),
                gridUnit = p.getString("grid_unit", null)?.let { runCatching { GridUnit.valueOf(it) }.getOrNull() } ?: d.gridUnit,
                gridStepUm = p.getLong("grid_step_um", d.gridStepUm),
                hiddenDetails = Appearance.decodeFields(p.getString("hidden_details", null)) ?: d.hiddenDetails,
                followSelection = p.getBoolean("follow_selection", d.followSelection),
                keepScreenOn = p.getBoolean("keep_screen_on", d.keepScreenOn),
            )
        }

        private fun mirror(context: Context, a: Appearance) {
            context.getSharedPreferences(SYNC, Context.MODE_PRIVATE).edit()
                .putString("mode", a.mode.name).putInt("accent", a.accent).putInt("board_background", a.boardBackground)
                .putBoolean("rotation_gesture", a.rotationGesture).putBoolean("pen_only_drawing", a.penOnlyDrawing)
                .putBoolean("show_fps", a.showFps).putString("grid", a.grid.name)
                .putBoolean("grid_auto", a.gridAuto).putString("grid_unit", a.gridUnit.name).putLong("grid_step_um", a.gridStepUm)
                .putString("hidden_details", Appearance.encodeFields(a.hiddenDetails))
                .putBoolean("follow_selection", a.followSelection)
                .putBoolean("keep_screen_on", a.keepScreenOn)
                .apply()
        }
    }

    val appearance: Flow<Appearance> = context.dataStore.data.map { p ->
        val d = Appearance()
        Appearance(
            mode = p[kMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.mode,
            accent = p[kAccent] ?: d.accent,
            boardBackground = p[kBackground] ?: d.boardBackground,
            rotationGesture = p[kRotation] ?: d.rotationGesture,
            penOnlyDrawing = p[kPenOnly] ?: d.penOnlyDrawing,
            showFps = p[kFps] ?: d.showFps,
            grid = p[kGrid]?.let { runCatching { GridStyle.valueOf(it) }.getOrNull() } ?: d.grid,
            gridAuto = p[kGridAuto] ?: d.gridAuto,
            gridUnit = p[kGridUnit]?.let { runCatching { GridUnit.valueOf(it) }.getOrNull() } ?: d.gridUnit,
            gridStepUm = p[kGridStep] ?: d.gridStepUm,
            hiddenDetails = Appearance.decodeFields(p[kHiddenDetails]) ?: d.hiddenDetails,
            followSelection = p[kFollow] ?: d.followSelection,
            keepScreenOn = p[kKeepOn] ?: d.keepScreenOn,
        )
    }

    suspend fun save(a: Appearance) {
        mirror(context, a)
        context.dataStore.edit { p ->
            p[kMode] = a.mode.name
            p[kAccent] = a.accent
            p[kBackground] = a.boardBackground
            p[kRotation] = a.rotationGesture
            p[kPenOnly] = a.penOnlyDrawing
            p[kFps] = a.showFps
            p[kGrid] = a.grid.name
            p[kGridAuto] = a.gridAuto
            p[kGridUnit] = a.gridUnit.name
            p[kGridStep] = a.gridStepUm
            p[kHiddenDetails] = Appearance.encodeFields(a.hiddenDetails)
            p[kFollow] = a.followSelection
            p[kKeepOn] = a.keepScreenOn
        }
    }
}
