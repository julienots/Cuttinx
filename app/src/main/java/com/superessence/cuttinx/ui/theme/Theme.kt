package com.superessence.cuttinx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.data.AppSettings
import com.superessence.cuttinx.data.ThemeMode

/** CUTTINX palette: white & essence yellow on deep black (SUPERESSENCE identity). */
object Cx {
    val Yellow = Color(0xFFFFCC1F)
    val Black = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val Surface2 = Color(0xFF1C1C20)
    val Surface3 = Color(0xFF26262C)
    val Line = Color(0xFF2E2E36)
    val TextDim = Color(0xFF9A9AA6)
    val Video = Color(0xFF2C4A7A)
    val Audio = Color(0xFF1F6B4F)
    val Music = Color(0xFF2A7F62)
    val Voice = Color(0xFF7A3E9D)
    val Text = Color(0xFFB8860B)
    val Subtitle = Color(0xFF8A6D1F)
    val Effect = Color(0xFF8E2C5A)
    val Danger = Color(0xFFFF5252)
    val Ok = Color(0xFF2ECC71)
}

data class CxColors(val accent: Color, val onAccent: Color, val panel: Color, val panel2: Color, val line: Color, val dim: Color, val dark: Boolean)
val LocalCx = staticCompositionLocalOf { CxColors(Cx.Yellow, Color.Black, Cx.Surface, Cx.Surface2, Cx.Line, Cx.TextDim, true) }

val CuttinxDisplay = FontFamily(Font(com.superessence.cuttinx.R.font.montserrat_extrabold, FontWeight.ExtraBold))

@Composable
fun CuttinxTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme(); else -> true }
    val accent = Color(settings.accent.argb)
    val bg = when { !dark -> Color(0xFFF6F6F8); settings.theme == ThemeMode.AMOLED -> Color.Black; else -> Cx.Black }
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.Black, secondary = accent, background = bg, surface = if (settings.theme == ThemeMode.AMOLED) Color(0xFF0B0B0C) else Cx.Surface,
        surfaceVariant = Cx.Surface2, onSurface = Color.White, onBackground = Color.White, onSurfaceVariant = Cx.TextDim, outline = Cx.Line,
        surfaceContainer = Cx.Surface2, surfaceContainerHigh = Cx.Surface3, surfaceContainerLow = Cx.Surface, error = Cx.Danger,
    ) else lightColorScheme(
        primary = Color(0xFF1A1A1A), onPrimary = accent, secondary = accent, background = bg, surface = Color.White, surfaceVariant = Color(0xFFECECF0),
        onSurface = Color(0xFF111111), onSurfaceVariant = Color(0xFF55555F), outline = Color(0xFFD5D5DD), error = Cx.Danger,
    )
    val cx = CxColors(
        accent = if (dark) accent else Color(0xFF1A1A1A), onAccent = if (dark) Color.Black else accent,
        panel = scheme.surface, panel2 = scheme.surfaceVariant, line = scheme.outline, dim = scheme.onSurfaceVariant, dark = dark,
    )
    val scale = if (settings.largeControls) 1.12f else 1f
    val t = Typography()
    val typo = Typography(
        headlineLarge = TextStyle(fontFamily = CuttinxDisplay, fontWeight = FontWeight.ExtraBold, fontSize = (30 * scale).sp),
        headlineMedium = TextStyle(fontFamily = CuttinxDisplay, fontWeight = FontWeight.ExtraBold, fontSize = (24 * scale).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = (20 * scale).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = (16 * scale).sp),
        bodyLarge = t.bodyLarge.copy(fontSize = (15 * scale).sp), bodyMedium = t.bodyMedium.copy(fontSize = (14 * scale).sp),
        bodySmall = t.bodySmall.copy(fontSize = (12 * scale).sp), labelSmall = t.labelSmall.copy(fontSize = (11 * scale).sp),
        labelMedium = t.labelMedium.copy(fontSize = (12 * scale).sp), labelLarge = t.labelLarge.copy(fontSize = (14 * scale).sp, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalCx provides cx) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}
