# Documentation for AI agents and maintainers

This folder contains detailed documentation for broxy subsystems: architecture, proxy facade, downstream
connections, preset filtering, configuration and hot reload, inbound transports, remote auth and WebSocket
transport, capabilities snapshots for the UI, and logging.

Recommended reading order:

1. `docs/architecture.md` - modules, layers, and end-to-end flows.
2. `docs/proxy_facade.md` - inbound facade, routing, and the `serverId:tool` namespace contract.
3. `docs/downstream_mcp_connections.md` - downstream MCP clients, timeouts, retry/backoff, and capabilities cache.
4. `docs/presets_and_filtering.md` - preset model, filtering rules, and prompt/resource routing.
5. `docs/configuration_and_hot_reload.md` - `mcp.json`, `preset_*.json`, environment placeholders, and watcher.
6. `docs/inbound_transports.md` - inbound STDIO and Streamable HTTP transports and SDK adapter.
7. `docs/remote_auth_and_websocket.md` - OAuth for downstream HTTP/WS servers and authorization flows.
8. `docs/capabilities_cache_and_ui_refresh.md` - UI snapshots, cache, statuses, and background refresh.
9. `docs/logging_and_observability.md` - log formats, key events, and tracing guidance.
10. `docs/testing.md` - testing practices and test entry points.
11. `docs/test_mcp_server_status.md` - self-check for the test MCP server.
