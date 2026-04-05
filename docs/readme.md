# Documentation for AI agents and maintainers

This folder contains detailed documentation for Broxy subsystems: architecture, proxy facade, downstream
connections, preset filtering, configuration and hot reload, inbound transports, remote auth and WebSocket
transport, capabilities snapshots for the UI, and logging.

Recommended reading order:

1. `docs/architecture.md` - modules, layers, and end-to-end flows.
2. `docs/ui_adapter_boundary.md` - ui-adapter core boundary inventory and target contract.
3. `docs/core_contracts.md` - public core contracts and invariants.
4. `docs/core_contracts_checklist.md` - quick checklist for core contract review.
5. `docs/core_dependency_audit.md` - package dependency map and refactor targets.
6. `docs/proxy_facade.md` - inbound facade, routing, and the `serverId_tool` namespace contract.
7. `docs/downstream_mcp_connections.md` - downstream MCP clients, timeouts, retry/backoff, and capabilities cache.
8. `docs/presets_and_filtering.md` - preset model, filtering rules, and prompt/resource routing.
9. `docs/configuration_and_hot_reload.md` - `config.json` + Claude-compatible `mcp.json`, environment placeholders, and watcher.
10. `docs/claude_code_mcp_format.md` - canonical Cursor/Claude `mcp.json` format, field mapping, and interoperability rules.
11. `docs/inbound_transports.md` - inbound STDIO and Streamable HTTP transports and SDK adapter.
12. `docs/cli_mode.md` - CLI flags, defaults, and hot reload behavior.
13. `docs/remote_auth_and_websocket.md` - OAuth for downstream HTTP/WS servers plus STDIO bootstrap auth flow.
14. `docs/capabilities_cache_and_ui_refresh.md` - UI snapshots, cache, statuses, and background refresh.
15. `docs/logging_and_observability.md` - log formats, key events, and tracing guidance.
16. `docs/testing.md` - testing practices and test entry points.
17. `docs/test_mcp_server_status.md` - self-check for the test MCP server.
18. `docs/localization.md` - UI localization strings and language wiring.
19. `docs/ai_clients.md` - AI client connectors and Codex integration.
20. `docs/mcp_catalog.md` - bundled MCP catalog (`server-registry`), GitHub sync, and schema-driven install UI.
21. `docs/server_icons.md` - server icon rules and assets.
22. `docs/distribution.md` - minimal DMG packaging and local build commands.
