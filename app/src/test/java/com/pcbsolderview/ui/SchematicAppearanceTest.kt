package com.pcbsolderview.ui

import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.SchematicInk
import com.pcbsolderview.data.ThemeMode
import com.pcbsolderview.render.GridStyle
import com.pcbsolderview.render.SchematicColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Settings › Appearance › Schematic view: defaults keep the theme's look, choices override it. */
class SchematicAppearanceTest {
    @Test
    fun defaultsFollowTheTheme() {
        val dark = schematicColorsFor(Appearance(mode = ThemeMode.DARK))
        assertEquals(0xFF161C24.toInt(), dark.paper)
        assertEquals(SchematicColors.Ink.INVERTED, dark.ink)
        assertEquals(0xFF4DD8E6.toInt(), dark.net)
        val style = schematicStyleFor(Appearance())
        assertEquals(1f, style.textScale)
        assertEquals(GridStyle.NONE, style.grid)
    }

    @Test
    fun choicesOverrideTheTheme() {
        val a = Appearance(
            mode = ThemeMode.DARK, schPaper = 0xFFFFFFFF.toInt(), schInk = SchematicInk.ORIGINAL,
            schNetColor = 0xFF22C55E.toInt(), schGrid = GridStyle.DOTS, schGridStepUm = 5080, schTextScale = 1.5f,
        )
        val c = schematicColorsFor(a)
        assertEquals(0xFFFFFFFF.toInt(), c.paper)
        assertEquals(SchematicColors.Ink.ORIGINAL, c.ink)
        assertEquals(0xFF22C55E.toInt(), c.net)
        assertEquals(1.5f, schematicStyleFor(a).textScale)
        assertEquals(5080L, schematicStyleFor(a).gridStepUm)
        // "File" paper: the colour saved in the schematic (null = use the file's).
        assertNull(schematicColorsFor(a.copy(schPaper = Appearance.SCH_PAPER_FILE)).paper)
    }
}
