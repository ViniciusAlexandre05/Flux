package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.PinRef
import com.pcbsolderview.model.Schematic
import com.pcbsolderview.model.SchematicConnectivity
import com.pcbsolderview.parser.BoardImporter
import com.pcbsolderview.parser.Samples
import com.pcbsolderview.parser.testXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class SchematicNetsTest {
    private val mini by lazy { BoardImporter(testXml).import(listOf(Samples.input("synthetic/ipc-mini/mini.xml"), Samples.input("synthetic/ipc-mini/mini.SchDoc"))) }

    @Test
    fun buildsNetsFromWiresLabelsAndPorts() {
        val c = SchematicConnectivity(mini.schematic!!)
        val vcc = c.net("VCC")!!
        // Two VCC labels join R1/C1 with U1.1; the T-stub and junction stay on the same net.
        assertEquals(setOf(PinRef("R1", "1"), PinRef("C1", "1"), PinRef("U1", "1")), vcc.pins)
        assertEquals(setOf(PinRef("R1", "2")), c.net("GND")!!.pins)
        // Unnamed nets get Altium's automatic name.
        assertEquals("NetU1_2", c.netOf(PinRef("U1", "2"))!!.name)
        assertEquals("NetC1_2", c.netOf(PinRef("C1", "2"))!!.name)
        assertEquals(false, c.netOf(PinRef("U1", "2"))!!.named)
        // Tapping a wire finds its net.
        assertEquals("VCC", c.netAt(0, 400.0 * 254, 580.0 * 254, 300.0)?.name)
    }

    /** S4: every board net has exactly one matching schematic net. */
    @Test
    fun everyBoardNetMatchesOneSchematicNet() {
        val c = SchematicConnectivity(mini.schematic!!)
        val m = c.matchBoard(mini.board)
        assertEquals(mini.board.nets.keys, m.keys)
        for ((boardNet, sch) in m) {
            val pads = mini.board.pads.filter { it.net == boardNet && it.componentRef != null && mini.schematic!!.symbolsOf(it.componentRef!!).isNotEmpty() }
                .map { PinRef(it.componentRef!!, it.name) }
            assertTrue(sch.pins.containsAll(pads), "$boardNet → ${sch.name}")
        }
        assertEquals("VCC", m["VCC"]!!.name); assertEquals("GND", m["GND"]!!.name)
    }

    /** The RP2040 module: board nets against the schematic (printed; the layout is partial). */
    @Test
    fun realRp2040Nets() {
        val dir = "real/RPI_Module"
        assumeTrue(File(Samples.dir, "$dir/RP2040-Module.SchDoc").exists())
        val r = BoardImporter(testXml).import(listOf(Samples.input("$dir/RP2040-Module.cvg"), Samples.input("$dir/RP2040-Module.SchDoc")))
        val c = SchematicConnectivity(r.schematic!!)
        val m = c.matchBoard(r.board)
        println("RP2040 nets: schematic ${c.nets.size}, board ${r.board.nets.size}, matched ${m.size}")
        var exact = 0
        for ((boardNet, sch) in m.toSortedMap()) {
            val pads = r.board.pads.filter { it.net == boardNet && it.componentRef != null }.map { PinRef(it.componentRef!!, it.name) }
            val inSch = pads.filter { c.netOf(it) != null }
            val ok = inSch.all { c.netOf(it) == sch }
            if (ok) exact++ else println("  $boardNet → ${sch.name}: ${inSch.groupBy { c.netOf(it)?.name }}")
        }
        println("  board nets whose schematic pins all share one net: $exact / ${m.size}")
        assertTrue(c.nets.any { it.name == "GND" } && c.nets.any { it.name == "3V3" })
        assertTrue(exact >= m.size * 2 / 3, "most nets line up")
        val names = c.nets.filter { it.named }.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("USB_P", "USB_N", "1V1", "VSYS")), names.toString())
    }

    @Test
    fun emptySchematicHasNoNets() {
        assertEquals(0, SchematicConnectivity(Schematic("x")).nets.size)
    }
}
