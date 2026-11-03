package com.pcbsolderview.parser.text

import com.pcbsolderview.model.Units
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Altium writes UTF-8 (sometimes with BOM) or the Windows ANSI code page. */
fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, start, bytes.size - start, Charset.forName("windows-1252"))
    }
}

/** Lower-cases and strips everything except letters and digits: "Center-X(mm)" -> "centerxmm". */
fun normalizeHeader(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

/** Unit hinted by a column header such as "Center-X(mm)" or "Mid X (mil)". */
fun unitFromHeader(header: String): Units? {
    val m = Regex("""\(\s*(mm|mil|mils|in|inch|um)\s*\)""", RegexOption.IGNORE_CASE).find(header) ?: return null
    return unitFromToken(m.groupValues[1])
}

fun unitFromToken(token: String): Units? = when (token.trim().lowercase()) {
    "mm", "metric", "millimeter", "millimeters", "millimetre", "millimetres" -> Units.MILLIMETER
    "mil", "mils", "imperial", "thou" -> Units.MIL
    "in", "inch", "inches" -> Units.INCH
    "um", "µm", "micron", "microns" -> Units.MICROMETER
    else -> null
}

private val numberWithUnit = Regex("""^\s*([-+]?(?:\d+(?:[.,]\d*)?|[.,]\d+)(?:[eE][-+]?\d+)?)\s*(mm|mil|mils|in|inch|um)?\s*$""", RegexOption.IGNORE_CASE)

/** Parses "12.5", "12,5", "1325mil", "10.2mm". Returns value + explicit unit (if any), or null. */
fun parseNumberWithUnit(s: String): Pair<Double, Units?>? {
    val m = numberWithUnit.matchEntire(s) ?: return null
    val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val u = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { unitFromToken(it) }
    return v to u
}

fun parseDouble(s: String): Double? = parseNumberWithUnit(s)?.first

fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() && it != "-" && it != "—" }
