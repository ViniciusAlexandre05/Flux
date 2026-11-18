package com.pcbsolderview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchematicDuplicatesTest {
    private fun sym(ref: String, lib: String, part: Int = 1, count: Int = 1) = SchSymbol(
        refDes = ref, label = ref, partId = part, partCount = count, libRef = lib,
        position = Point(0, 0), bounds = Rect(0, 0, 1, 1),
    )

    private fun sch(vararg s: SchSymbol) = Schematic("t", listOf(SchematicSheet("Sheet1", symbols = s.toList())))

    @Test
    fun multiPartPartIsNotADuplicate() {
        val s = sch(sym("U1", "LM358", 1, 2), sym("U1", "LM358", 2, 2), sym("R1", "RES"))
        assertTrue(s.duplicateDesignators.isEmpty())
        assertNull(s.duplicateNotice())
        assertNull(s.duplicateWarning("U1"))
    }

    @Test
    fun differentLibraryPartsOrRepeatedPartAreDuplicates() {
        // RP2040 case: a two-part chip plus a one-part header with the same designator.
        val s = sch(
            sym("RP2040", "RP2040", 1, 2), sym("RP2040", "RP2040", 2, 2), sym("RP2040", "Header"),
            sym("R1", "RES"), sym("r1", "RES"), // same part placed twice (case-insensitive)
            sym("R?", "RES"), sym("R?", "CAP"), sym("*", "X"), sym("*", "Y"), // unannotated: ignored
        )
        assertEquals(listOf("R1", "RP2040"), s.duplicateDesignators.keys.toList())
        assertTrue(s.isDuplicate("rp2040"))
        assertFalse(s.isDuplicate("R?"))
        assertEquals(3, s.duplicateDesignators.getValue("RP2040").size)
        val warning = s.duplicateWarning("RP2040")
        assertNotNull(warning)
        warning!!
        assertTrue("RP2040, Header" in warning, warning)
        assertTrue(s.duplicateNotice()!!.contains("designators R1, RP2040 are"))
    }

    @Test
    fun longListsAreShortened() {
        val s = sch(*(1..6).flatMap { listOf(sym("C$it", "CAP"), sym("C$it", "CAP")) }.toTypedArray())
        assertTrue(s.duplicateNotice()!!.contains("C1, C2, C3, C4 and 2 more"))
    }
}
