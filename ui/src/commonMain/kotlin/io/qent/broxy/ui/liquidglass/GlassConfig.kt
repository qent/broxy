package io.qent.broxy.ui.liquidglass

import androidx.compose.runtime.Immutable

@Immutable
data class GlassConfig(
    val glassEnabled: Boolean,
    val reduceTransparency: Boolean,
    val reduceMotion: Boolean,
    val dimmingPolicy: DimmingPolicy,
    val vibrancyEnabled: Boolean,
    val tokens: GlassTokens = GlassTokens(),
)

fun defaultGlassConfig(
    isMacOs: Boolean,
    systemReduceTransparency: Boolean = false,
): GlassConfig {
    val baseEnabled = isMacOs && !systemReduceTransparency
    return GlassConfig(
        glassEnabled = baseEnabled,
        reduceTransparency = systemReduceTransparency,
        reduceMotion = false,
        dimmingPolicy = DimmingPolicy.Auto,
        vibrancyEnabled = baseEnabled,
    )
}
