package com.fasterdownloads.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Highlighter-neon. */
val Neon = Color(0xFFCCFF00)

/** Near-black ink. */
val Ink = Color(0xFF0A0A0A)

/**
 * Light: neon canvas, black ink.
 * Dark: black canvas, neon ink - the same two colours with their roles swapped.
 */
private val NeonLightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Neon,
    secondary = Ink,
    onSecondary = Neon,
    background = Neon,
    onBackground = Ink,
    surface = Neon,
    onSurface = Ink,
    surfaceVariant = Color(0x14000000),
    onSurfaceVariant = Color(0x99000000),
    outline = Color(0x80000000),
    outlineVariant = Color(0x33000000),
    error = Color(0xFF7A0B0B),
    onError = Neon,
)

private val NeonDarkScheme = darkColorScheme(
    primary = Neon,
    onPrimary = Ink,
    secondary = Neon,
    onSecondary = Ink,
    background = Ink,
    onBackground = Neon,
    surface = Ink,
    onSurface = Neon,
    surfaceVariant = Color(0x1FCCFF00),
    onSurfaceVariant = Color(0xB3CCFF00),
    outline = Color(0x99CCFF00),
    outlineVariant = Color(0x40CCFF00),
    error = Color(0xFFFF6B6B),
    onError = Ink,
)

/** Thin, wide-tracking type: light weights everywhere, generous letterspacing on labels. */
private val ThinTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 40.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 30.sp,
        letterSpacing = (-0.6).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 17.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 12.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        letterSpacing = 2.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    ),
)

@Composable
fun FasterDownloadsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) NeonDarkScheme else NeonLightScheme,
        typography = ThinTypography,
        content = content,
    )
}
