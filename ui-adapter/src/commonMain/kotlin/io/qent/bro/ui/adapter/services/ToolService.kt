package io.qent.bro.ui.adapter.services

import io.qent.bro.ui.adapter.models.UiMcpServerConfig
import io.qent.bro.ui.adapter.models.UiServerCapabilities

/**
 * Provides access to server tools/capabilities for UI components.
 * Implementations live per-platform.
 */
expect suspend fun fetchServerCapabilities(config: UiMcpServerConfig): Result<UiServerCapabilities>

