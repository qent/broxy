# Inbound transports: STDIO and Streamable HTTP

## What inbound transport means

Inbound is how broxy accepts MCP JSON-RPC requests from clients.

Supported inbound transports on JVM:

- STDIO (local mode for IDEs/agents)
- Streamable HTTP (JSON-only mode for clients that need an HTTP endpoint)

Files:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

## InboundServerFactory

`InboundServerFactory.create(transport, proxy, logger)`:

- `TransportConfig.StdioTransport` -> `StdioInboundServer`
- `TransportConfig.StreamableHttpTransport` -> `KtorStreamableHttpInboundServer`
- `TransportConfig.HttpTransport` -> backward-compatible alias (treated as Streamable HTTP)
- other types are rejected

Note: downstream supports more transports than inbound.

## STDIO inbound

`StdioInboundServer.start()`:

1) uses `System.in` / `System.out`;
2) creates `StdioServerTransport` (MCP SDK);
3) builds SDK `Server` via `buildSdkServer(proxy)`;
4) starts a session with `server.createSession(transport)`.

STDIO requires stdout to remain clean for the MCP protocol. CLI uses a stderr logger:

- `cli/src/main/kotlin/io/qent/broxy/cli/support/StderrLogger.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

Lifecycle note:

- MCP SDK sessions do not block the main thread automatically. CLI keeps the process alive
  with a loop; headless UI mode waits on `transport.onClose`.

Headless STDIO mode (packaged app):

- `broxy --stdio-proxy`
- preset selection order:
    1) explicit `--stdio-proxy` override (if provided by app entrypoint),
    2) `defaultPresetId` from `mcp.json`,
    3) the only preset if exactly one exists,
    4) otherwise an empty preset.

See: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/headless/HeadlessEntrypointJvm.kt`

## Streamable HTTP inbound

### URL parsing and normalization

`KtorStreamableHttpInboundServer` parses `url` (for example `http://localhost:3335/mcp`):

- host/port/path via `URI(url)`
- if host is empty -> `0.0.0.0`
- if port is missing -> 80 (http) or 443 (https)
- if path is empty -> `/mcp`
- trailing slash is removed

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

### Single endpoint: `POST /mcp`

`mountStreamableHttpRoute(...)` handlers:

1) `POST`:
    - expects `Content-Type: application/json`;
    - uses `mcp-session-id` header to select or create a session;
    - sets `mcp-session-id` on the response;
    - for `JSONRPCRequest` returns `JSONRPCResponse` as `application/json`;
    - for notifications returns `200 OK` with no body.
2) `GET`:
    - returns `405 Method Not Allowed` (SSE is not supported).
3) `DELETE`:
    - requires `mcp-session-id` header; closes the session and returns `204 No Content`.

Requests use a 60s response timeout (`DEFAULT_REQUEST_TIMEOUT_MILLIS`).

### Multi-session registry

`InboundStreamableHttpRegistry` (ConcurrentHashMap):

- stores `sessionId -> ServerSession`;
- `remove(sessionId)` closes the session and removes it.

### JSON-only behavior

Outbound messages from the server to the client (notifications) are dropped because the
implementation does not maintain an SSE stream.

## MCP SDK adapter (buildSdkServer)

`buildSdkServer` creates an SDK `Server` backed by `ProxyMcpServer`:

- registers tools/prompts/resources based on `proxy.getCapabilities()`
- re-syncs on preset or server changes via `syncSdkServer`

See: `docs/proxy_facade.md`.

## CLI inbound flags

File: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- `--inbound stdio|http` (aliases: `local|remote|sse`)
- `--url http://localhost:3335/mcp` (for `http` inbound)

## Desktop UI: auto HTTP inbound

Desktop UI starts a local Streamable HTTP inbound automatically on app launch and
stops it on exit.

- Port is configured via `mcp.json` key `inboundSsePort` (default `3335`).
- Port changes via UI restart the inbound server.
- If the port is in use, inbound start fails and UI reports the error.
