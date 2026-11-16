package com.pcbsolderview.render

/** Freehand ink in board coordinates (µm), so it stays on the board while panning/zooming. */
data class InkStroke(
    val id: Long,
    val argb: Int,
    val widthUm: Float,
    val points: List<Pair<Double, Double>>,
)

/**
 * A component painted with a status colour (e.g. green = OK, red = problem). [glyph] is a short
 * symbol for the status ("✓", "!") drawn on the part when it is large enough on screen.
 */
data class ComponentMark(val refDes: String, val argb: Int, val note: String? = null, val glyph: String? = null)

/** What is drawn. Layer visibility is by layer name. */
data class DisplayOptions(
    val hiddenLayers: Set<String> = emptySet(),
    val showDesignators: Boolean = true,
    val showComponentOutlines: Boolean = true,
    val showMultiLayer: Boolean = true,
    val showAnnotations: Boolean = true,
    val showFps: Boolean = false,
    val rotationGesture: Boolean = true,
    /** When true only the stylus draws; fingers always navigate. */
    val penOnlyDrawing: Boolean = false,
    /** CAD layers (copper, silk) or a realistic, assembled-board look. */
    val viewMode: ViewMode = ViewMode.CAD,
    /** Solder-mask colour used by [ViewMode.REALISTIC]. */
    val maskArgb: Int = MASK_COLORS.first().second,
    /** Background grid behind the board. */
    val grid: GridStyle = GridStyle.NONE,
    /** Fixed grid spacing in µm (default 1 mm), used when [gridAuto] is off. */
    val gridStepUm: Long = 1_000,
    /** Automatic grid: spacing follows the zoom instead of [gridStepUm]. */
    val gridAuto: Boolean = false,
) {
    companion object {
        val MASK_COLORS: List<Pair<String, Int>> = listOf(
            "Green" to 0xFF1E5C3A.toInt(),
            "Blue" to 0xFF1D3F78.toInt(),
            "Red" to 0xFF8A1E1E.toInt(),
            "Black" to 0xFF141414.toInt(),
            "White" to 0xFFE9EAE6.toInt(),
            "Purple" to 0xFF4B2A6E.toInt(),
        )
    }
    fun isVisible(layer: String?): Boolean = layer == null || layer !in hiddenLayers
}

enum class ViewMode { CAD, REALISTIC }

enum class GridStyle(val label: String) { NONE("None"), LINES("Lines"), DOTS("Dots") }

/** How single-finger drags are interpreted. The stylus always draws (or erases), never pans. */
enum class CanvasTool { NAVIGATE, PEN, ERASER }
