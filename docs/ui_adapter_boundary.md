# UI-adapter boundary (core dependencies)

This document captures the current `ui-adapter -> core` import inventory, highlights disputed
dependencies, and defines the target boundary for the refactor plan in `modules_refactoring.md`.

## Inventory of core imports

Categories:
- contracts/models: stable contracts and domain models intended for UI consumption
- runtime: runtime wiring, data layer, or infrastructure from `core`

| Import | Category | Notes |
| --- | --- | --- |
| io.qent.broxy.core.config.ConfigurationManager | runtime | config mutation workflow (AppStore/StoreConfigGateway) |
| io.qent.broxy.core.config.EnvironmentVariableResolver | runtime | STDIO PATH/env resolution (ToolServiceJvm) |
| io.qent.broxy.core.config.JsonConfigurationRepository | runtime | JVM repository wiring (RepositoriesJvm) |
| io.qent.broxy.core.capabilities.FilePersistedCapabilityCacheStore | runtime | shared persisted capability snapshot store (JVM) |
| io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore | contracts/models | cache store contract for app-aware preset management backend |
| io.qent.broxy.core.mcp.DefaultMcpServerConnection | runtime | JVM capability fetch fallback (ToolServiceJvm) |
| io.qent.broxy.core.mcp.PromptDescriptor | contracts/models | raw capability descriptors for UI snapshots |
| io.qent.broxy.core.mcp.ResourceDescriptor | contracts/models | raw capability descriptors for UI snapshots |
| io.qent.broxy.core.mcp.ServerCapabilities | contracts/models | raw capability payloads (ProxyRuntimeFacade + UI mapping) |
| io.qent.broxy.core.mcp.ToolDescriptor | contracts/models | raw capability descriptors for UI snapshots |
| io.qent.broxy.core.mcp.auth.AuthorizationPresenter | contracts/models | UI authorization popup integration |
| io.qent.broxy.core.mcp.auth.AuthorizationPresenterRegistry | contracts/models | presenter registration bridge (JVM) |
| io.qent.broxy.core.mcp.auth.AuthorizationRequest | contracts/models | auth request payload |
| io.qent.broxy.core.mcp.auth.AuthorizationResult | contracts/models | auth result payload |
| io.qent.broxy.core.mcp.auth.AuthorizationStatusListener | contracts/models | auth status flow from capability fetch |
| io.qent.broxy.core.mcp.auth.OAuthState | contracts/models | OAuth state snapshot |
| io.qent.broxy.core.mcp.auth.OAuthStateStore | contracts/models | OAuth cache storage (JVM) |
| io.qent.broxy.core.mcp.auth.resolveOAuthResourceUrl | contracts/models | OAuth resource resolution |
| io.qent.broxy.core.mcp.auth.restoreFrom | contracts/models | restore OAuth state |
| io.qent.broxy.core.mcp.auth.toSnapshot | contracts/models | persist OAuth state |
| io.qent.broxy.core.models.AuthConfig | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.McpServerConfig | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.McpServersConfig | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.Preset | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.PromptReference | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.ResourceReference | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.ToolReference | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.models.TransportConfig | contracts/models | mapper-only (UI <-> core) |
| io.qent.broxy.core.presetmanagement.* | contracts/models + runtime | preset-management backend contracts and JVM implementation wiring |
| io.qent.broxy.core.proxy.runtime.ProxyController | runtime | JVM app-store factory wiring |
| io.qent.broxy.core.proxy.runtime.ProxyLifecycle | runtime | JVM app-store factory wiring |
| io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade | contracts/models | facade for UI/runtime calls |
| io.qent.broxy.core.proxy.runtime.ProxyRuntimeSdkFacade | runtime | JVM SDK adapter for remote sessions |
| io.qent.broxy.core.proxy.runtime.ServerConnectionStatus | contracts/models | runtime status enum |
| io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate | contracts/models | runtime status stream payload |
| io.qent.broxy.core.proxy.runtime.createProxyController | runtime | JVM app-store factory wiring |
| io.qent.broxy.core.repository.ConfigurationRepository | contracts/models | config persistence interface |
| io.qent.broxy.core.utils.AppCacheDir | runtime | cache path for capability persistence |
| io.qent.broxy.core.utils.CollectingLogger | runtime | logging stream for UI |
| io.qent.broxy.core.utils.CommandLocator | runtime | STDIO command discovery |
| io.qent.broxy.core.utils.CompositeLogger | runtime | JVM logging wiring |
| io.qent.broxy.core.utils.ConsoleLogger | runtime | JVM logging wiring |
| io.qent.broxy.core.utils.DailyFileLogger | runtime | JVM logging wiring |
| io.qent.broxy.core.utils.Logger | runtime | logging interface |

## Target boundary (post-refactor)

`ui-adapter` depends on `core` through stable contracts plus narrowly scoped JVM wiring:

- `io.qent.broxy.core.repository` for configuration persistence interfaces.
- `io.qent.broxy.core.mcp` (raw capabilities) and `io.qent.broxy.core.mcp.auth` for auth contracts.
- `io.qent.broxy.core.proxy.runtime.ProxyRuntimeFacade` for proxy start/stop/refresh, plus
  `ServerConnectionStatus`/`ServerConnectionUpdate` for status streams.
- `io.qent.broxy.core.models` only inside UI <-> core mapper code.
- `io.qent.broxy.core.capabilities` for shared persisted capability snapshot contracts/storage.
- `io.qent.broxy.core.presetmanagement` for preset-management backend contracts and app-aware wiring.
- `io.qent.broxy.core.utils` for logging interfaces; JVM wiring uses logging sinks and cache helpers.
- `io.qent.broxy.core.config` only in AppStore/store intent config gateway and JVM wiring
  (`EnvironmentVariableResolver`, `JsonConfigurationRepository`).

Notes:

- UI config/preset DTOs (`UiMcpServerConfig`, `UiMcpServersConfig`, `UiTransportConfig`, `UiPresetCore`)
  live in `ui-adapter` with `toCore()`/`toUi()` mappers; `core.models` should be used only in the
  mapper layer.
- UI capability snapshots/cache should live in `ui-adapter` (Phase 3).
- Direct access to `ProxyMcpServer` or inbound SDK builders is not allowed in the target boundary (Phase 1).

## Guardrail allowlist (Phase 0)

The `:ui-adapter:checkUiAdapterCoreBoundary` task enforces that all `io.qent.broxy.core.*` imports
stay within the current baseline package allowlist. The shared allowlist is narrow, and
JVM/config wiring uses file-specific exceptions:

- `io.qent.broxy.core.mcp`
- `io.qent.broxy.core.models`
- `io.qent.broxy.core.capabilities`
- `io.qent.broxy.core.presetmanagement`
- `io.qent.broxy.core.proxy.runtime`
- `io.qent.broxy.core.repository`
- `io.qent.broxy.core.utils`

File-specific exceptions:
- `src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt` -> `io.qent.broxy.core.config`
- `src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/AppStoreIntents.kt` -> `io.qent.broxy.core.config`
- `src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/StoreConfigGateway.kt` -> `io.qent.broxy.core.config`
- `src/jvmMain/kotlin/io/qent/broxy/ui/adapter/data/RepositoriesJvm.kt` -> `io.qent.broxy.core.config`
- `src/jvmMain/kotlin/io/qent/broxy/ui/adapter/services/ToolServiceJvm.kt` -> `io.qent.broxy.core.config`
