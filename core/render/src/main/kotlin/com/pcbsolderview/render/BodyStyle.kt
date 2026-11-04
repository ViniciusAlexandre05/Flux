package com.pcbsolderview.render

import androidx.compose.ui.graphics.Color
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Rect

/** Typical package colours for the realistic view, guessed from designator and description. */
object BodyStyle {
    val ResistorBlack = Color(0xFF1C1C1E)
    val CeramicTan = Color(0xFFB99A6E)
    val InductorGray = Color(0xFF45484D)
    val IcBlack = Color(0xFF17181A)
    val LedWhite = Color(0xFFE8E2CF)
    val TerminalBlue = Color(0xFF2F6DB5)
    val ConnectorBeige = Color(0xFFDCD5C0)
    val CrystalSilver = Color(0xFFC5CBD3)
    val Default = Color(0xFF2A2C30)

    private fun prefix(ref: String) = ref.takeWhile { it.isLetter() }.uppercase()

    fun colorFor(c: Component): Color {
        val desc = listOfNotNull(c.description, c.value, c.footprint).joinToString(" ").lowercase()
        // Descriptions first: designers often name connectors by function ("24V_IN", "OUT_1").
        when {
            "terminal" in desc || "borne" in desc || "screw block" in desc -> return TerminalBlue
            Regex("""\bled\b""").containsMatchIn(desc) -> return LedWhite
            "crystal" in desc || "xtal" in desc || "oscillator" in desc -> return CrystalSilver
        }
        return when (prefix(c.refDes)) {
            "R", "RN", "RV" -> ResistorBlack
            "C" -> if ("tant" in desc || "elec" in desc) Color(0xFF6E5B2E) else CeramicTan
            "L", "FB" -> InductorGray
            "D", "LED" -> if ("led" in desc) LedWhite else IcBlack
            "U", "IC", "Q", "T", "VR", "PS" -> IcBlack
            "J", "P", "CN", "CON", "X", "K", "TB" -> ConnectorBeige
            "Y", "XTAL", "OSC" -> CrystalSilver
            "F" -> Color(0xFFD9D2C3)
            "SW", "S", "BTN" -> Color(0xFF2B2B2B)
            else -> Default
        }
    }

    /**
     * Body shape: the package outline when known; otherwise the hit-test box shrunk so pads at the
     * ends stay visible (Pick & Place has no body data).
     */
    fun shapeFor(c: Component): List<Point> {
        c.outline?.takeIf { it.size >= 3 }?.let { return it }
        val b = c.bounds
        val insetX = (b.width * 0.12).toLong(); val insetY = (b.height * 0.12).toLong()
        val r = Rect(b.minX + insetX, b.minY + insetY, b.maxX - insetX, b.maxY - insetY)
        return listOf(Point(r.minX, r.minY), Point(r.maxX, r.minY), Point(r.maxX, r.maxY), Point(r.minX, r.maxY))
    }

    /** Silkscreen ink that stays readable on the chosen solder mask. */
    fun silkFor(mask: Color): Color = if (mask.red * 0.3f + mask.green * 0.59f + mask.blue * 0.11f > 0.6f) Color(0xFF1A1A1A) else Color(0xFFF2F2EE)

    val PadFinish = Color(0xFFD8B45A) // ENIG / HASL gold
}
