package com.pcbsolderview.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), HIGH_CONTRAST("High contrast") }

/** User-chosen look. Colours are 0xAARRGGBB. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: Int = ACCENTS.first().second,
    val boardBackground: Int = BACKGROUNDS[1].second,
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

    val appearance: Flow<Appearance> = context.dataStore.data.map { p ->
        Appearance(
            mode = p[kMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            accent = p[kAccent] ?: Appearance().accent,
            boardBackground = p[kBackground] ?: Appearance().boardBackground,
        )
    }

    suspend fun save(a: Appearance) {
        context.dataStore.edit { p ->
            p[kMode] = a.mode.name
            p[kAccent] = a.accent
            p[kBackground] = a.boardBackground
        }
    }
}
