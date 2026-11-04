package com.pcbsolderview.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), HIGH_CONTRAST("High contrast") }

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
) {
    companion object {
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
            )
        }

        private fun mirror(context: Context, a: Appearance) {
            context.getSharedPreferences(SYNC, Context.MODE_PRIVATE).edit()
                .putString("mode", a.mode.name).putInt("accent", a.accent).putInt("board_background", a.boardBackground)
                .putBoolean("rotation_gesture", a.rotationGesture).putBoolean("pen_only_drawing", a.penOnlyDrawing)
                .putBoolean("show_fps", a.showFps)
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
        }
    }
}
