package io.qent.broxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

data class AppSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val gutter: Dp = 40.dp
)

data class AppRadii(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val pill: Dp = 999.dp
)

data class AppShapeTokens(
    val chip: CornerBasedShape,
    val surfaceSm: CornerBasedShape,
    val surfaceMd: CornerBasedShape,
    val surfaceLg: CornerBasedShape,
    val dialog: CornerBasedShape,
    val pill: CornerBasedShape
)

data class AppStrokeWidths(
    val hairline: Dp = 0.5.dp,
    val thin: Dp = 1.dp,
    val thick: Dp = 2.dp
)

data class AppElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp
)

data class AppLayout(
    val navigationRailWidth: Dp = 80.dp,
    val scrollbarThickness: Dp = 12.dp,
    val dialogMinWidth: Dp = 360.dp,
    val dialogMaxHeight: Dp = 420.dp
)

private val DefaultSpacing = AppSpacing()
private val DefaultRadii = AppRadii()
private val DefaultShapes = AppShapeTokens(
    chip = RoundedCornerShape(DefaultRadii.sm),
    surfaceSm = RoundedCornerShape(DefaultRadii.md),
    surfaceMd = RoundedCornerShape(DefaultRadii.lg),
    surfaceLg = RoundedCornerShape(DefaultRadii.xl),
    dialog = RoundedCornerShape(DefaultRadii.lg),
    pill = RoundedCornerShape(DefaultRadii.pill)
)
private val DefaultStrokeWidths = AppStrokeWidths()
private val DefaultElevation = AppElevation()
private val DefaultLayout = AppLayout()

private val LocalSpacing = staticCompositionLocalOf { DefaultSpacing }
private val LocalRadii = staticCompositionLocalOf { DefaultRadii }
private val LocalShapes = staticCompositionLocalOf { DefaultShapes }
private val LocalStrokeWidths = staticCompositionLocalOf { DefaultStrokeWidths }
private val LocalElevation = staticCompositionLocalOf { DefaultElevation }
private val LocalLayout = staticCompositionLocalOf { DefaultLayout }

object AppTheme {
    val spacing: AppSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val radii: AppRadii
        @Composable
        @ReadOnlyComposable
        get() = LocalRadii.current

    val shapes: AppShapeTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalShapes.current

    val strokeWidths: AppStrokeWidths
        @Composable
        @ReadOnlyComposable
        get() = LocalStrokeWidths.current

    val elevation: AppElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalElevation.current

    val layout: AppLayout
        @Composable
        @ReadOnlyComposable
        get() = LocalLayout.current

    val colors
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography

    val materialShapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes

    @Composable
    operator fun invoke(
        themeStyle: ThemeStyle = ThemeStyle.System,
        content: @Composable () -> Unit
    ) {
        val darkTheme = when (themeStyle) {
            ThemeStyle.System -> isSystemInDarkTheme()
            ThemeStyle.Light -> false
            ThemeStyle.Dark -> true
        }
        val materialShapes = Shapes(
            extraSmall = RoundedCornerShape(DefaultRadii.xs),
            small = RoundedCornerShape(DefaultRadii.sm),
            medium = RoundedCornerShape(DefaultRadii.md),
            large = RoundedCornerShape(DefaultRadii.lg),
            extraLarge = RoundedCornerShape(DefaultRadii.xl)
        )

        CompositionLocalProvider(
            LocalSpacing provides DefaultSpacing,
            LocalRadii provides DefaultRadii,
            LocalShapes provides DefaultShapes,
            LocalStrokeWidths provides DefaultStrokeWidths,
            LocalElevation provides DefaultElevation,
            LocalLayout provides DefaultLayout
        ) {
            MaterialTheme(
                colorScheme = if (darkTheme) DarkColors else LightColors,
                shapes = materialShapes,
                typography = Typography(),
                content = content
            )
        }
    }
}
