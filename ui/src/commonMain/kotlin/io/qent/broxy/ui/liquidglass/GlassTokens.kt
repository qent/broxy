package io.qent.broxy.ui.liquidglass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GlassTokens(
    val cornerRadius: Dp = 16.dp,
    val strokeWidth: Dp = 1.dp,
    val clearAlpha: Float = 0.42f,
    val regularAlpha: Float = 0.55f,
    val pressedBoost: Float = 0.12f,
    val hoverBoost: Float = 0.06f,
    val reducedTransparencyAlpha: Float = 0.92f,
    val highlightAlpha: Float = 0.16f,
    val reducedHighlightAlpha: Float = 0.06f,
    val shadowElevation: Dp = 10.dp,
    val animationMillis: Int = 180,
)

enum class GlassSurfaceVariant {
    Clear,
    Regular,
}
