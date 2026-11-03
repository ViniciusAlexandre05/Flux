package com.pcbsolderview.parser.bom

import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.testXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BomParserTest {

    @Test
    fun altiumCsvBom() {
        val bom = BomParser.parse(Samples.text("synthetic/simple/Simple BOM.csv"))
        assertEquals(10, bom.designatorCount)
        val c2 = bom["c2"]!!
        assertEquals("100nF", c2.value)
        assertEquals("Capacitor, ceramic, 50V X7R", c2.description)
        assertEquals("GRM188R71H104KA93D", c2.partNumber)
        assertEquals("Murata", c2.manufacturer)
        assertEquals(null, bom["J1"]!!.manufacturer) // empty cell -> null
    }

    @Test
    fun semicolonBomWithRangesAndPreamble() {
        val bom = BomParser.parse(Samples.text("synthetic/double-sided/DoubleSided BOM.csv"))
        assertEquals(8, bom.designatorCount)
        assertEquals("100n", bom["C1"]!!.value)
        assertEquals("100n", bom["C2"]!!.value)
        assertEquals("10k", bom["R11"]!!.value)
        assertEquals("Wi-Fi; BT module", bom["U3"]!!.description)
    }

    @Test
    fun expandsDesignators() {
        assertEquals(listOf("R1", "R2", "R3", "C5"), BomParser.expandDesignators("R1-R3, C5"))
        assertEquals(listOf("R7", "R8"), BomParser.expandDesignators("R7-8"))
        assertEquals(listOf("J1-A"), BomParser.expandDesignators("J1-A"))
    }

    @Test
    fun xlsxBom() {
        val bytes = minimalXlsx(
            shared = listOf("Designator", "Comment", "Description", "C1, C2", "100nF", "Cap"),
            sheet = """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
                  <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" t="s"><v>4</v></c><c r="C2" t="s"><v>5</v></c></row>
                  <row r="3"><c r="A3" t="inlineStr"><is><t>R9</t></is></c><c r="C3" t="inlineStr"><is><t>Res</t></is></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
        )
        val rows = XlsxReader(testXml).readFirstSheet(bytes)
        assertEquals(listOf("Designator", "Comment", "Description"), rows[0])
        assertEquals(listOf("R9", "", "Res"), rows[2])
        val bom = BomParser.fromTable(rows[0], rows.drop(1))
        assertEquals("100nF", bom["C2"]!!.value)
        assertEquals("Res", bom["R9"]!!.description)
        assertEquals(null, bom["R9"]!!.value)
    }

    companion object {
        fun minimalXlsx(shared: List<String>, sheet: String): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                z.write(("<sst>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>").toByteArray())
                z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                z.write(sheet.toByteArray())
            }
            return out.toByteArray()
        }
    }
}
