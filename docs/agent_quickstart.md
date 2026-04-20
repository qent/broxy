# Agent Quickstart (Codex-Oriented)

## Purpose

A fast, decision-oriented entrypoint for AI code agents and maintainers. Use this file to map code changes
into the correct docs and checks before editing implementation.

## When to read

- Before starting any code or contract change.
- Before finalizing a PR that touched runtime behavior, routing, config, auth, or capability surfaces.

## Source-of-truth files

- `AGENTS.md`
- `docs/readme.md`
- `docs/core_contracts.md`
- `docs/testing.md`

## Behavior contract

This quickstart is a routing layer. Canonical behavior definitions remain in Level 2 subsystem docs.

## If You Change X -> Read/Update Y

| Subsystem change | Read first | Update before merge |
| --- | --- | --- |
| Tool namespace, dispatch, filtering | `docs/proxy_facade.md`, `docs/core_contracts.md` | `docs/proxy_facade.md`, `docs/core_contracts.md` |
| Preset semantics, built-ins, allow-lists | `docs/presets_and_filtering.md` | `docs/presets_and_filtering.md`, `docs/core_contracts.md` |
| HTTP/SSE inbound routes, sessions, status codes | `docs/inbound_transports.md` | `docs/inbound_transports.md`, `docs/proxy_facade.md` |
| Downstream clients, timeouts, cache/fallback | `docs/downstream_mcp_connections.md` | `docs/downstream_mcp_connections.md` |
| OAuth, auth challenges, secure storage | `docs/remote_auth_and_websocket.md` | `docs/remote_auth_and_websocket.md` |
| Remote preset WS notifications | `docs/websocket_preset_capabilities.md` | `docs/websocket_preset_capabilities.md`, `docs/remote_auth_and_websocket.md` |
| `config.json` / `mcp.json` / placeholder behavior | `docs/configuration_and_hot_reload.md` | `docs/configuration_and_hot_reload.md`, `docs/claude_code_mcp_format.md` |
| UI capability snapshots/refresh/statuses | `docs/capabilities_cache_and_ui_refresh.md` | `docs/capabilities_cache_and_ui_refresh.md` |
| AI client connectors and import UX | `docs/ai_clients.md` | `docs/ai_clients.md` |
| Registry/catalog install planner flow | `docs/mcp_catalog.md` | `docs/mcp_catalog.md` |
| Icon matching/cache behavior | `docs/server_icons.md` | `docs/server_icons.md` |
| Logging event names/payload shape | `docs/logging_and_observability.md` | `docs/logging_and_observability.md` |
| CLI runtime/startup/hot-reload | `docs/cli_mode.md` | `docs/cli_mode.md` |

## Canonical Invariants

- Tool namespace is `serverId_toolName` (underscore separator). Colon-separated namespace formats are invalid.
- Preset semantics:
  - tools are explicit allow-list;
  - `prompts/resources = null` means unrestricted within in-scope servers;
  - `prompts/resources = []` means deny all.
- Inbound session binding:
  - `/mcp` and `/sse` track active preset;
  - `/mcp/{presetId}` and `/sse/{presetId}` are preset-pinned per session;
  - cross-route/session rebinding is rejected with `409 Conflict`.
- Config source-of-truth split:
  - `config.json`: global runtime settings;
  - `mcp.json`: server definitions;
  - `ui.json`: UI-only settings.

## Canonical Commands / Checks

| Goal | Command |
| --- | --- |
| Full build | `./gradlew build` |
| All tests | `./gradlew testAll` |
| CLI integration tests | `./gradlew :cli:integrationTest` |
| Test MCP server self-check | `./gradlew :test-mcp-server:selfCheck --console=plain` |
| Formatting/static checks | `./gradlew ktlintCheck detekt` |
| UI-adapter boundary guard | `./gradlew :ui-adapter:checkUiAdapterCoreBoundary` |

## High-Signal Code Entrypoints

Core:

- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt`

Inbound / SDK wiring (JVM):

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundRoutes.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

Configuration:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigMapper.kt`

UI-adapter:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/ProxyRuntime.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilityRefresher.kt`

UI:

- `ui/src/commonMain/kotlin/io/qent/broxy/ui/screens/MainWindow.kt`
- `ui/src/commonMain/kotlin/io/qent/broxy/ui/screens/CatalogScreen.kt`
- `ui/src/commonMain/kotlin/io/qent/broxy/ui/components/AppNavigation.kt`

CLI:

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommandRunner.kt`

Server registry:

- `server-registry/src/commonMain/kotlin/io/qent/broxy/registry/catalog/CatalogInstallPlanner.kt`
- `server-registry/src/jvmMain/kotlin/io/qent/broxy/registry/data/GithubCatalogRepository.kt`

Headless runtime:

- `headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`
