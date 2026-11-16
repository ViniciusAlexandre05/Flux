package com.pcbsolderview.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons missing from the Material set. 24×24 viewport, tinted by Icon(). */
object AppIcons {
    /** Eraser tilted 45°: body, a gap, the tip, and a base line. */
    val Eraser: ImageVector by lazy {
        ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                // Body
                moveTo(14.2f, 2.8f); lineTo(21.2f, 9.8f); lineTo(14.6f, 16.4f); lineTo(7.6f, 9.4f); close()
                // Tip (separated from the body by a small gap)
                moveTo(6.8f, 10.2f); lineTo(13.8f, 17.2f); lineTo(10.2f, 20.8f); lineTo(3.2f, 13.8f); close()
                // Base line
                moveTo(12f, 21f); lineTo(21f, 21f); lineTo(21f, 22.6f); lineTo(12f, 22.6f); close()
            }
        }.build()
    }
}
