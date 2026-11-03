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
    fun keepsNewlinesInsideQuotes() {
        assertEquals(listOf("a,\"b\nc\"", "d"), DelimitedText.lines("a,\"b\nc\"\r\nd"))
    }
}
