package com.pcbsolderview.parser.altium

import com.pcbsolderview.model.HAlign
import com.pcbsolderview.model.SchematicSheet
import com.pcbsolderview.model.VAlign
import com.pcbsolderview.model.SchematicLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class SchematicLayoutTest {
    private val samples = File(System.getProperty("samples.dir") ?: "samples")
    private fun sheet(path: String): SchematicSheet? = File(samples, path).takeIf { it.exists() }?.let { SchDocParser.parse(it.readBytes(), it.name) }

    @Test
    fun miniSheetLayout() {
        val layout = SchematicLayout(sheet("synthetic/ipc-mini/mini.SchDoc")!!)
        // Pin numbers shown for R1/C1 (show-designator bit), names for U1's pins.
        val texts = layout.texts.map { it.text }
        assertTrue(texts.containsAll(listOf("R1", "10k", "C1", "100nF", "U1A", "U1B", "IN", "RST", "GND", "VCC", "Mini test sheet")), texts.toString())
        assertEquals(layout.texts.size, layout.textOwners.size)
        assertEquals("U1", layout.textOwners[layout.texts.indexOfFirst { it.text == "RST" }])
        // GND points down: its name sits centred below the symbol.
        val gnd = layout.texts.first { it.text == "GND" }
        assertEquals(HAlign.CENTER, gnd.halign); assertEquals(VAlign.TOP, gnd.valign)
        assertEquals(6, layout.strokes.count { it.kind == SchematicLayout.Kind.WIRE })
        assertEquals(1, layout.dots.size)
        assertTrue(layout.strokes.count { it.kind == SchematicLayout.Kind.POWER } >= 4)
    }

    /** Previews for comparing with Altium's own output (build/renders). */
    @Test
    fun renderPreviews() {
        for ((path, out) in listOf("synthetic/ipc-mini/mini.SchDoc" to "mini", "real/RPI_Module/RP2040-Module.SchDoc" to "RP2040-Module")) {
            val s = sheet(path) ?: continue
            File("build/renders").mkdirs()
            ImageIO.write(SchematicPng.render(SchematicLayout(s), 2400), "png", File("build/renders/schematic-$out.png"))
        }
    }
}

/** JVM preview of a [SchematicLayout] with the same drawing rules as [SchematicCanvas] (light theme). */
object SchematicPng {
    fun render(l: SchematicLayout, widthPx: Int): BufferedImage {
        val e = l.sheet.bounds
        val scale = widthPx.toDouble() / e.width
        val img = BufferedImage(widthPx, (e.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = java.awt.Color(l.sheet.paperArgb ?: SchematicLayout.PAPER); g.fillRect(0, 0, img.width, img.height)
        fun x(v: Long) = (v - e.minX) * scale
        fun y(v: Long) = (e.maxY - v) * scale
        fun path(pts: List<com.pcbsolderview.model.Point>, closed: Boolean) = Path2D.Double().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.x), y(p.y)) else lineTo(x(p.x), y(p.y)) }
            if (closed) closePath()
        }
        for (f in l.fills) { g.color = java.awt.Color(f.argb, true); g.fill(path(f.points, true)) }
        for (s in l.strokes) {
            g.color = java.awt.Color(s.argb, true)
            g.stroke = BasicStroke(maxOf(1f, (s.widthUm * scale).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path(s.points, s.closed))
        }
        for (d in l.dots) { g.color = java.awt.Color(d.argb, true); val r = d.radius * scale; g.fill(java.awt.geom.Ellipse2D.Double(x(d.center.x) - r, y(d.center.y) - r, 2 * r, 2 * r)) }
        for (t in l.texts) {
            val px = (t.sizeUm * scale).toFloat()
            if (px < 3f || t.text.isEmpty()) continue
            val family = if (t.fontName.orEmpty().contains("Times", true)) Font.SERIF else Font.SANS_SERIF
            g.font = Font(family, (if (t.bold) Font.BOLD else 0) or (if (t.italic) Font.ITALIC else 0), 1).deriveFont(px)
            g.color = java.awt.Color(t.argb ?: SchematicLayout.TEXT_INK, true)
            val w = g.fontMetrics.stringWidth(t.text).toFloat()
            val dx = when (t.halign) { HAlign.LEFT -> 0f; HAlign.CENTER -> -w / 2; HAlign.RIGHT -> -w }
            val dy = when (t.valign) { VAlign.BOTTOM -> -0.2f * px; VAlign.MIDDLE -> 0.32f * px; VAlign.TOP -> 0.8f * px }
            val at = g.transform
            g.translate(x(t.position.x), y(t.position.y))
            g.rotate(-Math.toRadians(t.rotationDeg))
            g.drawString(t.text, dx, dy)
            g.transform = at
        }
        g.dispose()
        return img
    }
}
