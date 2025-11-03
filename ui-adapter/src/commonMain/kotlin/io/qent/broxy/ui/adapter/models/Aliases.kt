package io.qent.broxy.ui.adapter.models

// UI-facing aliases that hide direct dependency on :core from the UI module.
// UI should import these types from ui-adapter instead of core.

typealias UiMcpServerConfig = io.qent.broxy.core.models.McpServerConfig
typealias UiMcpServersConfig = io.qent.broxy.core.models.McpServersConfig
typealias UiTransportConfig = io.qent.broxy.core.models.TransportConfig
typealias UiStdioTransport = io.qent.broxy.core.models.TransportConfig.StdioTransport
typealias UiHttpTransport = io.qent.broxy.core.models.TransportConfig.HttpTransport
typealias UiStreamableHttpTransport = io.qent.broxy.core.models.TransportConfig.StreamableHttpTransport
typealias UiWebSocketTransport = io.qent.broxy.core.models.TransportConfig.WebSocketTransport
typealias UiPresetCore = io.qent.broxy.core.models.Preset
typealias UiToolReference = io.qent.broxy.core.models.ToolReference
// MCP client runtime and capabilities
typealias UiServerStatus = io.qent.broxy.core.mcp.ServerStatus
typealias UiStarting = io.qent.broxy.core.mcp.ServerStatus.Starting
typealias UiRunning = io.qent.broxy.core.mcp.ServerStatus.Running
typealias UiStopping = io.qent.broxy.core.mcp.ServerStatus.Stopping
typealias UiStopped = io.qent.broxy.core.mcp.ServerStatus.Stopped
typealias UiError = io.qent.broxy.core.mcp.ServerStatus.Error
typealias UiServerCapabilities = io.qent.broxy.core.mcp.ServerCapabilities

// Configuration watcher aliases
typealias UiConfigurationWatcher = io.qent.broxy.core.config.ConfigurationWatcher
typealias UiConfigurationObserver = io.qent.broxy.core.config.ConfigurationObserver
