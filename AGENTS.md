# AGENTS.md - Codex Runbook for Broxy

## Purpose

This file is the execution contract for AI coding agents and maintainers. Keep it concise, actionable, and
link-first. Detailed contracts live in `docs/`.

## Start Here (always)

1. Read `docs/readme.md`.
2. Read `docs/agent_quickstart.md`.
3. Read only subsystem docs relevant to your change.

## Mandatory Workflow

1. Implement the change.
2. If behavior/contracts/data flow changed, update matching `docs/*.md` in the same PR.
3. Run required checks and fix all findings:
   - `./gradlew build`
   - `./gradlew testAll`
   - `./gradlew :cli:integrationTest`
4. Keep all filenames in `docs/` lowercase.

## Non-Negotiable Contracts (no drift)

- Tool namespace: `serverId_toolName`.
- Inbound transports: STDIO and Streamable HTTP; SSE endpoint is `/sse` on the same host/port.
- Session binding:
  - `/mcp` and `/sse` follow active preset;
  - `/mcp/{presetId}` and `/sse/{presetId}` are preset-pinned;
  - unknown preset -> `404`, route/preset rebinding -> `409`.
- Preset semantics:
  - tools are explicit allow-list;
  - `prompts/resources = null` means unrestricted within in-scope servers;
  - `prompts/resources = []` means deny all.
- Config ownership:
  - `config.json` -> global runtime settings;
  - `mcp.json` -> MCP servers;
  - `ui.json` -> UI-only settings.
- Remote preset notification contract:
  - method `broxy/preset_changed`;
  - `change_type`: `selection | composition`;
  - optional `preset_id`;
  - session prefix `preset-change:`.

## Change Routing (read/update map)

- Routing/namespace/proxy facade:
  - read/update `docs/proxy_facade.md`, `docs/core_contracts.md`.
- Presets/filtering/built-ins:
  - read/update `docs/presets_and_filtering.md`.
- HTTP/SSE inbound/session behavior:
  - read/update `docs/inbound_transports.md`.
- Downstream MCP connections/timeouts/cache:
  - read/update `docs/downstream_mcp_connections.md`.
- Config/parsing/hot reload:
  - read/update `docs/configuration_and_hot_reload.md`.
- Remote auth + websocket + preset-change payloads:
  - read/update `docs/remote_auth_and_websocket.md`, `docs/websocket_preset_capabilities.md`.
- UI adapter state/capability refresh:
  - read/update `docs/capabilities_cache_and_ui_refresh.md`, `docs/ui_adapter_boundary.md`.
- CLI behavior/startup:
  - read/update `docs/cli_mode.md`.
- Catalog/icons/client connectors:
  - read/update `docs/mcp_catalog.md`, `docs/server_icons.md`, `docs/ai_clients.md`.

## Boundaries

- `core`: runtime/domain/data logic, no UI dependencies.
- `ui-adapter`: orchestrates `core` for presentation, no Compose state types.
- `ui`: thin Compose layer; must not import `core` directly.
- `cli`: command entrypoint/runtime wiring.
- `server-registry`: catalog/repository/planning.
- `headless-runtime`: packaged STDIO entrypoint.
- Enforce adapter boundaries via `docs/ui_adapter_boundary.md` and `:ui-adapter:checkUiAdapterCoreBoundary`.

## Quality Rules

- Prefer deterministic tests; avoid `Thread.sleep`.
- Use coroutine test utilities for async behavior.
- Update tests when changing contracts/flows.
- Never log secrets/tokens in plain text.
