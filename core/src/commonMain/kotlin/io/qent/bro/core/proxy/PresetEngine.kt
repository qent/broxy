package io.qent.bro.core.proxy

import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.models.Preset

/**
 * Applies preset rules to downstream capabilities to produce a filtered view
 * and routing metadata.
 */
interface PresetEngine {
    fun apply(all: Map<String, ServerCapabilities>, preset: Preset): FilterResult
}

class DefaultPresetEngine(
    private val filter: ToolFilter = DefaultToolFilter()
) : PresetEngine {
    override fun apply(all: Map<String, ServerCapabilities>, preset: Preset): FilterResult =
        filter.filter(all, preset)
}

