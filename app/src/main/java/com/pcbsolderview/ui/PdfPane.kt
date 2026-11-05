package com.pcbsolderview.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pcbsolderview.model.Rect
import com.pcbsolderview.render.ViewTransform
import com.pcbsolderview.render.rememberViewGestureState
import com.pcbsolderview.render.viewGestures
import com.pcbsolderview.render.writeTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * View-only schematic fallback: the pages of a PDF (e.g. Altium's Smart PDF), with the same pan,
 * pinch and rotation as the board. There is no selection sync: a PDF has no parts or nets.
 */
@Composable
fun PdfPane(bytes: ByteArray, rotationGesture: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // PdfRenderer needs a file descriptor: write the bytes to a private cache file once.
    val renderer = remember(bytes) {
        runCatching {
            val f = File(context.cacheDir, "schematic.pdf").apply { writeBytes(bytes) }
            PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
        }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    if (renderer == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("This PDF could not be opened.", color = MaterialTheme.colorScheme.error) }
        return
    }
    var page by remember(renderer) { mutableIntStateOf(0) }
    var bitmap by remember(renderer) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, page) {
        bitmap = null
        bitmap = withContext(Dispatchers.IO) { synchronized(renderer) { renderPage(renderer, page) } }
    }
    var transform by remember(renderer, page) { mutableStateOf<ViewTransform?>(null) }
    var viewport by remember { mutableStateOf(0 to 0) }

    Column(modifier) {
        if (renderer.pageCount > 1) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (i in 0 until renderer.pageCount) {
                        val on = i == page
                        Text(
                            "Page ${i + 1}",
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.heightIn(min = 40.dp)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraLarge)
                                .clickable { page = i }.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            // Content units are bitmap pixels, Y up like every other view (ViewTransform flips Y).
            val extent = Rect(0, 0, bmp.width.toLong(), bmp.height.toLong())
            LaunchedEffect(bmp, viewport) {
                val (w, h) = viewport
                if (transform == null && w > 0 && h > 0) transform = ViewTransform.fitted(extent, w.toDouble(), h.toDouble())
            }
            val gestures = rememberViewGestureState()
            gestures.transform = transform
            gestures.onTransform = { transform = it }
            gestures.rotation = rotationGesture
            val image = remember(bmp) { bmp.asImageBitmap() }
            val matrix = remember { Matrix() }
            Canvas(
                Modifier.fillMaxSize().clipToBounds()
                    .onSizeChanged { viewport = it.width to it.height }
                    .viewGestures(gestures)
                    .testTag("schematic.pdf"),
            ) {
                drawRect(Color(0xFF6B7280))
                val t = transform ?: return@Canvas
                t.writeTo(matrix)
                withTransform({
                    transform(matrix)
                    // Bitmap rows go down; flip back so the page is upright.
                    translate(0f, bmp.height.toFloat())
                    scale(1f, -1f, pivot = Offset.Zero)
                }) { drawImage(image) }
            }
            Text(
                "PDF · view only",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Long side of a rendered page, px: sharp at a few times zoom, ~25 MB per page. */
private const val PDF_RENDER_PX = 3200

private fun renderPage(r: PdfRenderer, index: Int): Bitmap? = runCatching {
    r.openPage(index).use { p ->
        val scale = PDF_RENDER_PX.toFloat() / maxOf(p.width, p.height)
        val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }
}.getOrNull()
