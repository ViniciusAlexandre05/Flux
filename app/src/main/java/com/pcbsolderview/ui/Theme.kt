package com.pcbsolderview.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcbsolderview.render.BoardColors

/**
 * "Workbench" palette: deep slate surfaces so the board's own colours stand out, solder amber as
 * the accent, net cyan as the secondary. Dark only — the app is used at a soldering bench.
 */
object Palette {
    val Ink = Color(0xFF0B0F14)          // board background, behind everything
    val Surface = Color(0xFF11161D)
    val SurfaceLow = Color(0xFF151B23)
    val SurfaceMid = Color(0xFF1A212B)
    val SurfaceHigh = Color(0xFF212935)
    val SurfaceHighest = Color(0xFF283241)
    val Outline = Color(0xFF3A4656)
    val OutlineVariant = Color(0xFF2A333F)
    val Text = Color(0xFFE6EAF0)
    val TextMuted = Color(0xFF9AA6B5)

    val Solder = Color(0xFFF5B83D)       // primary accent
    val SolderDeep = Color(0xFF3B2C08)
    val Net = Color(0xFF4DD8E6)          // secondary: nets / highlights
    val NetDeep = Color(0xFF0D3A40)
    val Ok = Color(0xFF5CD08A)
    val Danger = Color(0xFFFF6B5E)

    val Substrate = Color(0xFF0F2A1C)    // board body
}

private val scheme = darkColorScheme(
    primary = Palette.Solder,
    onPrimary = Color(0xFF241A00),
    primaryContainer = Palette.SolderDeep,
    onPrimaryContainer = Color(0xFFFFDFA0),
    secondary = Palette.Net,
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Palette.NetDeep,
    onSecondaryContainer = Color(0xFFB8F3FA),
    tertiary = Palette.Ok,
    onTertiary = Color(0xFF00391C),
    error = Palette.Danger,
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1A14),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceMid,
    onSurfaceVariant = Palette.TextMuted,
    surfaceTint = Palette.Solder,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineVariant,
    scrim = Color.Black,
    surfaceDim = Palette.Ink,
    surfaceBright = Palette.SurfaceHighest,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.SurfaceLow,
    surfaceContainer = Palette.SurfaceMid,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHighest,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val typography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

/** Monospace style for designators, net names and coordinates. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

/** Board canvas colours derived from the palette, so the board and the UI share one ground. */
val boardColors = BoardColors(
    background = Palette.Ink,
    boardFill = Palette.Substrate,
    outline = Color(0xFFE8D27A),
    selected = Palette.Solder,
    net = Palette.Net,
)

@Composable
fun PcbTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
}
