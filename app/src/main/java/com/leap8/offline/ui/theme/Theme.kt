package com.leap8.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// awesome-design-md / Apple: Action Blue, paper surfaces, quiet hairlines.
// Native Android multilingual sans preserves Chinese glyphs and IPA.
val Pine: Color @Composable get() = MaterialTheme.colorScheme.primary
val PineLight: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Gold: Color @Composable get() = MaterialTheme.colorScheme.primary
val Coral: Color @Composable get() = MaterialTheme.colorScheme.error
val Blue: Color @Composable get() = MaterialTheme.colorScheme.primary
val Mist: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val Cream = Color(0xFFF5F5F7)
val Paper = Color.White
val Ink = Color(0xFF1D1D1F)
val Slate = Color(0xFF5B5B60)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0066CC), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FC), onPrimaryContainer = Color(0xFF004990),
    secondary = Color(0xFF333333), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF2FC), onSecondaryContainer = Color(0xFF004990),
    background = Cream, onBackground = Ink, surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFF0F0F3), onSurfaceVariant = Slate,
    outline = Color(0xFF7A7A80), outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFCECEB)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF2997FF), onPrimary = Color(0xFF001C38),
    primaryContainer = Color(0xFF123452), onPrimaryContainer = Color(0xFFD7EBFF),
    secondary = Color(0xFFCCCCCC), onSecondary = Ink,
    secondaryContainer = Color(0xFF123452), onSecondaryContainer = Color(0xFFD7EBFF),
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF1D1D1F), onSurface = Color.White,
    surfaceVariant = Color(0xFF272729), onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF98989F), outlineVariant = Color(0xFF3A3A3E),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF601410)
)
private fun type(size: Int, line: Int, strong: Boolean = false) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
    fontSize = size.sp, lineHeight = line.sp
)
private val LeapTypography = Typography(
    displaySmall = type(36, 44, true), headlineLarge = type(30, 38, true),
    headlineMedium = type(26, 34, true), headlineSmall = type(23, 31, true),
    titleLarge = type(21, 29, true), titleMedium = type(17, 25, true), titleSmall = type(15, 23, true),
    bodyLarge = type(17, 26), bodyMedium = type(15, 23), bodySmall = type(14, 21),
    labelLarge = type(15, 23, true), labelMedium = type(14, 21), labelSmall = type(12, 18)
)

@Composable
fun Leap8Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = LeapTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(11.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
