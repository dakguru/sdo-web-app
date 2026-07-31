package com.karursdo.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand tokens sampled from the existing web app (the Leave Orders HTML pages' :root variables).
 * Do not tweak — these must match the web tool exactly.
 */
object Brand {
    // Rich dark navy blue used across banners, primary actions and nav (no gradients).
    val PrimaryDark = Color(0xFF16315C)   // deep navy blue
    val PrimaryDarker = Color(0xFF0E2244) // darker navy for accents/top bars
    val ScreenBg = Color(0xFFF4F6FA)      // light app background

    val Navy = Color(0xFF1E3A5F)          // header gradient start / hero gradient start
    val NavyDeep = Color(0xFF1F3864)      // report letterhead navy
    // Former accent hues now map to the single uniform dark (per design: no gradients, uniform dark).
    val Indigo = PrimaryDark
    val Violet = PrimaryDark
    val Pink = Color(0xFFEC4899)
    val Teal = PrimaryDark
    val Emerald = Color(0xFF059669)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFE11D48)
    val Orange = Color(0xFFF97316)

    val Bg = Color(0xFFEEF2FB)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0F172A)
    val Ink2 = Color(0xFF1E293B)
    val Muted = Color(0xFF64748B)
    val Line = Color(0xFFE2E8F0)
    val Good = Color(0xFF059669)
    val Warn = Color(0xFFD97706)
    val Bad = Color(0xFFDC2626)

    // Badge palette (exact)
    val BadgeDsBg = Color(0xFFE0E7FF);   val BadgeDsFg = Color(0xFF3730A3)
    val BadgeGdsBg = Color(0xFFCCFBF1);  val BadgeGdsFg = Color(0xFF0F766E)
    val BadgeOutBg = Color(0xFFFEF9C3);  val BadgeOutFg = Color(0xFF854D0E)
    val BadgeTelBg = Color(0xFFDCFCE7);  val BadgeTelFg = Color(0xFF166534)
    val ChipPaidBg = Color(0xFFDCFCE7);  val ChipPaidFg = Color(0xFF166534)
    val ChipOtherBg = Color(0xFFFEF3C7); val ChipOtherFg = Color(0xFF92400E)
    val CntFg = Color(0xFF3730A3);       val CntBg = Color(0xFFE0E7FF)
    val TpBpoBg = Color(0xFFDCFCE7);     val TpBpoFg = Color(0xFF166534)
    val TpSpoBg = Color(0xFFE0E7FF);     val TpSpoFg = Color(0xFF3730A3)
    val TpSdoBg = Color(0xFFFEF9C3);     val TpSdoFg = Color(0xFF854D0E)
    val TpHpoBg = Color(0xFFFEE2E2);     val TpHpoFg = Color(0xFF991B1B)
    val TpOthBg = Color(0xFFF1F5F9);     val TpOthFg = Color(0xFF475569)

    // India Post mark (sampled from the official asset)
    val IndiaPostRed = Color(0xFFE4181C)
    val IndiaPostGold = Color(0xFFFDB913)

    // Solid fills (single colour at both stops) so nothing renders as a gradient.
    val HeaderGradient = Brush.linearGradient(0f to PrimaryDark, 1f to PrimaryDark)
    val BrandGradient = Brush.linearGradient(0f to PrimaryDark, 1f to PrimaryDark)
    val HeroGradient = Brush.linearGradient(0f to PrimaryDark, 1f to PrimaryDark)
    val EarnGradient = Brush.linearGradient(0f to Color(0xFF0F766E), 1f to Color(0xFF0F766E)) // solid teal-dark
    val DedGradient = Brush.linearGradient(0f to Color(0xFF9F1239), 1f to Color(0xFF9F1239))  // solid rose-dark
    val NetGradient = Brush.linearGradient(0f to PrimaryDark, 1f to PrimaryDark)
}
