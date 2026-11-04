package com.pcbsolderview.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.pcbsolderview.R

/** The app icon (background glow + artwork) as a rounded tile, same look as on the home screen. */
@Composable
fun FluxLogo(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(modifier.size(size).clip(shape).border(androidx.compose.ui.unit.Dp.Hairline, MaterialTheme.colorScheme.outlineVariant, shape)) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // Launchers show the middle ~72/108 of the canvas; scale up the same way.
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().scale(1.4f))
    }
}
