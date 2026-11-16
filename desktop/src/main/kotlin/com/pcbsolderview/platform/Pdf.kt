package com.pcbsolderview.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/** Desktop replacement of android.graphics.pdf.PdfRenderer, with Apache PDFBox. Not thread-safe: synchronise on it. */
class PdfRenderer(bytes: ByteArray) : AutoCloseable {
    private val doc: PDDocument = Loader.loadPDF(bytes)
    private val renderer = PDFRenderer(doc)

    val pageCount: Int get() = doc.numberOfPages

    /** Page [index] as an opaque white-backed image whose long side is [longSidePx]. */
    fun render(index: Int, longSidePx: Int): ImageBitmap? = runCatching {
        val box = doc.getPage(index).mediaBox
        val longPt = maxOf(box.width, box.height).coerceAtLeast(1f)
        val scale = longSidePx / longPt
        renderer.renderImage(index, scale, ImageType.RGB).toComposeImageBitmap()
    }.getOrNull()

    override fun close() = doc.close()
}
