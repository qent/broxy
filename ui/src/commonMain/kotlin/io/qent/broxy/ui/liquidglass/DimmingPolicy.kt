package io.qent.broxy.ui.liquidglass

enum class DimmingPolicy {
    Auto,
    Always,
    Never,
}

enum class GlassBackgroundScenario {
    App,
    Bright,
    Dark,
    Noisy,
}

internal fun GlassBackgroundScenario.requiresAutoDimming(): Boolean =
    this == GlassBackgroundScenario.Bright || this == GlassBackgroundScenario.Noisy
