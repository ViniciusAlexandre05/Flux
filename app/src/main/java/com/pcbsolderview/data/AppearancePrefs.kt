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
import androidx.datastore.preferences.core.floatPreferencesKey

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), HIGH_CONTRAST("Contrast") }

/** How schematic lines and text are coloured; [THEME] follows the app theme (Light = Altium's colours). */
enum class SchematicInk(val label: String) { THEME("Theme"), ORIGINAL("Altium"), INVERTED("Inverted"), HIGH_CONTRAST("Contrast") }

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
    // ---- Schematic view (Settings > Appearance > Schematic view). 0 = follow the theme.
    /** Paper colour: [SCH_PAPER_THEME], [SCH_PAPER_FILE] (the colour saved in the .SchDoc) or 0xAARRGGBB. */
    val schPaper: Int = SCH_PAPER_THEME,
    val schInk: SchematicInk = SchematicInk.THEME,
    /** Colour of a traced net, 0 = theme. */
    val schNetColor: Int = 0,
    val schGrid: GridStyle = GridStyle.NONE,
    /** Schematic grid spacing in um (Altium's default snap grid: 100 mil). */
    val schGridStepUm: Long = 2540,
    /** Multiplies every schematic text size (0.8 to 2). */
    val schTextScale: Float = 1f,
    /** Settings › Updates: also offer test versions (GitHub pre-releases). */
    val showTestVersions: Boolean = false,
) {
    fun shows(f: DetailField) = f !in hiddenDetails

    companion object {
        fun encodeFields(s: Set<DetailField>) = s.joinToString(",") { it.name }
        fun decodeFields(s: String?): Set<DetailField>? = s?.split(',')?.mapNotNull { n -> runCatching { DetailField.valueOf(n.trim()) }.getOrNull() }?.toSet()

        /** Paper choices; the two first are not colours (alpha 0) but "follow the theme" / "as in the file". */
        const val SCH_PAPER_THEME = 0
        const val SCH_PAPER_FILE = 1
        val SCH_PAPERS: List<Pair<String, Int>> = listOf(
            "Theme" to SCH_PAPER_THEME,
            "File" to SCH_PAPER_FILE,
            "White" to 0xFFFFFFFF.toInt(),
            "Cream" to 0xFFFFFCF8.toInt(),
            "Dark" to 0xFF161C24.toInt(),
            "Black" to 0xFF000000.toInt(),
        )
        /** Traced-net colours; 0 = the theme's. */
        val SCH_NET_COLORS: List<Pair<String, Int>> = listOf(
            "Theme" to 0,
            "Cyan" to 0xFF00B8D4.toInt(),
            "Magenta" to 0xFFFF2BD6.toInt(),
            "Green" to 0xFF22C55E.toInt(),
            "Orange" to 0xFFFF851B.toInt(),
        )
        /** Grid spacings offered (um): 50, 100, 200 mil. */
        val SCH_GRID_STEPS: List<Pair<String, Long>> = listOf("50 mil" to 1270L, "100 mil" to 2540L, "200 mil" to 5080L)
        /** Text size steps. */
        val SCH_TEXT_SCALES: List<Float> = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

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
    private val kSchPaper = intPreferencesKey("sch_paper")
    private val kSchInk = stringPreferencesKey("sch_ink")
    private val kSchNet = intPreferencesKey("sch_net_color")
    private val kSchGrid = stringPreferencesKey("sch_grid")
    private val kSchGridStep = longPreferencesKey("sch_grid_step_um")
    private val kSchText = floatPreferencesKey("sch_text_scale")
    private val kTestVersions = booleanPreferencesKey("show_test_versions")

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
                schPaper = p.getInt("sch_paper", d.schPaper),
                schInk = p.getString("sch_ink", null)?.let { runCatching { SchematicInk.valueOf(it) }.getOrNull() } ?: d.schInk,
                schNetColor = p.getInt("sch_net_color", d.schNetColor),
                schGrid = p.getString("sch_grid", null)?.let { runCatching { GridStyle.valueOf(it) }.getOrNull() } ?: d.schGrid,
                schGridStepUm = p.getLong("sch_grid_step_um", d.schGridStepUm),
                schTextScale = p.getFloat("sch_text_scale", d.schTextScale),
                showTestVersions = p.getBoolean("show_test_versions", d.showTestVersions),
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
                .putInt("sch_paper", a.schPaper).putString("sch_ink", a.schInk.name).putInt("sch_net_color", a.schNetColor)
                .putString("sch_grid", a.schGrid.name).putLong("sch_grid_step_um", a.schGridStepUm).putFloat("sch_text_scale", a.schTextScale)
                .putBoolean("show_test_versions", a.showTestVersions)
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
            schPaper = p[kSchPaper] ?: d.schPaper,
            schInk = p[kSchInk]?.let { runCatching { SchematicInk.valueOf(it) }.getOrNull() } ?: d.schInk,
            schNetColor = p[kSchNet] ?: d.schNetColor,
            schGrid = p[kSchGrid]?.let { runCatching { GridStyle.valueOf(it) }.getOrNull() } ?: d.schGrid,
            schGridStepUm = p[kSchGridStep] ?: d.schGridStepUm,
            schTextScale = p[kSchText] ?: d.schTextScale,
            showTestVersions = p[kTestVersions] ?: d.showTestVersions,
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
            p[kSchPaper] = a.schPaper
            p[kSchInk] = a.schInk.name
            p[kSchNet] = a.schNetColor
            p[kSchGrid] = a.schGrid.name
            p[kSchGridStep] = a.schGridStepUm
            p[kSchText] = a.schTextScale
            p[kTestVersions] = a.showTestVersions
        }
    }
}
