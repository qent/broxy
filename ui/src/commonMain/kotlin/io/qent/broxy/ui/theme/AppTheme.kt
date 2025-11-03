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
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF0B1B45),
    secondary = Color(0xFFB3B6C2),
    onSecondary = Color(0xFF191B20),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE7EAF0),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE7EAF0)
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

