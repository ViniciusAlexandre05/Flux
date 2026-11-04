package com.pcbsolderview.ui

import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.DetailField
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.PadShape
import com.pcbsolderview.model.Point
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailFieldsTest {
    @Test
    fun hiddenFieldsRoundTrip() {
        val s = setOf(DetailField.LIBRARY, DetailField.POSITION)
        assertEquals(s, Appearance.decodeFields(Appearance.encodeFields(s)))
        assertEquals(emptySet<DetailField>(), Appearance.decodeFields(""))  // "show all" is saved as empty
        assertNull(Appearance.decodeFields(null))                           // never saved -> defaults
        assertEquals(setOf(DetailField.HEIGHT), Appearance.decodeFields("HEIGHT,NOT_A_FIELD"))
        val a = Appearance()
        assertFalse(a.shows(DetailField.LIBRARY)); assertTrue(a.shows(DetailField.PART_NUMBER))
    }

    @Test
    fun mountingDescribesPads() {
        fun pad(name: String, drill: Long? = null) = Pad(0, name, Point(0, 0), PadShape.RECT, 1, 1, drill = drill)
        assertEquals("SMD · 2 pins", mountingText(listOf(pad("1"), pad("2"))))
        // Through-hole pins appear once per copper layer; they are counted once.
        assertEquals("Through-hole · 3 pins", mountingText(listOf("1", "2", "3", "1", "2", "3").map { pad(it, 800) }))
        assertEquals("Mixed SMD / through-hole · 2 pins", mountingText(listOf(pad("1"), pad("2", 800))))
        assertNull(mountingText(emptyList()))
    }
}
