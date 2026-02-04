# Core contracts checklist

This checklist mirrors `docs/core_contracts.md` and is intended to be used as a quick baseline
when refactoring core internals.

## Public interfaces (signatures)

- [ ] `ProxyServer`
  - [ ] `start(preset, transport)`
  - [ ] `stop()`
  - [ ] `getStatus()`
- [ ] `ProxyController`
  - [ ] `start(..., authorizationTimeoutSeconds, connectionRetryCount, capabilitiesRefreshIntervalSeconds, fallbackPromptsAndResourcesToTools)`
  - [ ] `stop()`
  - [ ] `applyPreset(...)`
  - [ ] `updateServers(..., authorizationTimeoutSeconds, connectionRetryCount, capabilitiesRefreshIntervalSeconds, fallbackPromptsAndResourcesToTools)`
  - [ ] `refreshServerCapabilities(serverId)`
  - [ ] `refreshFilteredCapabilities()`
  - [ ] `updateCallTimeout(...)`
  - [ ] `updateCapabilitiesTimeout(...)`
  - [ ] `updateConnectionRetryCount(...)`
  - [ ] `updateFallbackPromptsAndResourcesToTools(...)`
  - [ ] `capabilityUpdates: Flow<Map<String, ServerCapabilities>>`
  - [ ] `logs`
  - [ ] `serverStatusUpdates`
  - [ ] `createProxyController(...)`
  - [ ] `createStdioProxyController(...)`
- [ ] `ProxyRuntimeFacade`
  - [ ] `start(...)`
  - [ ] `stop()`
  - [ ] `applyPreset(...)`
  - [ ] `updateServers(...)`
  - [ ] `refreshServerCapabilities(serverId)`
  - [ ] `refreshFilteredCapabilities()`
  - [ ] `updateCallTimeout(...)`
  - [ ] `updateCapabilitiesTimeout(...)`
  - [ ] `updateConnectionRetryCount(...)`
  - [ ] `updateFallbackPromptsAndResourcesToTools(...)`
  - [ ] `capabilityUpdates: Flow<Map<String, ServerCapabilities>>`
  - [ ] `serverStatusUpdates`
  - [ ] `isRunning`
- [ ] `McpClient`
  - [ ] `connect()`
  - [ ] `disconnect()`
  - [ ] `fetchCapabilities()`
  - [ ] `callTool(...)`
  - [ ] `getPrompt(...)`
  - [ ] `readResource(...)`
- [ ] `McpServerConnection`
  - [ ] `serverId`
  - [ ] `config`
  - [ ] `status`
  - [ ] `connect()`
  - [ ] `disconnect()`
  - [ ] `getCapabilities(forceRefresh)`
  - [ ] `callTool(...)`
  - [ ] `getPrompt(...)`
  - [ ] `readResource(...)`
- [ ] `ConfigurationRepository`
  - [ ] `loadMcpConfig()`
  - [ ] `saveMcpConfig(...)`
  - [ ] `loadPreset(id)`
  - [ ] `savePreset(preset)`
  - [ ] `listPresets()`
  - [ ] `deletePreset(id)`

## Behavioral invariants

- [ ] Namespace contract: tool names must be prefixed as `serverId_tool` and invalid formats are rejected.
- [ ] Allow-list enforcement: empty allow list denies tool calls when `allowAllWhenNoAllowedTools=false`.
- [ ] Empty preset: empty tool/prompt/resource lists yield empty filtered capabilities and deny tool calls.
- [ ] Prompt/resource fallback: when routing maps miss, dispatcher scans downstream capabilities and uses
      the first match in iteration order.
- [ ] Streamable HTTP + SSE inbound semantics remain unchanged.
