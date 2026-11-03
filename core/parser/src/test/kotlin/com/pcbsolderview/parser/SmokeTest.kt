package com.pcbsolderview.parser

import com.pcbsolderview.model.Board
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun modelIsReachable() {
        assertEquals("x", Board("x").name)
    }
}
