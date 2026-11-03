package com.pcbsolderview.render

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side

/**
 * Decides what a tap selects. Two modes, one per inspect tool:
 *
 * [pickComponent]: pad under the finger -> its component; component box; then the same within
 * touch tolerance. Only components on the visible side.
 *
 * [pickNet]: pad, track or via under the finger -> its net; then within tolerance; then the copper
 * pour containing the point. Tracks on the visible side win where layers overlap.
 *
 * Only visible layers can be hit.
 */
class BoardPicker(val board: Board) {
    val components = ComponentIndex(board.components)
    val copper = CopperIndex(board)
    private val sides = board.layers.associate { it.name to it.side }
    private val sideOf: (String) -> Side? = { sides[it] }

    fun pickComponent(x: Double, y: Double, tolerance: Double, side: Side, visible: (String?) -> Boolean = { true }): Component? {
        fun owner(tol: Double) = copper.padAt(x, y, tol, visible, side)?.componentRef?.let { board.component(it) }
        return owner(0.0)
            ?: components.hitTest(x, y, side, 0.0)
            ?: owner(tolerance)
            ?: components.hitTest(x, y, side, tolerance)
    }

    fun pickNet(x: Double, y: Double, tolerance: Double, side: Side, visible: (String?) -> Boolean = { true }): BoardHit.OnNet? {
        copper.padAt(x, y, 0.0, visible, side, strictSide = false)?.let { p -> p.net?.let { return BoardHit.OnNet(it, p.layer) } }
        val trace = copper.traceAt(x, y, tolerance, visible, side, sideOf)
        if (trace != null && trace.second == 0.0) return trace.first
        val nearPad = copper.padAt(x, y, tolerance, visible, side, strictSide = false)?.takeIf { it.net != null }
        // Within tolerance: whichever copper is closer wins; a track beats a pad on a tie.
        if (trace != null) return trace.first
        nearPad?.let { return BoardHit.OnNet(it.net!!, it.layer) }
        return copper.pourAt(x, y, visible, side, sideOf)
    }

    /** Component first, then net — kept for callers that want a single answer. */
    fun pick(x: Double, y: Double, tolerance: Double, side: Side, visible: (String?) -> Boolean = { true }): BoardHit? {
        copper.padAt(x, y, 0.0, visible, side)?.componentRef?.let { board.component(it) }?.let { return BoardHit.OnComponent(it) }
        val trace = copper.traceAt(x, y, tolerance, visible, side, sideOf)
        if (trace != null && trace.second == 0.0) return trace.first
        components.hitTest(x, y, side, 0.0)?.let { return BoardHit.OnComponent(it) }
        trace?.let { return it.first }
        pickComponent(x, y, tolerance, side, visible)?.let { return BoardHit.OnComponent(it) }
        return copper.pourAt(x, y, visible, side, sideOf)
    }
}
