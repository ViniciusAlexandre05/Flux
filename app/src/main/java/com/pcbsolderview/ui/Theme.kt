package com.pcbsolderview.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcbsolderview.data.Appearance
import com.pcbsolderview.data.ThemeMode
import com.pcbsolderview.render.BoardColors
import com.pcbsolderview.render.SchematicColors

/** "Workbench" dark palette (default theme). */
object Palette {
    val Ink = Color(0xFF0B0F14)
    val Surface = Color(0xFF11161D)
    val SurfaceLow = Color(0xFF151B23)
    val SurfaceMid = Color(0xFF1A212B)
    val SurfaceHigh = Color(0xFF212935)
    val SurfaceHighest = Color(0xFF283241)
    val Outline = Color(0xFF3A4656)
    val OutlineVariant = Color(0xFF2A333F)
    val Text = Color(0xFFE6EAF0)
    val TextMuted = Color(0xFF9AA6B5)
    val Net = Color(0xFF4DD8E6)
    val Ok = Color(0xFF5CD08A)
    val Danger = Color(0xFFFF6B5E)
    val Substrate = Color(0xFF0F2A1C)
}

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF15120A) else Color.White

private fun schemeFor(a: Appearance): ColorScheme {
    val accent = Color(a.accent)
    return when (a.mode) {
        ThemeMode.DARK -> darkColorScheme(
            primary = accent, onPrimary = onColor(accent),
            primaryContainer = lerp(Palette.Surface, accent, 0.22f), onPrimaryContainer = lerp(accent, Color.White, 0.55f),
            secondary = Palette.Net, onSecondary = Color(0xFF00363C),
            secondaryContainer = Color(0xFF0D3A40), onSecondaryContainer = Color(0xFFB8F3FA),
            tertiary = Palette.Ok, onTertiary = Color(0xFF00391C),
            error = Palette.Danger, onError = Color(0xFF410002),
            errorContainer = Color(0xFF5C1A14), onErrorContainer = Color(0xFFFFDAD5),
            background = Palette.Ink, onBackground = Palette.Text,
            surface = Palette.Surface, onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceMid, onSurfaceVariant = Palette.TextMuted,
            surfaceTint = accent, outline = Palette.Outline, outlineVariant = Palette.OutlineVariant,
            scrim = Color.Black, surfaceDim = Palette.Ink, surfaceBright = Palette.SurfaceHighest,
            surfaceContainerLowest = Palette.Ink, surfaceContainerLow = Palette.SurfaceLow,
            surfaceContainer = Palette.SurfaceMid, surfaceContainerHigh = Palette.SurfaceHigh,
            surfaceContainerHighest = Palette.SurfaceHighest,
        )
        ThemeMode.LIGHT -> {
            // Darken the accent so text in that colour stays readable on white.
            val p = lerp(accent, Color.Black, 0.32f)
            val surface = Color(0xFFFFFFFF)
            lightColorScheme(
                primary = p, onPrimary = onColor(p),
                primaryContainer = lerp(surface, accent, 0.25f), onPrimaryContainer = lerp(accent, Color.Black, 0.6f),
                secondary = Color(0xFF00838F), onSecondary = Color.White,
                secondaryContainer = Color(0xFFCDEFF3), onSecondaryContainer = Color(0xFF002F34),
                tertiary = Color(0xFF1E8E4E), onTertiary = Color.White,
                error = Color(0xFFC62828), onError = Color.White,
                errorContainer = Color(0xFFFDE0DC), onErrorContainer = Color(0xFF410002),
                background = Color(0xFFF3F5F8), onBackground = Color(0xFF1A1F27),
                surface = surface, onSurface = Color(0xFF1A1F27),
                surfaceVariant = Color(0xFFE8ECF1), onSurfaceVariant = Color(0xFF5A6573),
                surfaceTint = p, outline = Color(0xFFA3ADBA), outlineVariant = Color(0xFFD3D9E1),
                scrim = Color.Black, surfaceDim = Color(0xFFE2E6EC), surfaceBright = surface,
                surfaceContainerLowest = surface, surfaceContainerLow = Color(0xFFF7F8FA),
                surfaceContainer = Color(0xFFEEF1F5), surfaceContainerHigh = Color(0xFFE6EAF0),
                surfaceContainerHighest = Color(0xFFDDE2E9),
            )
        }
        ThemeMode.HIGH_CONTRAST -> darkColorScheme(
            primary = accent, onPrimary = onColor(accent),
            primaryContainer = lerp(Color.Black, accent, 0.35f), onPrimaryContainer = Color.White,
            secondary = Color(0xFF00E5FF), onSecondary = Color.Black,
            secondaryContainer = Color(0xFF00363C), onSecondaryContainer = Color.White,
            tertiary = Color(0xFF00E676), onTertiary = Color.Black,
            error = Color(0xFFFF5252), onError = Color.Black,
            errorContainer = Color(0xFF5C0000), onErrorContainer = Color.White,
            background = Color.Black, onBackground = Color.White,
            surface = Color.Black, onSurface = Color.White,
            surfaceVariant = Color(0xFF1E1E1E), onSurfaceVariant = Color(0xFFE0E0E0),
            surfaceTint = accent, outline = Color.White, outlineVariant = Color(0xFF8A8A8A),
            scrim = Color.Black, surfaceDim = Color.Black, surfaceBright = Color(0xFF282828),
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF141414), surfaceContainerHigh = Color(0xFF1E1E1E),
            surfaceContainerHighest = Color(0xFF2A2A2A),
        )
    }
}

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

/** Board canvas colours for an appearance: background chosen by the user, outline kept visible on it. */
fun boardColorsFor(a: Appearance): BoardColors {
    val bg = Color(a.boardBackground)
    val light = bg.luminance() > 0.5f
    return BoardColors(
        background = bg,
        boardFill = Palette.Substrate,
        outline = if (light) Color(0xFF7A5F12) else Color(0xFFE8D27A),
        selected = Color(a.accent),
        net = if (light) Color(0xFF0097A7) else Palette.Net,
        highlight = if (light) Color(0xFFD5009F) else Color(0xFFFF2BD6),
    )
}

/**
 * Schematic colours for the theme: Light keeps Altium's colours and paper; Dark inverts the
 * lightness on dark paper; High contrast uses bright ink on black.
 */
fun schematicColorsFor(a: Appearance): SchematicColors {
    val accent = a.accent
    return when (a.mode) {
        ThemeMode.LIGHT -> SchematicColors(background = 0xFFE2E6EC.toInt(), paper = null, ink = SchematicColors.Ink.ORIGINAL, selected = 0xFFE08A00.toInt(), highlight = 0xFFD5009F.toInt(), net = 0xFF0097A7.toInt())
        ThemeMode.DARK -> SchematicColors(background = 0xFF0B0F14.toInt(), paper = 0xFF161C24.toInt(), ink = SchematicColors.Ink.INVERTED, selected = accent, highlight = 0xFFFF2BD6.toInt(), net = 0xFF4DD8E6.toInt())
        ThemeMode.HIGH_CONTRAST -> SchematicColors(background = 0xFF000000.toInt(), paper = 0xFF050505.toInt(), ink = SchematicColors.Ink.HIGH_CONTRAST, selected = accent, highlight = 0xFFFF2BD6.toInt(), net = 0xFF00E5FF.toInt(), missingAlpha = 0.55f)
    }
}

val LocalSchematicColors = staticCompositionLocalOf { schematicColorsFor(Appearance()) }

/** Board colours of the current theme. */
val LocalBoardColors = staticCompositionLocalOf { boardColorsFor(Appearance()) }

@Composable
fun PcbTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val scheme = remember(appearance) { schemeFor(appearance) }
    val board = remember(appearance) { boardColorsFor(appearance) }
    val schematic = remember(appearance) { schematicColorsFor(appearance) }
    CompositionLocalProvider(LocalBoardColors provides board, LocalSchematicColors provides schematic) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}
