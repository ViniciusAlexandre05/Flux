package com.pcbsolderview.parser.bom

import com.pcbsolderview.model.Board
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.text.Table
import com.pcbsolderview.parser.text.blankToNull
import com.pcbsolderview.parser.text.normalizeHeader

/** One BOM line, already exploded to individual designators. */
data class BomEntry(
    val designators: List<String>,
    val value: String? = null,
    val description: String? = null,
    val footprint: String? = null,
    val partNumber: String? = null,
    val manufacturer: String? = null,
)

class Bom(val entries: List<BomEntry>) {
    private val byRef: Map<String, BomEntry> =
        entries.flatMap { e -> e.designators.map { it.uppercase() to e } }.toMap()

    operator fun get(refDes: String): BomEntry? = byRef[refDes.uppercase()]
    val designatorCount: Int get() = byRef.size
}

object BomParser {
    private val DESIGNATOR = arrayOf("designator", "designators", "refdes", "reference", "references", "ref", "partreferences")
    private val VALUE = arrayOf("value", "comment", "val")
    private val DESCRIPTION = arrayOf("description", "desc")
    private val FOOTPRINT = arrayOf("footprint", "package", "pcbfootprint", "pattern")
    private val PART_NUMBER = arrayOf(
        "manufacturerpartnumber1", "manufacturerpartnumber", "mpn", "partnumber", "mfrpartnumber",
        "mfrpn", "manufacturerpn", "supplierpartnumber1", "libref",
    )
    private val MANUFACTURER = arrayOf("manufacturer1", "manufacturer", "mfr", "manufacturername")

    fun looksLikeBom(normalizedHeader: List<String>): Boolean =
        DESIGNATOR.any { it in normalizedHeader } &&
            (VALUE + DESCRIPTION + FOOTPRINT + PART_NUMBER + arrayOf("quantity", "qty")).any { it in normalizedHeader }

    fun parse(text: String): Bom {
        val table = Table.read(text) { looksLikeBom(it) }
            ?: throw BoardParseException("No BOM header found (need a Designator column).")
        return fromTable(table.header, table.rows)
    }

    fun fromTable(header: List<String>, rows: List<List<String>>): Bom {
        val n = header.map { normalizeHeader(it) }
        fun col(a: Array<String>): Int? = a.firstNotNullOfOrNull { alias -> n.indexOf(alias).takeIf { it >= 0 } }
        val iRef = col(DESIGNATOR) ?: throw BoardParseException("BOM has no Designator column.")
        val iVal = col(VALUE)
        // If both Comment and Value exist, prefer Value for the value and keep Comment out.
        val iDesc = col(DESCRIPTION)
        val iFoot = col(FOOTPRINT)
        val iPn = col(PART_NUMBER)
        val iMfr = col(MANUFACTURER)

        val entries = rows.mapNotNull { row ->
            fun cell(i: Int?) = i?.let { row.getOrNull(it)?.blankToNull() }
            val refs = expandDesignators(cell(iRef) ?: return@mapNotNull null)
            if (refs.isEmpty()) return@mapNotNull null
            BomEntry(refs, cell(iVal), cell(iDesc), cell(iFoot), cell(iPn), cell(iMfr))
        }
        return Bom(entries)
    }

    private val range = Regex("""^([A-Za-z_]+)(\d+)\s*-\s*(?:\1)?(\d+)$""")

    /** "C1, C2 C3;C4", "R1-R4" -> individual designators. */
    fun expandDesignators(s: String): List<String> =
        s.split(',', ';', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { token ->
            val m = range.matchEntire(token)
            if (m != null) {
                val (prefix, a, b) = m.destructured
                val from = a.toInt(); val to = b.toInt()
                if (to >= from && to - from <= 1000) (from..to).map { "$prefix$it" } else listOf(token)
            } else listOf(token)
        }
}

/** Fills missing component fields from the BOM, matching by designator. Existing values win. */
fun Board.withBom(bom: Bom): Board = copy(
    components = components.map { c ->
        val e = bom[c.refDes] ?: return@map c
        // A real part number from the BOM replaces one guessed from the library name.
        val useBomPn = e.partNumber != null && (c.partNumber == null || c.partNumberFromLibrary)
        c.copy(
            value = c.value ?: e.value,
            description = c.description ?: e.description,
            footprint = c.footprint ?: e.footprint,
            partNumber = if (useBomPn) e.partNumber else c.partNumber,
            partNumberFromLibrary = if (useBomPn) false else c.partNumberFromLibrary,
            manufacturer = c.manufacturer ?: e.manufacturer,
        )
    },
)
