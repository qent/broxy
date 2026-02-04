# Inbound transports: STDIO, Streamable HTTP, and SSE

## What inbound transport means

Inbound is how Broxy accepts MCP JSON-RPC requests from clients.

Supported inbound transports on JVM:

- STDIO (local mode for IDEs/agents)
- Streamable HTTP (JSON-only mode for clients that need an HTTP endpoint)
- SSE endpoint at `/sse` on the same host/port as Streamable HTTP

Files:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

## InboundServerFactory

`InboundServerFactory.create(transport, proxy, logger, requestTimeoutMillis = DEFAULT)`:

- `TransportConfig.StdioTransport` -> `StdioInboundServer`
- `TransportConfig.StreamableHttpTransport` -> `KtorStreamableHttpInboundServer` (Streamable HTTP + `/sse`)
- `TransportConfig.HttpTransport` -> backward-compatible alias (treated as Streamable HTTP)
- other types are rejected

Note: downstream supports more transports than inbound.

## STDIO inbound

`StdioInboundServer.start()`:

1) uses `System.in` / `System.out`;
2) creates `StdioServerTransport` (MCP SDK);
3) builds SDK `Server` via `buildSdkServer(proxy)`;
4) starts a session with `server.createSession(transport)`.

Startup note:

- Before the STDIO session is created, Broxy waits for the initial downstream
  capability refresh across all enabled servers so `tools/list`, `prompts/list`,
  and `resources/list` are populated on the first request.

STDIO requires stdout to remain clean for the MCP protocol. CLI uses a stderr logger:

- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/StdErrLogger.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/support/CliLoggerFactory.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommandRunner.kt`

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
- headless STDIO warms the downstream capabilities cache from
  `~/.config/broxy/capabilities_raw/` (or the configured `configDir`).
- cached entries are treated as fresh on startup so tools/prompts/resources are served from cache
  and no downstream OAuth/connect is triggered during client attach.
- background refresh uses non-forced capability checks; downstream OAuth/connect runs only when
  cached entries are stale.

See: `headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`

## Streamable HTTP inbound

### URL parsing and normalization

`KtorStreamableHttpInboundServer` parses `url` (for example `http://localhost:3335/mcp`):

- host/port/path via `URI(url)`
- if host is empty -> `0.0.0.0`
- if port is missing -> 80 (http) or 443 (https)
- if path is empty -> `/mcp`
- trailing slash is removed

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

Note: the SSE endpoint is always `/sse` on the same host/port (not derived from the Streamable HTTP path).

### Single endpoint: `POST /mcp`

`mountStreamableHttpRoute(...)` handlers:

1) `POST`:
    - expects `Content-Type: application/json`;
    - uses `mcp-session-id` header to select or create a session;
    - sets `mcp-session-id` on the response;
    - for `JSONRPCRequest` returns `JSONRPCResponse` as `application/json`;
    - for notifications/responses returns `202 Accepted` with no body.
2) `GET`:
    - returns `405 Method Not Allowed` (SSE is served at `/sse`).
3) `DELETE`:
    - requires `mcp-session-id` header; closes the session and returns `204 No Content`.

Requests use `requestTimeoutSeconds` from configuration (default 60s if not overridden).

### Multi-session registry

`InboundStreamableHttpRegistry` (ConcurrentHashMap):

- stores `sessionId -> ServerSession`;
- `remove(sessionId)` closes the session and removes it.
- each session tracks `lastSeenAt` and is cleaned up after 20 minutes of inactivity (cleanup runs every 5 minutes).

`InboundSseRegistry` (ConcurrentHashMap):

- stores `sessionId -> SseServerTransport`;
- `sessionId` is passed as a query parameter (`/sse?sessionId=...`).
- sessions are cleaned up after 20 minutes of inactivity (cleanup runs every 5 minutes) and transports are closed.

### JSON-only behavior (Streamable HTTP only)

Outbound messages from the server to the client (notifications) are dropped because the
implementation does not maintain an SSE stream. SSE inbound does emit server messages
over the SSE connection.

## SSE inbound

SSE is exposed at `/sse` on the same host/port as Streamable HTTP:

1) `GET /sse`:
    - opens the SSE stream;
    - emits an `endpoint` event pointing clients at `/sse?sessionId=...`.
2) `POST /sse?sessionId=...`:
    - expects `Content-Type: application/json`;
    - forwards the MCP JSON-RPC message into the active SSE session.

## MCP SDK adapter (buildSdkServer)

`buildSdkServer` creates an SDK `Server` backed by `ProxyMcpServer`:

- registers tools/prompts/resources based on `proxy.getCapabilities()`
- re-syncs on preset or server changes via `syncSdkServer`

See: `docs/proxy_facade.md`.

## CLI inbound flags

File: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- `--inbound stdio|http` (aliases: `local|remote|sse`)
- `--url http://localhost:3335/mcp` (for `http` inbound)
- SSE endpoint is served at `http://localhost:3335/sse` on the same host/port.

## Desktop UI: auto HTTP inbound

Desktop UI starts a local Streamable HTTP inbound automatically on app launch and
stops it on exit.

- Port is configured via `mcp.json` key `inboundHttpPort` (default `3335`).
- Port changes via UI restart the inbound server.
- If the port is in use, inbound start fails and UI reports the error.
- SSE is available at `http://localhost:{port}/sse`.
