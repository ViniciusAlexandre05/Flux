package com.pcbsolderview.model

/**
 * Format-independent schematic model (v0.1). Produced from Altium .SchDoc (native or ASCII).
 *
 * Coordinates follow the board rule: micrometres (Long), Y axis up, converted to screen only by
 * the render module's ViewTransform. Every primitive is in absolute sheet coordinates (symbols
 * are already placed, rotated and mirrored).
 */
data class Schematic(
    val name: String,
    val sheets: List<SchematicSheet> = emptyList(),
) {
    /** Every placed symbol of every sheet. */
    val symbols: List<SchSymbol> by lazy { sheets.flatMap { it.symbols } }

    /**
     * Symbols by board designator (upper-case). A multi-part part (U201A, U201B) has several
     * symbols under one designator (U201).
     */
    val symbolsByRef: Map<String, List<SchSymbol>> by lazy { symbols.groupBy { it.refDes.uppercase() } }

    fun symbolsOf(refDes: String): List<SchSymbol> = symbolsByRef[refDes.uppercase()].orEmpty()

    /** Sheet index that holds [symbol]. */
    fun sheetOf(symbol: SchSymbol): Int = sheets.indexOfFirst { s -> s.symbols.any { it === symbol } }

    /**
     * Designators used by more than one part (upper-case key, natural order): symbols under one
     * designator that come from different library parts, or repeat a part number (two "part 1").
     * The parts of one multi-part part (U201A, U201B) are not duplicates. The annotation should be
     * fixed in Altium, otherwise the pins and nets of those parts are mixed up.
     */
    val duplicateDesignators: Map<String, List<SchSymbol>> by lazy {
        symbolsByRef.filter { (ref, syms) ->
            ref.isAnnotated() && syms.size > 1 &&
                (syms.map { it.libRef }.distinct().size > 1 || syms.groupingBy { it.partId }.eachCount().values.any { it > 1 })
        }.toSortedMap(NaturalOrder)
    }

    fun isDuplicate(refDes: String): Boolean = refDes.uppercase() in duplicateDesignators

    /** Warning for the part panel when [refDes] is a duplicate designator, else null. */
    fun duplicateWarning(refDes: String): String? {
        val syms = duplicateDesignators[refDes.uppercase()] ?: return null
        val parts = syms.map { it.libRef ?: it.comment ?: "?" }.distinct()
        return "Used by ${syms.size} symbols in the schematic" +
            (if (parts.size > 1) " (${parts.joinToString(", ")})" else "") +
            ". Pins and nets of these parts may be mixed up; fix the annotation in Altium."
    }

    /** One notice for the user about [duplicateDesignators], null when there are none. */
    fun duplicateNotice(): String? {
        val list = duplicateDesignators.keys.toList()
        if (list.isEmpty()) return null
        val names = if (list.size <= 4) list.joinToString(", ") else list.take(4).joinToString(", ") + " and ${list.size - 4} more"
        val one = list.size == 1
        return "Schematic: the designator${if (one) "" else "s"} $names ${if (one) "is" else "are"} used by more than one part. " +
            "Their pins and nets may be mixed up; fix the annotation in Altium."
    }

    /** Designators of the parts (unique, natural order), without unannotated "R?" / "*". */
    val designators: List<String> by lazy {
        symbols.map { it.refDes }.filter { it.isAnnotated() }.distinct().sortedWith(NaturalOrder)
    }
}

/** False for designators Altium has not numbered yet ("R?", "U?", "*"). */
fun String.isAnnotated(): Boolean = isNotBlank() && this != "*" && !endsWith("?")

data class SchematicSheet(
    val name: String,
    /** Paper size (µm); the sheet spans (0,0)..(width,height). */
    val width: Long = 0,
    val height: Long = 0,
    /** Paper colour from the file (0xAARRGGBB), null = default. */
    val paperArgb: Int? = null,
    val symbols: List<SchSymbol> = emptyList(),
    val wires: List<SchWire> = emptyList(),
    val junctions: List<Point> = emptyList(),
    val netLabels: List<SchNetLabel> = emptyList(),
    val powerPorts: List<SchPowerPort> = emptyList(),
    /** Graphics not owned by a symbol (title block, notes, harness frames…). */
    val graphics: List<SchGraphic> = emptyList(),
    /** Free text not owned by a symbol. */
    val texts: List<SchText> = emptyList(),
    /** No-ERC markers (small crosses). */
    val noErc: List<Point> = emptyList(),
) {
    /** Paper rectangle, or the extent of the content when the paper size is unknown. */
    val bounds: Rect by lazy {
        if (width > 0 && height > 0) Rect(0, 0, width, height)
        else (symbols.map { it.bounds } + wires.map { Rect.of(it.points) }).reduceOrNull { a, b -> a.union(b) } ?: Rect(0, 0, 1, 1)
    }
}

/** One placed symbol (one part of a part: U201A and U201B are two symbols of U201). */
data class SchSymbol(
    /** Board designator: "U201" for every part of a multi-part U201. */
    val refDes: String,
    /** As shown on the sheet: "U201A" for part 1 of a multi-part part, else [refDes]. */
    val label: String,
    /** 1-based part of a multi-part part. */
    val partId: Int = 1,
    val partCount: Int = 1,
    val libRef: String? = null,
    val description: String? = null,
    /** "Comment" parameter (value shown on the sheet, e.g. "10k"). */
    val comment: String? = null,
    /** Current PCB footprint model. */
    val footprint: String? = null,
    val position: Point,
    val rotationDeg: Double = 0.0,
    val mirrored: Boolean = false,
    val pins: List<SchPin> = emptyList(),
    val graphics: List<SchGraphic> = emptyList(),
    /** Visible designator, comment and parameter texts. */
    val texts: List<SchText> = emptyList(),
    /** Parameters (name → value), hidden ones included; "*" values are dropped. */
    val parameters: Map<String, String> = emptyMap(),
    /** Body + pins, for hit-testing. */
    val bounds: Rect,
)

enum class PinElectrical { INPUT, IO, OUTPUT, OPEN_COLLECTOR, PASSIVE, HIZ, OPEN_EMITTER, POWER, UNKNOWN }

data class SchPin(
    /** Pin number, matches the board pad name. */
    val designator: String,
    val name: String? = null,
    /** Characters of [name] drawn with an overline (active-low signals), null when none. */
    val nameOverline: List<Boolean>? = null,
    /** Connection point (the outer end, where wires attach). */
    val position: Point,
    /** End on the symbol body. */
    val base: Point,
    val hidden: Boolean = false,
    val showName: Boolean = true,
    val showDesignator: Boolean = true,
    val electrical: PinElectrical = PinElectrical.UNKNOWN,
    /** Direction from the body to the connection point, degrees CCW (0, 90, 180, 270). */
    val angleDeg: Int = 0,
    val argb: Int? = null,
)

data class SchWire(val points: List<Point>, val widthUm: Long = 0, val argb: Int? = null)

data class SchNetLabel(val text: String, val position: Point, val label: SchText)

enum class PowerStyle { CIRCLE, ARROW, BAR, WAVE, GROUND_POWER, GROUND_SIGNAL, GROUND_EARTH, GOST_ARROW, GOST_GROUND, GOST_EARTH, GOST_BAR, UNKNOWN }

data class SchPowerPort(
    val text: String,
    val position: Point,
    /** Direction the symbol points to from [position], degrees CCW. */
    val angleDeg: Int = 90,
    val style: PowerStyle = PowerStyle.BAR,
    val showName: Boolean = true,
    val argb: Int? = null,
)

/** Text anchored at [position] with Altium's 9-point justification. */
data class SchText(
    val text: String,
    val position: Point,
    /** Font em size (µm). */
    val sizeUm: Long,
    val rotationDeg: Double = 0.0,
    val halign: HAlign = HAlign.LEFT,
    val valign: VAlign = VAlign.BOTTOM,
    val argb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontName: String? = null,
    /** Text with an overbar (Altium's `\` escapes, e.g. `R\S\T\`): characters to overline. */
    val overline: List<Boolean>? = null,
)

enum class HAlign { LEFT, CENTER, RIGHT }
enum class VAlign { BOTTOM, MIDDLE, TOP }

sealed class SchGraphic {
    abstract val argb: Int?

    /** Open polyline (lines, polylines, Bézier tessellated). */
    data class Poly(val points: List<Point>, val widthUm: Long, override val argb: Int?) : SchGraphic()

    /** Closed shape: polygon, rectangle, rounded rectangle, ellipse (tessellated). */
    data class Shape(
        val points: List<Point>,
        val widthUm: Long,
        override val argb: Int?,
        /** Fill colour, null = not filled. */
        val fillArgb: Int?,
    ) : SchGraphic()

    /** Circular arc, angles in degrees CCW from +X; start == end - 360 means a full circle. */
    data class Arc(
        val center: Point,
        val radius: Long,
        val startDeg: Double,
        val endDeg: Double,
        val widthUm: Long,
        override val argb: Int?,
    ) : SchGraphic()
}
