# Core contracts and invariants

This document captures the public core contracts and behavioral invariants that must not change
without explicit agreement, documentation, and tests. It is the baseline for core refactoring.

## Public contracts (do not change)

- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyServer.kt`
  - `start(preset, transport)`
  - `stop()`
  - `getStatus()`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyController.kt`
  - `start(..., authorizationTimeoutSeconds, connectionRetryCount, capabilitiesRefreshIntervalSeconds, fallbackPromptsAndResourcesToTools)`
  - `stop()`, `applyPreset(...)`, `updateServers(..., authorizationTimeoutSeconds, connectionRetryCount, capabilitiesRefreshIntervalSeconds, fallbackPromptsAndResourcesToTools)`
  - `refreshServerCapabilities(serverId)`, `refreshFilteredCapabilities()`
  - timeout and retry updates (`updateCallTimeout`, `updateCapabilitiesTimeout`, `updateConnectionRetryCount`)
  - toggle updates (`updateFallbackPromptsAndResourcesToTools`)
  - capability/log/status streams (`capabilityUpdates: Flow<Map<String, ServerCapabilities>>`, `logs`, `serverStatusUpdates`)
  - factories: `createProxyController(...)`, `createStdioProxyController(...)`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyRuntimeFacade.kt`
  - `start(...)`, `stop()`, `applyPreset(...)`, `updateServers(...)`
  - `refreshServerCapabilities(serverId)`, `refreshFilteredCapabilities()`
  - timeout updates and streams (`updateCallTimeout`, `updateCapabilitiesTimeout`, `updateConnectionRetryCount`,
    `capabilityUpdates: Flow<Map<String, ServerCapabilities>>`, `serverStatusUpdates`)
  - toggle updates (`updateFallbackPromptsAndResourcesToTools`)
  - status (`isRunning`)
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpClient.kt`
  - `connect()`, `disconnect()`, `fetchCapabilities()`
  - `callTool(...)`, `getPrompt(...)`, `readResource(...)`
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpServerConnection.kt`
  - `serverId`, `config`, `status`
  - `connect()`, `disconnect()`, `getCapabilities(forceRefresh)`
  - `callTool(...)`, `getPrompt(...)`, `readResource(...)`
- `core/src/commonMain/kotlin/io/qent/broxy/core/repository/ConfigurationRepository.kt`
  - `loadMcpConfig()`, `saveMcpConfig(...)`
  - `loadPreset(id)`, `savePreset(preset)`, `listPresets()`, `deletePreset(id)`

## Behavioral invariants (do not change)

### Namespace: `serverId_toolName`

- Inbound tool names must be prefixed as `serverId_toolName`.
- For configured server IDs that start with `io.qent.broxy/`, the inbound namespace uses the
  normalized ID without this prefix.
- `DefaultNamespaceManager.parsePrefixedToolName(...)` rejects invalid formats with
  `IllegalArgumentException`.
- `DefaultRequestDispatcher` and `MultiServerClient` rely on this prefix for routing.

Files:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/NamespaceManager.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

### Allow-list enforcement

- `DefaultRequestDispatcher` enforces the allow list when it is non-empty, or when
  `allowAllWhenNoAllowedTools=false`.
- `ProxyMcpServer` always builds the dispatcher with `allowAllWhenNoAllowedTools=false`,
  so an empty allow list denies all tool calls.

Files:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

### Empty preset behavior

- `Preset.empty()` produces empty lists for tools, prompts, and resources.
- `DefaultToolFilter` produces an empty filtered capability set and empty routing maps.
- With the default dispatcher policy (`allowAllWhenNoAllowedTools=false`), tool calls are denied
  when the allowed tool set is empty.
- `getPrompt(...)` and `readResource(...)` fail when the item is not present in the filtered view.

Files:
- `core/src/commonMain/kotlin/io/qent/broxy/core/models/Preset.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

### Prompt/resource routing fallback

- When routing maps do not contain a prompt/resource, `DefaultRequestDispatcher` falls back to
  scanning downstream capabilities.
- If multiple servers expose the same prompt name or resource key, the first match returned from
  capability scanning is used (no tie-breaking beyond iteration order).

Files:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/MultiServerClient.kt`

### Streamable HTTP + SSE inbound semantics

Streamable HTTP (`/mcp`):

- `POST /mcp` accepts `Content-Type: application/json` and MCP JSON-RPC payloads.
- `mcp-session-id` header selects or creates a session; the response echoes the header.
- Requests return `200 OK` with `JSONRPCResponse`.
- Notifications/responses return `202 Accepted` with no body.
- `GET /mcp` returns `405 Method Not Allowed`.
- `DELETE /mcp` requires `mcp-session-id` and returns `204 No Content`.
- Missing/invalid headers or payloads return `400 Bad Request`.

SSE (`/sse` on the same host/port):

- `GET /sse` opens the SSE stream and advertises the `sessionId`.
- `POST /sse?sessionId=...` forwards MCP JSON-RPC into the SSE session.

Files:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
