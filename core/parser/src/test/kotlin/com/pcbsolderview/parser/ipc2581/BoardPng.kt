package com.pcbsolderview.parser.ipc2581

import com.pcbsolderview.model.Board
import com.pcbsolderview.model.LayerType
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.centerline
import com.pcbsolderview.model.outline
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Test-only rasteriser (java.awt) so parser output can be inspected visually and compared with
 * Altium without a device. Top view: bottom layers first, then top.
 */
object BoardPng {
    /**
     * Preview of the app's realistic view (mirrors render/BodyStyle): green mask, gold pads,
     * white silkscreen, part bodies from package outlines with a drop shadow. No tracks/planes.
     */
    fun renderRealistic(board: Board, out: File, widthPx: Int = 1600) {
        val b = board.bounds.inflate(1000)
        val scale = widthPx.toDouble() / b.width
        val heightPx = (b.height * scale).toInt().coerceAtLeast(1)
        val img = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x0B0F14); g.fillRect(0, 0, widthPx, heightPx)
        fun sx(x: Double) = (x - b.minX) * scale
        fun sy(y: Double) = (b.maxY - y) * scale
        fun path(pts: List<Point>, p: Path2D.Double = Path2D.Double(Path2D.WIND_EVEN_ODD)): Path2D.Double {
            pts.forEachIndexed { i, q -> if (i == 0) p.moveTo(sx(q.x.toDouble()), sy(q.y.toDouble())) else p.lineTo(sx(q.x.toDouble()), sy(q.y.toDouble())) }
            p.closePath(); return p
        }
        board.outline.forEach { poly -> val p = path(poly.points); poly.holes.forEach { path(it, p) }; g.color = Color(0x1E5C3A); g.fill(p) }
        // Copper under the mask: mask colour 30% darker (mirrors BodyStyle.copperUnderMask).
        val topCopper = board.layers.filter { it.type == LayerType.COPPER && it.side == Side.TOP }.map { it.name }.toSet()
        g.color = Color(0x15, 0x40, 0x29)
        board.fills.filter { it.layer in topCopper }.forEach { f -> val p = path(f.polygon.points); f.polygon.holes.forEach { path(it, p) }; g.fill(p) }
        board.tracks.filter { it.layer in topCopper }.forEach { t ->
            g.stroke = BasicStroke(maxOf(1.0, t.width * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val p = Path2D.Double(); t.centerline().forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(sx(x), sy(y)) else p.lineTo(sx(x), sy(y)) }; g.draw(p)
        }
        g.color = Color(0xD8B45A)
        board.pads.filter { it.isThroughHole || it.side == Side.TOP }.forEach { g.fill(path(it.outline())) }
        g.color = Color(0x0B0F14)
        val viaHoles = board.vias.map { it.position to it.drill }.toHashSet()
        board.holes.filter { (it.position to it.diameter) !in viaHoles }.forEach { h -> val r = h.diameter / 2.0 * scale; g.fill(Ellipse2D.Double(sx(h.position.x.toDouble()) - r, sy(h.position.y.toDouble()) - r, 2 * r, 2 * r)) }
        val silk = board.layers.filter { it.type == LayerType.SILKSCREEN && it.side == Side.TOP }.map { it.name }.toSet()
        g.color = Color(0xF2F2EE)
        board.fills.filter { it.layer in silk }.forEach { f -> val p = path(f.polygon.points); f.polygon.holes.forEach { path(it, p) }; g.fill(p) }
        board.tracks.filter { it.layer in silk }.forEach { t ->
            g.stroke = BasicStroke(maxOf(1.0, t.width * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val p = Path2D.Double(); t.centerline().forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(sx(x), sy(y)) else p.lineTo(sx(x), sy(y)) }; g.draw(p)
        }
        val parts = board.components.filter { it.side == Side.TOP && it.padIds.isNotEmpty() }
        fun bodyColor(ref: String, desc: String): Color = if ("terminal" in desc || "borne" in desc) Color(0x2F6DB5) else when (ref.takeWhile { it.isLetter() }.uppercase()) {
            "R" -> Color(0x1C1C1E); "C" -> Color(0xB99A6E); "U", "Q" -> Color(0x17181A)
            "J", "P" -> if ("terminal" in desc || "borne" in desc) Color(0x2F6DB5) else Color(0xDCD5C0)
            else -> Color(0x2A2C30)
        }
        val shadow = java.awt.geom.AffineTransform.getTranslateInstance(2.5, 3.5)
        g.color = Color(0, 0, 0, 97)
        parts.forEach { c -> c.outline?.let { g.fill(shadow.createTransformedShape(path(it))) } }
        parts.forEach { c ->
            val o = c.outline ?: return@forEach
            val p = path(o)
            g.color = bodyColor(c.refDes, (c.description ?: "").lowercase()); g.fill(p)
            g.stroke = BasicStroke(1.6f); g.color = Color(255, 255, 255, 46); g.draw(p)
        }
        g.dispose(); out.parentFile.mkdirs(); ImageIO.write(img, "png", out)
    }

    fun render(board: Board, out: File, widthPx: Int = 1600) {
        val b = board.bounds.inflate(1000)
        val scale = widthPx.toDouble() / b.width
        val heightPx = (b.height * scale).toInt().coerceAtLeast(1)
        val img = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x0B0F14); g.fillRect(0, 0, widthPx, heightPx)
        fun sx(x: Double) = (x - b.minX) * scale
        fun sy(y: Double) = (b.maxY - y) * scale // the one Y flip
        fun path(pts: List<Point>, p: Path2D.Double = Path2D.Double(Path2D.WIND_EVEN_ODD)): Path2D.Double {
            pts.forEachIndexed { i, q -> if (i == 0) p.moveTo(sx(q.x.toDouble()), sy(q.y.toDouble())) else p.lineTo(sx(q.x.toDouble()), sy(q.y.toDouble())) }
            p.closePath(); return p
        }

        board.outline.forEach { poly ->
            val p = path(poly.points); poly.holes.forEach { path(it, p) }
            g.color = Color(0x12301F); g.fill(p)
        }

        val defaults = mapOf(LayerType.COPPER to 0xC83232, LayerType.SILKSCREEN to 0xFFFF00)
        val drawn = board.layers.filter { it.type == LayerType.COPPER || it.type == LayerType.SILKSCREEN }
            .sortedWith(compareBy({ if (it.side == Side.TOP) 1 else 0 }, { if (it.type == LayerType.COPPER) 0 else 1 }))
        for (layer in drawn) {
            val rgb = layer.rgb ?: defaults.getValue(layer.type)
            val alpha = if (layer.side == Side.BOTTOM) 110 else 230
            g.color = Color((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, alpha)
            board.fills.filter { it.layer == layer.name }.forEach { f ->
                val p = path(f.polygon.points); f.polygon.holes.forEach { path(it, p) }; g.fill(p)
            }
            board.tracks.filter { it.layer == layer.name }.forEach { t ->
                g.stroke = BasicStroke(maxOf(1.0, t.width * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val pts = t.centerline()
                val p = Path2D.Double()
                pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(sx(x), sy(y)) else p.lineTo(sx(x), sy(y)) }
                g.draw(p)
            }
            board.pads.filter { it.layer == layer.name }.forEach { pad ->
                if (pad.isThroughHole) g.color = Color(0xC0, 0xC0, 0xC0, alpha)
                g.fill(path(pad.outline()))
                g.color = Color((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, alpha)
            }
        }
        g.color = Color(0xC0C0C0)
        board.vias.forEach { v ->
            val r = v.diameter / 2.0 * scale
            g.fill(Ellipse2D.Double(sx(v.position.x.toDouble()) - r, sy(v.position.y.toDouble()) - r, 2 * r, 2 * r))
        }
        g.color = Color(0x0B0F14)
        board.holes.forEach { h ->
            val r = h.diameter / 2.0 * scale
            g.fill(Ellipse2D.Double(sx(h.position.x.toDouble()) - r, sy(h.position.y.toDouble()) - r, 2 * r, 2 * r))
        }
        g.color = Color(0x3FA9F5); g.stroke = BasicStroke(1f)
        board.components.forEach { c ->
            val r = c.bounds
            g.drawRect(sx(r.minX.toDouble()).toInt(), sy(r.maxY.toDouble()).toInt(), (r.width * scale).toInt(), (r.height * scale).toInt())
        }
        g.color = Color.WHITE; g.stroke = BasicStroke(2f)
        board.outline.forEach { poly -> g.draw(path(poly.points)) }
        g.dispose()
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }
}
