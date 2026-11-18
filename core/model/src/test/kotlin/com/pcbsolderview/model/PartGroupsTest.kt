package com.pcbsolderview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PartGroupsTest {
    private val pads = ArrayList<Pad>()

    private fun comp(ref: String, value: String?, fp: String? = "0402", pn: String? = null, th: Boolean = false, noPads: Boolean = false): Component {
        val ids = if (noPads) emptyList() else (0 until 2).map { i ->
            Pad(id = pads.size, name = "${i + 1}", position = Point(0, 0), shape = PadShape.RECT, width = 1, height = 1,
                drill = if (th) 800 else null, componentRef = ref).also { pads += it }.id
        }
        return Component(refDes = ref, value = value, footprint = fp, partNumber = pn, side = Side.TOP,
            position = Point(0, 0), bounds = Rect(0, 0, 1, 1), padIds = ids)
    }

    private fun board(vararg c: Component) = Board(name = "t", source = SourceFormat.IPC2581, components = c.toList(), pads = pads.toList())

    @Test
    fun groupsByPartNumberOrValueAndFootprint() {
        val b = board(
            comp("R10", "10k"), comp("R2", "10 K"), comp("R3", "10k", fp = "0603"),
            comp("C1", "100nF", pn = "GRM155"), comp("C2", "0.1uF", pn = "grm155 "),
            comp("U1", null), comp("U2", null),
        )
        val groups = b.partGroups()
        // Every part in exactly one group.
        assertEquals(b.components.map { it.refDes }.sorted(), groups.flatMap { g -> g.parts.map { it.refDes } }.sorted())
        val byFirst = groups.associate { it.first.refDes to it.parts.map { p -> p.refDes } }
        assertEquals(listOf("R2", "R10"), byFirst["R2"]) // natural order inside the group
        assertEquals(listOf("R3"), byFirst["R3"])
        assertEquals(listOf("C1", "C2"), byFirst["C1"]) // same part number, values written differently
        assertEquals(listOf("U1"), byFirst["U1"]) // unknown parts never group
        assertEquals(listOf("U2"), byFirst["U2"])
    }

    @Test
    fun smdFirstThenThroughHoleThenNoPadsBiggestFirst() {
        val b = board(
            comp("J1", "HDR", fp = "HDR2", th = true),
            comp("X1", "MARK", noPads = true),
            comp("C1", "1u"), comp("R1", "1k"), comp("R2", "1k"), comp("R3", "1k"),
            comp("J2", "HDR", fp = "HDR2", th = true), comp("J3", "HDR", fp = "HDR2", th = true),
            comp("C2", "1u"),
        )
        val order = b.partGroups().map { "${it.first.refDes}:${it.parts.size}:${it.mount}" }
        assertEquals(listOf("R1:3:SMD", "C1:2:SMD", "J1:3:THROUGH_HOLE", "X1:1:null"), order)
    }
}
