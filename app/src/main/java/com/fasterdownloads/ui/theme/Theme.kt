package com.fasterdownloads.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Highlighter-neon canvas. */
val Neon = Color(0xFFCCFF00)

/** Near-black ink used for every element and every stroke. */
val Ink = Color(0xFF0A0A0A)

private val InkMuted = Color(0x99000000)
private val Hairline = Color(0x66000000)

private val NeonScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Neon,
    secondary = Ink,
    onSecondary = Neon,
    background = Neon,
    onBackground = Ink,
    surface = Neon,
    onSurface = Ink,
    surfaceVariant = Color(0x14000000),
    onSurfaceVariant = InkMuted,
    outline = Hairline,
    outlineVariant = Color(0x33000000),
    error = Color(0xFF7A0B0B),
    onError = Neon,
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
        colorScheme = NeonScheme,
        typography = ThinTypography,
        content = content,
    )
}
