package com.pcbsolderview.render

import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Side

/**
 * Hit-testing over component bounds: a [GridIndex] with the board's side rule.
 *
 * Priority when several components overlap the touch point:
 *  1. components on the visible side,
 *  2. components whose box actually contains the point (vs. within touch tolerance),
 *  3. the smallest box.
 */
class ComponentIndex(components: List<Component>) {

    private val grid = GridIndex(components) { it.bounds }

    /** Components whose bounds, inflated by [tolerance], contain the point. */
    fun candidates(x: Double, y: Double, tolerance: Double = 0.0): List<Component> = grid.candidates(x, y, tolerance)

    /**
     * @param tolerance board units (µm); typically touch slop in px divided by view scale.
     * @param strictSide when true, components on the far side can never be hit.
     */
    fun hitTest(x: Double, y: Double, visibleSide: Side, tolerance: Double = 0.0, strictSide: Boolean = true): Component? =
        grid.hitTest(
            x, y, tolerance,
            accept = { !strictSide || it.side == visibleSide },
            priority = { if (it.side == visibleSide) 0 else 1 },
        )
}
