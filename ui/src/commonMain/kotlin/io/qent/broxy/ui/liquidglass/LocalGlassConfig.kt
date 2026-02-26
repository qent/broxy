@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalGlassConfig =
    staticCompositionLocalOf {
        GlassConfig(
            glassEnabled = false,
            reduceTransparency = true,
            reduceMotion = false,
            dimmingPolicy = DimmingPolicy.Never,
            vibrancyEnabled = false,
        )
    }

@Composable
fun ProvideGlassConfig(
    config: GlassConfig,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGlassConfig provides config, content = content)
}
