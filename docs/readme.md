# Documentation Index (AI + Maintainers)

This directory is organized into two levels:

- Level 1: fast navigation for AI coding agents and maintainers.
- Level 2: detailed subsystem contracts and implementation notes.

## Level 1: Fast Navigation

- `docs/agent_quickstart.md` - first stop for task routing, invariants, commands, and high-signal entrypoints.
- `docs/architecture.md` - system map and end-to-end runtime flows.
- `docs/core_contracts.md` - stable contracts and invariants that must not drift.

## Task-Driven Index

| If you are changing... | Read first | Then update |
| --- | --- | --- |
| Tool routing, namespace, SDK sync | `docs/proxy_facade.md` | `docs/core_contracts.md`, `docs/inbound_transports.md` |
| Preset semantics / allow-lists | `docs/presets_and_filtering.md` | `docs/proxy_facade.md`, `docs/core_contracts.md` |
| Config parsing, placeholders, watcher | `docs/configuration_and_hot_reload.md` | `docs/claude_code_mcp_format.md`, `docs/cli_mode.md` |
| Downstream MCP client behavior | `docs/downstream_mcp_connections.md` | `docs/remote_auth_and_websocket.md`, `docs/logging_and_observability.md` |
| Inbound HTTP/SSE session behavior | `docs/inbound_transports.md` | `docs/proxy_facade.md` |
| Remote preset notifications over WS | `docs/websocket_preset_capabilities.md` | `docs/remote_auth_and_websocket.md` |
| UI capability snapshots / refresh | `docs/capabilities_cache_and_ui_refresh.md` | `docs/presets_and_filtering.md` |
| AI client connectors / import flow | `docs/ai_clients.md` | `docs/configuration_and_hot_reload.md` |
| Catalog install behavior | `docs/mcp_catalog.md` | `docs/server_icons.md` |
| Logging event contracts | `docs/logging_and_observability.md` | related subsystem doc where event source changed |
| Build/tests/checks policy | `docs/testing.md` | touched subsystem docs |

## Level 2: Detailed Subsystem Docs

Core runtime and proxy:

- `docs/proxy_facade.md`
- `docs/downstream_mcp_connections.md`
- `docs/presets_and_filtering.md`
- `docs/inbound_transports.md`
- `docs/remote_auth_and_websocket.md`
- `docs/websocket_preset_capabilities.md`

Configuration and compatibility:

- `docs/configuration_and_hot_reload.md`
- `docs/claude_code_mcp_format.md`
- `docs/cli_mode.md`

UI and adapter boundaries:

- `docs/capabilities_cache_and_ui_refresh.md`
- `docs/ui_adapter_boundary.md`
- `docs/ai_clients.md`
- `docs/localization.md`

Catalog and assets:

- `docs/mcp_catalog.md`
- `docs/server_icons.md`

Quality and release:

- `docs/logging_and_observability.md`
- `docs/testing.md`
- `docs/test_mcp_server_status.md`
- `docs/distribution.md`

Reference docs for planned/internal refactors:

- `docs/core_contracts_checklist.md`
- `docs/core_dependency_audit.md`

## Documentation Rules

- Keep filenames lowercase under `docs/`.
- When behavior/contracts/data-flow change, update the corresponding subsystem doc in the same PR.
- Prefer one canonical source per behavior and cross-link from related docs instead of duplicating logic.
