package com.joystream.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val JoyPurple = Color(0xFF7C5CFF)
val JoyPink = Color(0xFFFF4D8D)
val JoyBackground = Color(0xFF0B0B10)
val JoySurface = Color(0xFF14141C)
val JoySurfaceHigh = Color(0xFF1E1E29)
val JoyOnSurface = Color(0xFFF3F3F8)
val JoyMuted = Color(0xFF9A9AAE)
val JoyGreen = Color(0xFF3ED598)
val JoyAmber = Color(0xFFFFC44D)

private val JoyColors = darkColorScheme(
    primary = JoyPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFDDD6FF),
    secondary = JoyPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A1D2C),
    onSecondaryContainer = Color(0xFFFFD8E5),
    tertiary = JoyGreen,
    background = JoyBackground,
    onBackground = JoyOnSurface,
    surface = JoySurface,
    onSurface = JoyOnSurface,
    surfaceVariant = JoySurfaceHigh,
    onSurfaceVariant = JoyMuted,
    outline = Color(0xFF2E2E3D),
    outlineVariant = Color(0xFF23232F),
    error = Color(0xFFFF6B6B),
    onError = Color.White,
)

private val JoyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val JoyTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun JoyStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JoyColors,
        shapes = JoyShapes,
        typography = JoyTypography,
        content = content,
    )
}
