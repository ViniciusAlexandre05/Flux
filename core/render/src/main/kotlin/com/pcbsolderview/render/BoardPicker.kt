package com.pcbsolderview.render

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Pad
import com.pcbsolderview.model.Side

/**
 * Decides what a tap selects. Exact hits beat near misses:
 *  1. a pad under the finger  -> its component
 *  2. a track/via under it    -> its net
 *  3. a component box under it -> the component
 *  4. a track/via/pad/box within the touch tolerance (same order)
 *  5. a copper pour            -> its net
 * Only visible layers can be hit.
 */
class BoardPicker(val board: Board) {
    val components = ComponentIndex(board.components)
    val copper = CopperIndex(board)
    private val sides = board.layers.associate { it.name to it.side }
    private val sideOf: (String) -> Side? = { sides[it] }

    fun pick(x: Double, y: Double, tolerance: Double, side: Side, visible: (String?) -> Boolean = { true }): BoardHit? {
        fun padHit(p: Pad): BoardHit? =
            p.componentRef?.let { board.component(it) }?.let { BoardHit.OnComponent(it) }
                ?: p.net?.let { BoardHit.OnNet(it, p.layer) }

        copper.padAt(x, y, 0.0, visible, side)?.let { p -> padHit(p)?.let { return it } }
        val trace = copper.traceAt(x, y, tolerance, visible, side, sideOf)
        if (trace != null && trace.second == 0.0) return trace.first
        components.hitTest(x, y, side, 0.0)?.let { return BoardHit.OnComponent(it) }
        trace?.let { return it.first }
        copper.padAt(x, y, tolerance, visible, side)?.let { p -> padHit(p)?.let { return it } }
        components.hitTest(x, y, side, tolerance)?.let { return BoardHit.OnComponent(it) }
        return copper.pourAt(x, y, visible, side, sideOf)
    }
}
