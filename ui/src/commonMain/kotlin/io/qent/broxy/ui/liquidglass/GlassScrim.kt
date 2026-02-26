@file:Suppress("FunctionNaming", "MagicNumber")

package io.qent.broxy.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun GlassScrim(
    scenario: GlassBackgroundScenario,
    modifier: Modifier = Modifier,
) {
    val config = LocalGlassConfig.current
    val shouldDim =
        when (config.dimmingPolicy) {
            DimmingPolicy.Always -> true
            DimmingPolicy.Never -> false
            DimmingPolicy.Auto -> scenario.requiresAutoDimming()
        }
    if (!config.glassEnabled || !shouldDim) return

    val alpha =
        when {
            config.reduceTransparency -> 0.22f
            scenario == GlassBackgroundScenario.Noisy -> 0.30f
            scenario == GlassBackgroundScenario.Bright -> 0.22f
            else -> 0.18f
        }
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)),
    )
}
