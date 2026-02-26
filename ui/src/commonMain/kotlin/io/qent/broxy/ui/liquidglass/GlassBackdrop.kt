@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package io.qent.broxy.ui.liquidglass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun GlassBackdrop(
    scenario: GlassBackgroundScenario,
    modifier: Modifier = Modifier,
) {
    val baseBrush =
        when (scenario) {
            GlassBackgroundScenario.Bright ->
                Brush.linearGradient(
                    listOf(
                        Color(0xFFF7F3E7),
                        Color(0xFFE8F2FF),
                        Color(0xFFFDECEC),
                    ),
                    start = Offset.Zero,
                    end = Offset(1800f, 1800f),
                )

            GlassBackgroundScenario.Dark ->
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E293B),
                        Color(0xFF111827),
                    ),
                    start = Offset.Zero,
                    end = Offset(1600f, 1400f),
                )

            GlassBackgroundScenario.Noisy ->
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFFF3C1),
                        Color(0xFFDDEBFF),
                        Color(0xFFF8D5E4),
                        Color(0xFFD5F5E3),
                    ),
                    center = Offset(500f, 300f),
                    radius = 1600f,
                )

            GlassBackgroundScenario.App ->
                Brush.linearGradient(
                    listOf(
                        AppTheme.colors.background,
                        AppTheme.colors.surfaceVariant,
                    ),
                    start = Offset.Zero,
                    end = Offset(1400f, 1200f),
                )
        }

    Box(
        modifier = modifier.fillMaxSize().background(baseBrush),
    ) {
        if (scenario == GlassBackgroundScenario.Noisy) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stripeColor = Color.White.copy(alpha = 0.08f)
                val step = 34f
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        color = stripeColor,
                        start = Offset(x, 0f),
                        end = Offset(x + size.height, size.height),
                        strokeWidth = 1.2f,
                    )
                    x += step
                }
            }
        }
    }
}
