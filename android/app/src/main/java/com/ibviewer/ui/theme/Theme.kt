package com.ibviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// ── Brand colors matching web app ─────────────────────────────────────────────

object IbColors {
    // Light mode
    val BgPrimary        = Color(0xFFFFFFFF)
    val BgSecondary      = Color(0xFFF8F9FA)
    val BgElevated       = Color(0xFFFFFFFF)
    val TextPrimary      = Color(0xFF1A1A1A)
    val TextSecondary    = Color(0xFF495057)
    val TextTertiary     = Color(0xFF6C757D)
    val BorderMedium     = Color(0xFFDEE2E6)
    val Positive         = Color(0xFF00875A)
    val Negative         = Color(0xFFDE350B)
    val ActionPositive   = Color(0xFF4338CA)   // indigo — alloc buy
    val ActionNegative   = Color(0xFFBE185D)   // crimson — alloc sell
    val ActionNeutral    = Color(0xFF6C757D)
    val Warning          = Color(0xFFE67E22)
    val HeaderBg         = Color(0xFFDEE2E6)
    val HeaderText       = Color(0xFF1A1A1A)

    // Dark mode
    val BgPrimaryDark     = Color(0xFF0D1117)
    val BgSecondaryDark   = Color(0xFF161B22)
    val BgElevatedDark    = Color(0xFF21262D)
    val TextPrimaryDark   = Color(0xFFE6EDF3)
    val TextSecondaryDark = Color(0xFF8B949E)
    val TextTertiaryDark  = Color(0xFF6E7681)
    val BorderMediumDark  = Color(0xFF30363D)
    val PositiveDark      = Color(0xFF3DC37A)
    val NegativeDark      = Color(0xFFEE6579)
    val ActionPositiveDark = Color(0xFF85B7EB)
    val ActionNegativeDark = Color(0xFFF09595)
    val ActionNeutralDark  = Color(0xFF6E7681)
    val WarningDark        = Color(0xFFF0A500)
    val HeaderBgDark       = Color(0xFF161B22)
    val HeaderTextDark     = Color(0xFFE6EDF3)
}

// ── Extended color set exposed via CompositionLocal ───────────────────────────

data class ExtendedColors(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val borderMedium: Color,
    val positive: Color,
    val negative: Color,
    val actionPositive: Color,
    val actionNegative: Color,
    val actionNeutral: Color,
    val warning: Color,
    val headerBg: Color,
    val headerText: Color
)

val LightExtended = ExtendedColors(
    bgPrimary      = IbColors.BgPrimary,
    bgSecondary    = IbColors.BgSecondary,
    bgElevated     = IbColors.BgElevated,
    textPrimary    = IbColors.TextPrimary,
    textSecondary  = IbColors.TextSecondary,
    textTertiary   = IbColors.TextTertiary,
    borderMedium   = IbColors.BorderMedium,
    positive       = IbColors.Positive,
    negative       = IbColors.Negative,
    actionPositive = IbColors.ActionPositive,
    actionNegative = IbColors.ActionNegative,
    actionNeutral  = IbColors.ActionNeutral,
    warning        = IbColors.Warning,
    headerBg       = IbColors.HeaderBg,
    headerText     = IbColors.HeaderText
)

val DarkExtended = ExtendedColors(
    bgPrimary      = IbColors.BgPrimaryDark,
    bgSecondary    = IbColors.BgSecondaryDark,
    bgElevated     = IbColors.BgElevatedDark,
    textPrimary    = IbColors.TextPrimaryDark,
    textSecondary  = IbColors.TextSecondaryDark,
    textTertiary   = IbColors.TextTertiaryDark,
    borderMedium   = IbColors.BorderMediumDark,
    positive       = IbColors.PositiveDark,
    negative       = IbColors.NegativeDark,
    actionPositive = IbColors.ActionPositiveDark,
    actionNegative = IbColors.ActionNegativeDark,
    actionNeutral  = IbColors.ActionNeutralDark,
    warning        = IbColors.WarningDark,
    headerBg       = IbColors.HeaderBgDark,
    headerText     = IbColors.HeaderTextDark
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

// ── Material3 color schemes ───────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary          = IbColors.ActionPositive,
    onPrimary        = Color.White,
    secondary        = IbColors.Positive,
    background       = IbColors.BgPrimary,
    surface          = IbColors.BgSecondary,
    onBackground     = IbColors.TextPrimary,
    onSurface        = IbColors.TextPrimary,
    outline          = IbColors.BorderMedium,
    error            = IbColors.Negative
)

private val DarkColorScheme = darkColorScheme(
    primary          = IbColors.ActionPositiveDark,
    onPrimary        = IbColors.BgPrimaryDark,
    secondary        = IbColors.PositiveDark,
    background       = IbColors.BgPrimaryDark,
    surface          = IbColors.BgSecondaryDark,
    onBackground     = IbColors.TextPrimaryDark,
    onSurface        = IbColors.TextPrimaryDark,
    outline          = IbColors.BorderMediumDark,
    error            = IbColors.NegativeDark
)

@Composable
fun IbViewerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme  = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtended else LightExtended

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography(
                bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default)
            ),
            content = content
        )
    }
}

val MaterialTheme.ext: ExtendedColors
    @Composable get() = LocalExtendedColors.current
