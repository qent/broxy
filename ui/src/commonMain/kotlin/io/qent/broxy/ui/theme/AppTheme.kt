package io.qent.broxy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape

private val LightColors = lightColorScheme(
    primary = Color(0xFF4E7CF5),
    onPrimary = Color.White,
    secondary = Color(0xFF6C6F7D),
    onSecondary = Color.White,
    surface = Color(0xFFF9FAFB),
    onSurface = Color(0xFF1C1F24),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF20242A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6CCFF),
    onPrimary = Color(0xFF081839),
    primaryContainer = Color(0xFF314674),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFFC5CAD8),
    onSecondary = Color(0xFF12141A),
    secondaryContainer = Color(0xFF323641),
    onSecondaryContainer = Color(0xFFE3E6F2),
    surface = Color(0xFF1F242C),
    surfaceVariant = Color(0xFF2A303B),
    onSurface = Color(0xFFE4E8F2),
    onSurfaceVariant = Color(0xFFC2C6D3),
    background = Color(0xFF191E26),
    onBackground = Color(0xFFE4E8F2),
    outline = Color(0xFF454B59),
    outlineVariant = Color(0xFF353A46)
)

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()

    // Rounded shapes configurable via settings
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(20.dp)
    )

    val typography = Typography()

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = shapes,
        typography = typography,
        content = content
    )
}
