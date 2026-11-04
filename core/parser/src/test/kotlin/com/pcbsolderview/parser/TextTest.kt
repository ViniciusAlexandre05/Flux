package com.pcbsolderview.parser

import com.pcbsolderview.parser.text.DelimitedText
import com.pcbsolderview.parser.text.decodeText
import com.pcbsolderview.parser.text.parseNumberWithUnit
import com.pcbsolderview.model.Units
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TextTest {
    @Test
    fun splitsQuotedCsv() {
        assertEquals(listOf("C1", "Capacitor, ceramic", "say \"hi\"", ""),
            DelimitedText.split("\"C1\",\"Capacitor, ceramic\",\"say \"\"hi\"\"\",", DelimitedText.Mode.COMMA))
    }

    @Test
    fun detectsDelimiters() {
        assertEquals(DelimitedText.Mode.SEMICOLON, DelimitedText.detectMode("a;b;\"c,d\""))
        assertEquals(DelimitedText.Mode.TAB, DelimitedText.detectMode("a\tb\tc"))
        assertEquals(DelimitedText.Mode.WHITESPACE, DelimitedText.detectMode("Designator  Mid X  Mid Y"))
    }

    @Test
    fun parsesNumbersWithUnits() {
        assertEquals(1325.0 to Units.MIL, parseNumberWithUnit("1325mil"))
        assertEquals(10.5 to Units.MILLIMETER, parseNumberWithUnit(" 10.5 mm "))
        assertEquals(-3.25 to null, parseNumberWithUnit("-3,25"))
        assertNull(parseNumberWithUnit("abc"))
    }

    @Test
    fun decodesUtf8BomAndAnsi() {
        assertEquals("µm", decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "µm".toByteArray()))
        assertEquals("µ", decodeText(byteArrayOf(0xB5.toByte()))) // windows-1252
    }

    @Test
    fun repairsMojibake() {
        assertEquals("1.1nV/√Hz", com.pcbsolderview.parser.text.fixMojibake("1.1nV/âˆšHz"))
        assertEquals("Ø 3 mm µF", com.pcbsolderview.parser.text.fixMojibake("Ã˜ 3 mm Âµ" + "F"))
        // Double-encoded text is repaired too; clean text and real accents stay as they are.
        val once = String("√".toByteArray(Charsets.UTF_8), charset("windows-1252"))
        val twice = String(once.toByteArray(Charsets.UTF_8), charset("windows-1252"))
        assertEquals("√", com.pcbsolderview.parser.text.fixMojibake(twice))
        assertEquals("Resistor 1%", com.pcbsolderview.parser.text.fixMojibake("Resistor 1%"))
        assertEquals("Conector fêmea", com.pcbsolderview.parser.text.fixMojibake("Conector fêmea"))
    }

    @Test
    fun recognisesPartNumbers() {
        listOf("L7812CV", "LT6238CGNPBF", "TC7660HEOA", "BC847,215", "GRM188R71H104KA93D").forEach {
            assertEquals(true, com.pcbsolderview.parser.text.looksLikePartNumber(it), it)
        }
        listOf("RES 100K OHM 1%", "BORNE 1X2", "LOGO LESC", "CAP", "1234", null).forEach {
            assertEquals(false, com.pcbsolderview.parser.text.looksLikePartNumber(it), it.toString())
        }
    }

    @Test
    fun keepsNewlinesInsideQuotes() {
        assertEquals(listOf("a,\"b\nc\"", "d"), DelimitedText.lines("a,\"b\nc\"\r\nd"))
    }
}
