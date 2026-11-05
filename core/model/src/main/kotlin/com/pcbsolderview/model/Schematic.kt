package com.pcbsolderview.model

/**
 * Format-independent schematic model (v0.1). Placeholder from S0 so the import result and the UI
 * state can already carry a schematic; S1 fills it with symbols, pins, wires and nets.
 *
 * Coordinates will follow the board rule: micrometres (Long), Y axis up, converted to screen only
 * by the render module's ViewTransform.
 */
data class Schematic(
    val name: String,
    val sheets: List<SchematicSheet> = emptyList(),
)

/** One sheet of a schematic document (an Altium .SchDoc is one sheet). */
data class SchematicSheet(
    val name: String,
)
