package com.pcbsolderview.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.pcbsolderview.data.Appearance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min

class BoardColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f; val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun outlineStaysVisibleOnEveryBackground() {
        Appearance.BACKGROUNDS.forEach { (name, argb) ->
            val c = boardColorsFor(Appearance(boardBackground = argb))
            assertEquals(Color(argb), c.background, name)
            // Board outline must stand out from the chosen background (WCAG-style ratio >= 2).
            assertTrue(contrast(c.outline, c.background) >= 2f, "$name: outline contrast ${contrast(c.outline, c.background)}")
        }
    }

    @Test
    fun accentDrivesSelection() {
        Appearance.ACCENTS.forEach { (_, argb) -> assertEquals(Color(argb), boardColorsFor(Appearance(accent = argb)).selected) }
    }
}
