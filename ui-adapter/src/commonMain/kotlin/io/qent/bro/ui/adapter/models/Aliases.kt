package io.qent.bro.ui.adapter.models

// UI-facing aliases that hide direct dependency on :core from the UI module.
// UI should import these types from ui-adapter instead of core.

typealias UiMcpServerConfig = io.qent.bro.core.models.McpServerConfig
typealias UiMcpServersConfig = io.qent.bro.core.models.McpServersConfig
typealias UiTransportConfig = io.qent.bro.core.models.TransportConfig
typealias UiStdioTransport = io.qent.bro.core.models.TransportConfig.StdioTransport
typealias UiHttpTransport = io.qent.bro.core.models.TransportConfig.HttpTransport
typealias UiStreamableHttpTransport = io.qent.bro.core.models.TransportConfig.StreamableHttpTransport
typealias UiWebSocketTransport = io.qent.bro.core.models.TransportConfig.WebSocketTransport
typealias UiPresetCore = io.qent.bro.core.models.Preset
typealias UiToolReference = io.qent.bro.core.models.ToolReference
// MCP client runtime and capabilities
typealias UiServerStatus = io.qent.bro.core.mcp.ServerStatus
typealias UiStarting = io.qent.bro.core.mcp.ServerStatus.Starting
typealias UiRunning = io.qent.bro.core.mcp.ServerStatus.Running
typealias UiStopping = io.qent.bro.core.mcp.ServerStatus.Stopping
typealias UiStopped = io.qent.bro.core.mcp.ServerStatus.Stopped
typealias UiError = io.qent.bro.core.mcp.ServerStatus.Error
typealias UiServerCapabilities = io.qent.bro.core.mcp.ServerCapabilities

// Configuration watcher aliases
typealias UiConfigurationWatcher = io.qent.bro.core.config.ConfigurationWatcher
typealias UiConfigurationObserver = io.qent.bro.core.config.ConfigurationObserver
