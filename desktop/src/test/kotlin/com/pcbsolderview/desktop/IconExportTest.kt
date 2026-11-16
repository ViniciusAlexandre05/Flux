package com.pcbsolderview.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Builds the Windows icon (desktop/build/icons/flux.ico, PNG entries 16…256 px) from
 * src/main/resources/flux.svg. When the SVG changes, copy the result to desktop/icons/flux.ico
 * (used by the installer, see build.gradle.kts).
 */
class IconExportTest {
    @Test
    fun exportIco() {
        val svg = File("src/main/resources/flux.svg").readBytes()
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val pngs = sizes.map { px ->
            val dom = SVGDOM(Data.makeFromBytes(svg))
            dom.setContainerSize(px.toFloat(), px.toFloat())
            val surface = Surface.makeRasterN32Premul(px, px)
            surface.canvas.scale(px / 256f, px / 256f)
            dom.setContainerSize(256f, 256f)
            dom.render(surface.canvas)
            surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        u16(0); u16(1); u16(sizes.size)
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, px ->
            out.write(if (px >= 256) 0 else px); out.write(if (px >= 256) 0 else px)
            out.write(0); out.write(0); u16(1); u16(32); u32(pngs[i].size); u32(offset)
            offset += pngs[i].size
        }
        pngs.forEach { out.write(it) }
        val dir = File("build/icons").apply { mkdirs() }
        File(dir, "flux.ico").writeBytes(out.toByteArray())
        File(dir, "flux-256.png").writeBytes(pngs.last())
        assertTrue(pngs.all { it.size > 50 })
    }
}
