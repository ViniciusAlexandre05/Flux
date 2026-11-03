package com.pcbsolderview.parser.bom

import com.pcbsolderview.parser.BoardParseException
import com.pcbsolderview.parser.xml.XmlParserFactory
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Lightweight XLSX reader: reads the first worksheet's cell values (shared strings, inline strings,
 * numbers). No styles, formulas or dates — enough for an Altium BOM. Avoids Apache POI.
 */
class XlsxReader(private val xml: XmlParserFactory) {

    fun readFirstSheet(bytes: ByteArray): List<List<String>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                if (n == "xl/sharedStrings.xml" || n.startsWith("xl/worksheets/sheet")) entries[n] = zip.readBytes()
            }
        }
        val sheetName = entries.keys.filter { it.startsWith("xl/worksheets/sheet") }
            .minByOrNull { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
            ?: throw BoardParseException("XLSX file has no worksheet.")
        val shared = entries["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()
        return readSheet(entries.getValue(sheetName), shared)
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val p = xml.create(ByteArrayInputStream(bytes))
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inSi = false; var inT = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> inT = inSi
                }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { out.add(sb.toString()); inSi = false }
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val p = xml.create(ByteArrayInputStream(bytes))
        val rows = ArrayList<List<String>>()
        var row: MutableMap<Int, String>? = null
        var cellCol = 0; var cellType: String? = null
        val value = StringBuilder()
        var inValue = false
        var nextCol = 0
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { row = HashMap(); nextCol = 0 }
                    "c" -> {
                        cellCol = p.getAttributeValue(null, "r")?.let { columnIndex(it) } ?: nextCol
                        cellType = p.getAttributeValue(null, "t")
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val v = if (cellType == "s") raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: "" else raw
                        row?.put(cellCol, v)
                        nextCol = cellCol + 1
                    }
                    "row" -> {
                        val r = row ?: HashMap()
                        val width = (r.keys.maxOrNull() ?: -1) + 1
                        rows.add(List(width) { r[it] ?: "" })
                        row = null
                    }
                }
                XmlPullParser.END_DOCUMENT -> return rows
            }
        }
    }

    /** "AB12" -> 27 (zero-based). */
    private fun columnIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c.isLetter()) n = n * 26 + (c.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }
}
