package com.pcbsolderview.render

import com.pcbsolderview.model.SchematicLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchematicColorsTest {
    @Test
    fun lightThemeKeepsAltiumColours() {
        assertEquals(SchematicLayout.WIRE_INK, SchematicColors().stroke(SchematicLayout.WIRE_INK))
    }

    @Test
    fun darkInkKeepsHueAndFlipsLightness() {
        val dark = SchematicColors(ink = SchematicColors.Ink.INVERTED)
        val navy = dark.stroke(SchematicLayout.WIRE_INK)
        val b = navy and 0xFF; val r = navy shr 16 and 0xFF
        assertTrue(b > 200 && r < b, "navy becomes light blue: %08x".format(navy))
        val body = dark.fill(0xFFFFFFB0.toInt())
        assertTrue((body shr 16 and 0xFF) < 90, "pale yellow body becomes dark: %08x".format(body))
        // Black pins become light on dark paper.
        assertTrue((dark.stroke(SchematicLayout.PIN_INK) and 0xFF) > 150)
    }

    @Test
    fun highContrastMakesGreysWhite() {
        val hc = SchematicColors(ink = SchematicColors.Ink.HIGH_CONTRAST)
        assertEquals(0xFFFFFFFF.toInt(), hc.stroke(0xFF000000.toInt()))
    }

    @Test
    fun hslRoundTripIsStable() {
        for (c in listOf(0xFF000080.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF808080.toInt(), 0xFFFFFFB0.toInt())) {
            val back = SchematicColors.withHsl(c) { h, s, l -> Triple(h, s, l) }
            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs((back shr shift and 0xFF) - (c shr shift and 0xFF)) <= 1, "%08x vs %08x".format(c, back))
        }
    }
}
