# Downstream MCP connections (McpServerConnection + McpClient)

## Purpose of the downstream layer

The downstream layer implements a short-lived session model: connect -> perform one operation -> disconnect.
It hides transport details and returns `Result<T>` to the caller.

Main abstractions:

- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpServerConnection.kt` - connection interface.
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt` - implementation with
  retry/backoff and capabilities caching.

Client transport:

- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpClient.kt` - client interface.
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpClientFactory.kt` - factory using a provider.
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/McpClientFactoryJvm.kt` - JVM provider for STDIO/SSE/Streamable
  HTTP/WS.

## TransportConfig mapping to clients

Model:

- `core/src/commonMain/kotlin/io/qent/broxy/core/models/TransportConfig.kt`

JVM provider:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/McpClientFactoryJvm.kt`

Mapping table:

| TransportConfig           | Downstream client         | Implementation                       |
|---------------------------|---------------------------|--------------------------------------|
| `StdioTransport`          | process + stdio transport | `StdioMcpClient`                     |
| `HttpTransport`           | HTTP SSE                  | `KtorMcpClient(Mode.Sse)`            |
| `StreamableHttpTransport` | Streamable HTTP           | `KtorMcpClient(Mode.StreamableHttp)` |
| `WebSocketTransport`      | WebSocket                 | `KtorMcpClient(Mode.WebSocket)`      |

Note: inbound transport is limited to STDIO and Streamable HTTP. Downstream supports more modes,
including SSE and WebSocket.

## DefaultMcpServerConnection: short-lived session model

File: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt`

Key properties:

- Each operation creates a new `McpClient` (`newClient()`), calls `connect()`, performs the operation,
  and then calls `disconnect()`.
- This avoids long-lived stale connections and keeps the status tied to the last operation.

### Status values

- `ServerStatus.Starting` - before connect.
- `ServerStatus.Running` - after successful connect.
- `ServerStatus.Error(message)` - when an operation fails.
- `ServerStatus.Stopped` - after disconnect.

Type: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/ServerStatus.kt`

### Timeouts

There are three timeouts:

- `callTimeoutMillis` - tool/prompt/resource calls (wrapped in `withTimeout`).
- `capabilitiesTimeoutMillis` - used by clients for list operations.
- `connectTimeoutMillis` - timeout for `client.connect()`.

Update methods:

- `updateCallTimeout(millis)`
- `updateCapabilitiesTimeout(millis)` (also updates `connectTimeoutMillis`)

Runtime wiring:

- `ProxyLifecycle.updateCallTimeout(...)`
- `ProxyLifecycle.updateCapabilitiesTimeout(...)`

The default configuration values are defined in `mcp.json` defaults (60s request, 30s capabilities).
If a connection is created outside that flow, the internal defaults are 60s and 10s respectively.

### Retry/backoff on connect

`connectClient(client)`:

- `maxRetries` attempts (default 3, configurable via `connectionRetryCount` in `mcp.json`).
- backoff via `ExponentialBackoff` (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/Backoff.kt`).
- connect is wrapped in `withTimeout(connectTimeoutMillis)`.

Error types:

- `McpError.TimeoutError`
- `McpError.ConnectionError`

File: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/errors/McpError.kt`

## Capabilities cache (per server)

File: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/CapabilitiesCache.kt`

Behavior:

- Default TTL is 5 minutes (configurable via `cacheTtlMs`).
- Thread safety via `Mutex`.
- `getCapabilities(forceRefresh = false)`:
    - returns cache if fresh and `forceRefresh` is false;
    - otherwise fetches from downstream and updates the cache;
    - on failure, returns cached capabilities only if still within TTL.

This keeps the proxy running even when a downstream server is temporarily unavailable.

## Per-server isolation (JVM runtime)

To keep server lifecycles independent, the JVM proxy runtime wraps each downstream connection with
`core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/IsolatedMcpServerConnection.kt`:

- each server gets its own single-thread dispatcher;
- connect/disconnect/edit/capability fetch calls run on that dedicated thread;
- operations on one server do not block or restart other servers.
- initial capability refresh is concurrency-limited (max 4 or CPU count, whichever is smaller) and
  runs under a supervisor scope so one server failure/cancellation does not cancel other servers' jobs.
- a periodic refresh loop uses `capabilitiesRefreshIntervalSeconds` to retry missing/failed servers and
  keep cached capabilities up to date.

This wrapper is applied by `ProxyControllerJvm` when building downstreams, and also in the
STDIO headless entrypoint. It preserves per-server caches while allowing dynamic add/remove
of servers without affecting others.

## KtorMcpClient (HTTP SSE / Streamable HTTP / WebSocket)

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`

Timeout behavior:

- The client uses Ktor `HttpTimeout` for connect/request/socket timeouts.
- `fetchCapabilities()` calls `getTools()`, `getResources()`, and `getPrompts()` in parallel with per-call
  timeouts, so the total wait is bounded by the slowest category.
- `RealSdkClientFacade` skips list calls when the server capabilities (from `initialize`) report
  that a category is unsupported, and caches the unsupported flag to avoid repeated list attempts.
- If a list operation times out or fails, the client returns an empty list for that category and
  continues immediately. The overall `fetchCapabilities()` call still succeeds unless the client is not connected.

### OAuth for remote HTTP/WS servers

`KtorMcpClient` supports OAuth for HTTP-based downstream transports (SSE, Streamable HTTP, WebSocket):

- Uses `WWW-Authenticate` + `resource_metadata` when present, otherwise probes `.well-known`.
- Discovers authorization server metadata via OAuth 2.0 and OpenID Connect well-known endpoints.
- Requires PKCE (`S256`) support and sends the `resource` parameter on auth/token requests.
- Handles step-up authorization on `insufficient_scope` and refresh tokens when available.
- When metadata is available, completes OAuth before connecting to the MCP endpoint; otherwise falls
  back to an unauthenticated probe and step-up authorization.

If the server supports dynamic client registration, Broxy can auto-discover OAuth parameters via
`/.well-known` endpoints. Use the `auth` block in `mcp.json` only for pre-registered credentials
or servers without dynamic registration (see `docs/remote_auth_and_websocket.md`).

## StdioMcpClient (process + STDIO transport)

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`

### Process startup and environment

- `CommandLocator.resolveCommand(...)` resolves the STDIO command to an absolute path before launch.
- `ProcessBuilder(listOf(resolvedCommand) + args)` with environment populated from `env`.
- `env` is resolved via `EnvironmentVariableResolver.resolveMap(...)`.
- If `env` does not specify `PATH`, the JVM resolves the user's login + interactive shell `PATH`
  (fallback to the current process `PATH`) and injects it before launching. On macOS, standard
  Homebrew paths are appended if missing.
- If the command cannot be resolved, the connection fails fast with a configuration error so the UI
  can surface the message.
- Logs are sanitized via `EnvironmentVariableResolver.logResolvedEnv(...)`.

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/config/EnvironmentVariableResolver.kt`

### Handshake and timeout

The handshake is performed in `async(Dispatchers.IO)`:

- builds `StdioClientTransport` and wraps it in `LoggingTransport`;
- creates `Client(Implementation(...))`;
- calls `sdk.connect(transport)`;
- `withTimeout(connectTimeout)` waits for completion.

On timeout:

- the process is destroyed (`destroyForcibly()`);
- a `McpError.TimeoutError("STDIO connect timed out ...")` is returned.

### stderr logging

A dedicated thread reads `proc.errorStream` and logs lines as
`logger.warn("[STDERR][cmd] ...")`.

### LoggingTransport: MCP message tracing

`LoggingTransport` logs:

- `tools/list`, `resources/list`, `prompts/list` requests by method;
- corresponding responses by request id;
- `*_list_changed` notifications.

## MultiServerClient: parallel requests

File: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/MultiServerClient.kt`

Responsibilities:

- `fetchAllCapabilities()` - fetches capabilities in parallel across servers.
- `listPrefixedTools(allCaps)` - builds `serverId:` prefixed tool list.

`RequestDispatcher` uses `MultiServerClient` as a fallback when prompt/resource routing
maps are missing.
