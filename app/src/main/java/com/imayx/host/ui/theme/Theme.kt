package com.imayx.host.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.imayx.host.data.ThemeMode

data class IxColors(
    val bg: Color,
    val card: Color,
    val card2: Color,
    val border: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val text: Color,
    val soft: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val isLight: Boolean
)

private val Amoled = IxColors(
    bg = Color(0xFF000000),
    card = Color(0xFF080808),
    card2 = Color(0xFF0B0B0B),
    border = Color(0xFF1C1C22),
    accent = Color(0xFF8B5CF6),
    accentDeep = Color(0xFF6D3FE0),
    onAccent = Color(0xFFFFFFFF),
    text = Color(0xFFF4F4F7),
    soft = Color(0xFF9A9AA8),
    ok = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    isLight = false
)

private val DarkMode = Amoled.copy(
    bg = Color(0xFF0E0E12),
    card = Color(0xFF16161C),
    card2 = Color(0xFF1B1B22),
    border = Color(0xFF2A2A33)
)

private val LightMode = IxColors(
    bg = Color(0xFFF6F6FA),
    card = Color(0xFFFFFFFF),
    card2 = Color(0xFFF0F0F6),
    border = Color(0xFFE0E0EA),
    accent = Color(0xFF7C3AED),
    accentDeep = Color(0xFF6D28D9),
    onAccent = Color(0xFFFFFFFF),
    text = Color(0xFF15151C),
    soft = Color(0xFF666675),
    ok = Color(0xFF059669),
    warn = Color(0xFFB45309),
    danger = Color(0xFFDC2626),
    isLight = true
)

val LocalIx = staticCompositionLocalOf { Amoled }

object Ix {
    val c: IxColors
        @Composable
        @ReadOnlyComposable
        get() = LocalIx.current
}

private val IxTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp)
)

@Composable
fun IxTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val c = when (mode) {
        ThemeMode.AMOLED -> Amoled
        ThemeMode.DARK -> DarkMode
        ThemeMode.LIGHT -> LightMode
    }
    val scheme = if (c.isLight) {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.card, onSurface = c.text, surfaceVariant = c.card2, onSurfaceVariant = c.soft,
            outline = c.border, error = c.danger,
            surfaceContainer = c.card2, surfaceContainerHigh = c.card2, surfaceContainerHighest = c.card2,
            surfaceContainerLow = c.card, surfaceContainerLowest = c.bg
        )
    } else {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.card, onSurface = c.text, surfaceVariant = c.card2, onSurfaceVariant = c.soft,
            outline = c.border, error = c.danger,
            surfaceContainer = c.card2, surfaceContainerHigh = c.card2, surfaceContainerHighest = c.card2,
            surfaceContainerLow = c.card, surfaceContainerLowest = c.bg
        )
    }
    CompositionLocalProvider(LocalIx provides c) {
        MaterialTheme(colorScheme = scheme, typography = IxTypography, content = content)
    }
}
