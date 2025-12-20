package io.qent.broxy.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LightColors =
    lightColorScheme(
        // Indigo 500
        primary = Color(0xFF6366F1),
        onPrimary = Color.White,
        // Gray 500
        secondary = Color(0xFF6B7280),
        onSecondary = Color.White,
        // White
        surface = Color(0xFFFFFFFF),
        // Gray 900
        onSurface = Color(0xFF111827),
        // Gray 100
        background = Color(0xFFF3F4F6),
        // Gray 900
        onBackground = Color(0xFF111827),
        // Gray 200
        outline = Color(0xFFE5E7EB),
        // Red 500
        error = Color(0xFFEF4444),
    )

private val DarkColors =
    darkColorScheme(
        // Indigo 400
        primary = Color(0xFF818CF8),
        onPrimary = Color.White,
        // Indigo 900
        primaryContainer = Color(0xFF312E81),
        // Indigo 100
        onPrimaryContainer = Color(0xFFE0E7FF),
        // Slate 400
        secondary = Color(0xFF94A3B8),
        onSecondary = Color.White,
        // Slate 800
        secondaryContainer = Color(0xFF1E293B),
        // Slate 100
        onSecondaryContainer = Color(0xFFF1F5F9),
        // Slate 800
        surface = Color(0xFF1E293B),
        // Slate 700
        surfaceVariant = Color(0xFF334155),
        // Gray 50 (custom from user request)
        onSurface = Color(0xFFDFDFDF),
        // Slate 400
        onSurfaceVariant = Color(0xFF94A3B8),
        // Slate 900
        background = Color(0xFF0F172A),
        // Gray 50
        onBackground = Color(0xFFDFDFDF),
        // Slate 700
        outline = Color(0xFF334155),
        // Slate 800
        outlineVariant = Color(0xFF1E293B),
    )

// Custom Colors Extension
data class ExtendedColors(
    val sidebarBackground: Color,
    val success: Color,
)

val LocalExtendedColors =
    staticCompositionLocalOf {
        ExtendedColors(
            sidebarBackground = Color.White,
            success = Color.Green,
        )
    }

data class AppSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val gutter: Dp = 40.dp,
    val fab: Dp = 88.dp,
)

data class AppRadii(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val pill: Dp = 999.dp,
)

data class AppShapeTokens(
    val chip: CornerBasedShape,
    val button: CornerBasedShape,
    val input: CornerBasedShape,
    val item: CornerBasedShape,
    val card: CornerBasedShape,
    val dialog: CornerBasedShape,
    val pill: CornerBasedShape,
)

data class AppStrokeWidths(
    val hairline: Dp = 0.5.dp,
    val thin: Dp = 1.dp,
    val thick: Dp = 2.dp,
)

data class AppElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
)

data class AppLayout(
    val navigationRailWidth: Dp = 84.dp,
    val scrollbarThickness: Dp = 12.dp,
    val dialogMinWidth: Dp = 360.dp,
    val dialogMaxHeight: Dp = 420.dp,
)

private val DefaultSpacing = AppSpacing()
private val DefaultRadii = AppRadii()

private val DefaultShapes =
    AppShapeTokens(
        // 8dp
        chip = RoundedCornerShape(DefaultRadii.sm),
        // 8dp
        button = RoundedCornerShape(DefaultRadii.sm),
        // 8dp
        input = RoundedCornerShape(DefaultRadii.sm),
        // 12dp
        item = RoundedCornerShape(DefaultRadii.md),
        // 16dp
        card = RoundedCornerShape(DefaultRadii.lg),
        // 16dp
        dialog = RoundedCornerShape(DefaultRadii.lg),
        pill = RoundedCornerShape(DefaultRadii.pill),
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

    val extendedColors: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current

    val materialShapes: Shapes
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes

    @Composable
    operator fun invoke(
        themeStyle: ThemeStyle = ThemeStyle.Dark,
        content: @Composable () -> Unit,
    ) {
        val darkTheme =
            when (themeStyle) {
                ThemeStyle.Light -> false
                ThemeStyle.Dark -> true
            }
        val materialShapes =
            Shapes(
                extraSmall = RoundedCornerShape(DefaultRadii.xs),
                small = RoundedCornerShape(DefaultRadii.sm),
                medium = RoundedCornerShape(DefaultRadii.md),
                large = RoundedCornerShape(DefaultRadii.lg),
                extraLarge = RoundedCornerShape(DefaultRadii.xl),
            )

        val extendedColors =
            if (darkTheme) {
                ExtendedColors(
                    // Slate 800
                    sidebarBackground = Color(0xFF1E293B),
                    // Emerald 500
                    success = Color(0xFF10B981),
                )
            } else {
                ExtendedColors(
                    // White
                    sidebarBackground = Color(0xFFFFFFFF),
                    // Emerald 500
                    success = Color(0xFF10B981),
                )
            }

        CompositionLocalProvider(
            LocalSpacing provides DefaultSpacing,
            LocalRadii provides DefaultRadii,
            LocalShapes provides DefaultShapes,
            LocalStrokeWidths provides DefaultStrokeWidths,
            LocalElevation provides DefaultElevation,
            LocalLayout provides DefaultLayout,
            LocalExtendedColors provides extendedColors,
        ) {
            MaterialTheme(
                colorScheme = if (darkTheme) DarkColors else LightColors,
                shapes = materialShapes,
                typography = Typography(),
                content = content,
            )
        }
    }
}
