package com.karursdo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The five premium appearance palettes the user can pick (Home ▸ Appearance). Each recolours the
 * app's chrome (top bars / heroes), primary actions, chips and accents while keeping the neutral
 * surfaces consistent — and each has a legible light *and* dark variant. `swatch` is the dot shown
 * in the picker.
 */
enum class AppPalette(val label: String, val emoji: String, val swatch: Color) {
    ROYAL_NAVY("Royal Navy", "👑", Color(0xFF16315C)),
    EMERALD   ("Emerald",    "🌿", Color(0xFF059669)),
    AMETHYST  ("Amethyst",   "🔮", Color(0xFF7C3AED)),
    SUNSET    ("Sunset",     "🌅", Color(0xFFEA580C)),
    OCEAN     ("Ocean",      "🌊", Color(0xFF0891B2))
}

// Per-palette seed colours. `headerDeep` is a dark, saturated chrome colour (top bars & heroes,
// always paired with white text); the accents drive `primary` in light / dark schemes.
private data class Seed(
    val headerDeep: Color,
    val accentLight: Color,
    val accentDark: Color,
    val containerL: Color,
    val onContainerL: Color,
    val containerD: Color
)

private fun seedOf(p: AppPalette): Seed = when (p) {
    AppPalette.ROYAL_NAVY -> Seed(Color(0xFF16315C), Color(0xFF16315C), Color(0xFFA5B4FC), Color(0xFFE0E7FF), Color(0xFF3730A3), Color(0xFF283563))
    AppPalette.EMERALD    -> Seed(Color(0xFF065F46), Color(0xFF047857), Color(0xFF6EE7B7), Color(0xFFD1FAE5), Color(0xFF065F46), Color(0xFF124F3E))
    AppPalette.AMETHYST   -> Seed(Color(0xFF5B21B6), Color(0xFF7C3AED), Color(0xFFC4B5FD), Color(0xFFEDE9FE), Color(0xFF5B21B6), Color(0xFF3B2A63))
    AppPalette.SUNSET     -> Seed(Color(0xFF9A3412), Color(0xFFEA580C), Color(0xFFFDBA74), Color(0xFFFFEDD5), Color(0xFF9A3412), Color(0xFF5A2E1A))
    AppPalette.OCEAN      -> Seed(Color(0xFF0E5A6E), Color(0xFF0891B2), Color(0xFF7DD3FC), Color(0xFFCFFAFE), Color(0xFF155E75), Color(0xFF124454))
}

private fun lightSchemeFor(p: AppPalette): ColorScheme {
    val s = seedOf(p)
    return lightColorScheme(
        primary = s.accentLight, onPrimary = Color.White,
        primaryContainer = s.containerL, onPrimaryContainer = s.onContainerL,
        secondary = s.accentLight, onSecondary = Color.White,
        tertiary = Brand.Pink,
        background = Brand.Bg, onBackground = Brand.Ink,
        surface = Brand.Card, onSurface = Brand.Ink,
        surfaceVariant = Brand.Bg, onSurfaceVariant = Brand.Muted,
        outline = Brand.Line, error = Brand.Bad
    )
}

private fun darkSchemeFor(p: AppPalette): ColorScheme {
    val s = seedOf(p)
    return darkColorScheme(
        primary = s.accentDark, onPrimary = Color(0xFF0B1120),
        primaryContainer = s.containerD, onPrimaryContainer = Color(0xFFE2E8F0),
        secondary = s.accentDark, onSecondary = Color(0xFF0B1120),
        tertiary = Color(0xFFF9A8D4),
        background = Color(0xFF0B1120), onBackground = Color(0xFFE2E8F0),
        surface = Color(0xFF111A2E), onSurface = Color(0xFFE2E8F0),
        surfaceVariant = Color(0xFF1A2540), onSurfaceVariant = Color(0xFF94A3B8),
        outline = Color(0xFF2C3A57), error = Color(0xFFFCA5A5)
    )
}

/** Header / hero fill for the active palette — a subtle premium two-tone of the deep chrome colour. */
private fun headerBrushFor(p: AppPalette): Brush {
    val d = seedOf(p).headerDeep
    return Brush.linearGradient(listOf(lerp(d, Color.Black, 0.14f), d, lerp(d, Color.White, 0.10f)))
}

/** The active palette's header fill, provided down the tree so every top bar / hero recolours with
 *  the selected theme. Screens read it as `LocalHeaderBrush.current` in place of `Brand.HeaderGradient`. */
val LocalHeaderBrush = staticCompositionLocalOf<Brush> { Brand.HeaderGradient }
/** The active palette's deep chrome colour (for solid fills / status bars that need a flat colour). */
val LocalHeaderDeep = staticCompositionLocalOf { Brand.PrimaryDark }

val KsdShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),      // web cards ~16-20dp
    extraLarge = RoundedCornerShape(24.dp)
)

val KsdTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

/** Tabular figures for stat numbers, IDs and money (web app uses tnum). */
val TabularNumbers = FontFamily.Default

@Composable
fun KarurSdoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AppPalette = AppPalette.ROYAL_NAVY,
    dynamicColor: Boolean = false,   // brand palette by default; Material You opt-in
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkSchemeFor(palette)
        else -> lightSchemeFor(palette)
    }
    CompositionLocalProvider(
        LocalHeaderBrush provides headerBrushFor(palette),
        LocalHeaderDeep provides seedOf(palette).headerDeep
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = KsdShapes,
            typography = KsdTypography,
            content = content
        )
    }
}
