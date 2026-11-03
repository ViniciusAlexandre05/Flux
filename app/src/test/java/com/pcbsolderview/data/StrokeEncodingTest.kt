package com.pcbsolderview.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StrokeEncodingTest {
    @Test
    fun roundTripsWholeMicrometres() {
        val pts = listOf(1.4 to -2.6, 100_000.0 to 55_031.0)
        val s = StrokeEntity.encodePoints(pts)
        assertEquals("1,-3;100000,55031", s)
        assertEquals(listOf(1.0 to -3.0, 100_000.0 to 55_031.0), StrokeEntity.decodePoints(s))
        assertEquals(emptyList<Pair<Double, Double>>(), StrokeEntity.decodePoints(""))
        assertEquals(listOf(5.0 to 6.0), StrokeEntity.decodePoints("5,6;garbage;7"))
    }
}
