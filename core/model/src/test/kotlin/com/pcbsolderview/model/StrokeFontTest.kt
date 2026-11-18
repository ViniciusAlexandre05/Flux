package com.pcbsolderview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrokeFontTest {
    private fun box(lines: List<List<Point>>) = Rect.of(lines.flatten())

    @Test
    fun capitalsSpanTheHeightAndAdvanceOneHeight() {
        val b = box(StrokeFont.strokes("H", 1000, 2000, heightUm = 600, strokeUm = 100))
        assertEquals(Rect(1000, 2000, 1400, 2600), b)
        // Three characters: two advances of one height plus one glyph width.
        val w = box(StrokeFont.strokes("HHH", 0, 0, 600, 100))
        assertEquals(600L * 2 + 400, w.maxX)
        assertEquals(600L * 2 + 400 + 100, StrokeFont.width("HHH", 600, 100))
    }

    @Test
    fun rotationAndMirrorTurnAboutTheAnchor() {
        val r = box(StrokeFont.strokes("H", 0, 0, 600, 100, rotationDeg = 90.0))
        assertEquals(Rect(-600, 0, 0, 400), r)
        val m = box(StrokeFont.strokes("H", 0, 0, 600, 100, mirrored = true))
        assertEquals(Rect(-400, 0, 0, 600), m)
    }

    @Test
    fun spacesAndUnknownCharacters() {
        assertTrue(StrokeFont.strokes("   ", 0, 0, 600, 100).isEmpty())
        // An unknown character is drawn as a box so it is not silently lost.
        assertEquals(Rect(0, 0, 400, 600), box(StrokeFont.strokes("中", 0, 0, 600, 100)))
        assertTrue(StrokeFont.strokes("10kΩ ±5% µF", 0, 0, 600, 100).size > 10)
    }
}
