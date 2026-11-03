package com.pcbsolderview.render

/** Freehand ink in board coordinates (µm), so it stays on the board while panning/zooming. */
data class InkStroke(
    val id: Long,
    val argb: Int,
    val widthUm: Float,
    val points: List<Pair<Double, Double>>,
)

/** A component painted with a status colour (e.g. green = OK, red = problem). */
data class ComponentMark(val refDes: String, val argb: Int, val note: String? = null)

/** What is drawn. Layer visibility is by layer name. */
data class DisplayOptions(
    val hiddenLayers: Set<String> = emptySet(),
    val showDesignators: Boolean = true,
    val showComponentOutlines: Boolean = true,
    val showMultiLayer: Boolean = true,
    val showAnnotations: Boolean = true,
    val showFps: Boolean = false,
    val rotationGesture: Boolean = true,
    /** When true only the stylus draws; fingers always navigate. Turned on when an S Pen is first seen. */
    val penOnlyDrawing: Boolean = false,
) {
    fun isVisible(layer: String?): Boolean = layer == null || layer !in hiddenLayers
}

/** How single-finger drags are interpreted. The stylus always draws (or erases), never pans. */
enum class CanvasTool { NAVIGATE, PEN, ERASER }
