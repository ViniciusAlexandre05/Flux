package com.pcbsolderview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BatchStatusTest {
    private val ok = Status.OK.argb
    private val problem = Status.PROBLEM.argb
    private val refs = listOf("R1", "R2", "R3", "R4")
    private val current = mapOf("R2" to problem, "R3" to ok)

    @Test
    fun keepsPartsWithAnotherStatus() {
        // Batch OK: R1, R4 (no status) and R3 (already OK) change; R2 keeps "Problem".
        assertEquals(listOf("R1", "R3", "R4") to listOf("R2"), BatchStatus.split(refs, current, ok, origin = null))
    }

    @Test
    fun partActedOnAlwaysChanges() {
        // Setting OK from R2 itself (e.g. via its ring menu) changes R2 too.
        assertEquals(refs to emptyList<String>(), BatchStatus.split(refs, current, ok, origin = "R2"))
    }

    @Test
    fun clearingAppliesToAll() {
        assertEquals(refs to emptyList<String>(), BatchStatus.split(refs, current, null, origin = null))
    }
}
