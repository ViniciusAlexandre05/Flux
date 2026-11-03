package com.pcbsolderview.parser.pnp

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.model.Units
import com.pcbsolderview.model.rotatedBox
import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.text.Table
import com.pcbsolderview.parser.text.blankToNull
import com.pcbsolderview.parser.text.parseDouble
import com.pcbsolderview.parser.text.parseNumberWithUnit
import com.pcbsolderview.parser.text.unitFromHeader
import com.pcbsolderview.parser.text.unitFromToken

/**
 * Parses Altium Pick & Place output (CSV or space-aligned TXT). Columns are found by header name;
 * coordinates may be in mm or mil, given per value ("1325mil"), per column ("Center-X(mm)") or in
 * the preamble ("Units used: mm").
 */
object PickPlaceParser {

    private val DESIGNATOR = arrayOf("designator", "refdes", "reference", "ref", "designators")
    private val X = arrayOf("centerx", "centrex", "midx", "posx", "x", "locationx", "refx", "padx")
    private val Y = arrayOf("centery", "centrey", "midy", "posy", "y", "locationy", "refy", "pady")
    private val ROTATION = arrayOf("rotation", "rot", "angle")
    private val LAYER = arrayOf("layer", "side", "tb", "mirror")
    private val COMMENT = arrayOf("comment", "value", "val")
    private val DESCRIPTION = arrayOf("description", "desc")
    private val FOOTPRINT = arrayOf("footprint", "package", "pattern", "pcbfootprint")

    fun looksLikePickPlace(normalizedHeader: List<String>): Boolean {
        fun has(a: Array<String>) = a.any { alias -> normalizedHeader.any { it == alias || it.startsWith(alias) && it.length <= alias.length + 4 } }
        return has(DESIGNATOR) && has(X) && has(Y)
    }

    fun parse(text: String, name: String = "board"): Board {
        val table = Table.read(text) { looksLikePickPlace(it) }
            ?: throw BoardParseException("No Pick & Place header found (need Designator, X and Y columns).")

        val iRef = table.column(*DESIGNATOR)!!
        val iX = table.column(*X)!!
        val iY = table.column(*Y)!!
        val iRot = table.column(*ROTATION)
        val iLayer = table.column(*LAYER)
        val iComment = table.column(*COMMENT)
        val iDesc = table.column(*DESCRIPTION)
        val iFoot = table.column(*FOOTPRINT)

        val docUnits = preambleUnits(table.preamble)
        val xUnits = unitFromHeader(table.header[iX]) ?: docUnits
        val yUnits = unitFromHeader(table.header[iY]) ?: docUnits

        val components = ArrayList<Component>()
        var seenUnits: Units? = null
        for (row in table.rows) {
            val ref = row[iRef].blankToNull() ?: continue
            val x = parseNumberWithUnit(row[iX]) ?: continue
            val y = parseNumberWithUnit(row[iY]) ?: continue
            val ux = x.second ?: xUnits ?: Units.MILLIMETER
            val uy = y.second ?: yUnits ?: Units.MILLIMETER
            seenUnits = seenUnits ?: ux
            val pos = Point(ux.toMicrons(x.first), uy.toMicrons(y.first))
            val rot = iRot?.let { parseDouble(row[it]) } ?: 0.0
            val footprint = iFoot?.let { row[it].blankToNull() }
            val (w, h) = FootprintSizeGuesser.guess(footprint)
            components += Component(
                refDes = ref,
                value = iComment?.let { row[it].blankToNull() },
                description = iDesc?.let { row[it].blankToNull() },
                footprint = footprint,
                side = iLayer?.let { parseSide(row[it]) } ?: Side.TOP,
                position = pos,
                rotationDeg = normalizeAngle(rot),
                bounds = rotatedBox(pos, w, h, rot),
                boundsEstimated = true,
            )
        }
        if (components.isEmpty()) throw BoardParseException("Pick & Place file has a header but no component rows.")

        return Board(
            name = name,
            source = SourceFormat.PICK_AND_PLACE,
            components = components,
            units = seenUnits ?: Units.MILLIMETER,
        )
    }

    fun parseSide(s: String): Side? = when (s.trim().lowercase()) {
        "toplayer", "top", "t", "top layer", "front", "f", "no" -> Side.TOP
        "bottomlayer", "bottom", "b", "bottom layer", "bot", "back", "yes" -> Side.BOTTOM
        else -> null
    }

    private fun preambleUnits(preamble: List<String>): Units? {
        for (line in preamble) {
            val m = Regex("""units\s*(?:used)?\s*[:=]\s*"?([A-Za-z]+)""", RegexOption.IGNORE_CASE).find(line) ?: continue
            unitFromToken(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun normalizeAngle(a: Double): Double {
        var r = a % 360.0
        if (r < 0) r += 360.0
        return r
    }
}
