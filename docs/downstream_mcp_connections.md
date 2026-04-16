# Downstream MCP connections (McpServerConnection + McpClient)

## Purpose

Define downstream client/connection behavior, timeout/retry policy, and capability caching semantics.

## When to read

- When changing `DefaultMcpServerConnection` behavior.
- When changing Ktor/STDIO transport implementations.
- When changing capability cache/retry/timeout policy.

## Source-of-truth files

- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/CapabilitiesCache.kt`

## Behavior contract

Broxy uses short-lived downstream sessions per operation and returns `Result<T>` while preserving
capability-cache fallback semantics for transient failures.

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

Config mapping (from `mcp.json`):

- `"http"` -> `StreamableHttpTransport`
- `"sse"` -> `HttpTransport`
- `"ws"` -> `WebSocketTransport`

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

There are four timeouts:

- `callTimeoutMillis` - tool/prompt/resource calls (wrapped in `withTimeout`).
- `capabilitiesTimeoutMillis` - used by clients for list operations.
- `connectTimeoutMillis` - timeout for `client.connect()` (kept in sync with `capabilitiesTimeoutMillis`).
- `authorizationTimeoutMillis` - OAuth authorization timeout used by HTTP/WS clients (headless flows).

Interactive OAuth note:

- For clients that implement `AuthInteractiveMcpClient`, `DefaultMcpServerConnection` does not wrap
  `client.connect()` in an outer timeout. The OAuth flow controls its own authorization timeout
  (headless) or waits without a timeout when the UI popup is open, while network connect timeouts
  are still enforced inside the client implementations.

Update methods:

- `updateCallTimeout(millis)`
- `updateCapabilitiesTimeout(millis)` (also updates `connectTimeoutMillis`)
- `updateAuthorizationTimeout(millis)`
- `updateConnectionRetryCount(count)`

Runtime wiring:

- `ProxyLifecycle.updateCallTimeout(...)`
- `ProxyLifecycle.updateCapabilitiesTimeout(...)`
- `ProxyLifecycle.updateConnectionRetryCount(...)`
- `ProxyLifecycle.updateServers(...)` applies new `authorizationTimeoutSeconds` and retry counts when configs change

The default configuration values are defined in `mcp.json` defaults (60s request, 30s capabilities, 120s authorization, 3 retries).
If a connection is created outside that flow, the internal defaults are 60s call, 10s capabilities/connect,
120s authorization, and 5 retries.

### Retry/backoff on connect

`connectClient(client)`:

- `maxRetries` attempts (default 5 in `DefaultMcpServerConnection`, configurable via `connectionRetryCount` in `mcp.json`).
- backoff via `ExponentialBackoff` (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/ExponentialBackoff.kt`).
- connect is wrapped in `withTimeout(connectTimeoutMillis)`.

Error types:

- `McpError.TimeoutError`
- `McpError.ConnectionError`

### Capability fetch (single attempt)

`getCapabilities()` performs a single session per call: it connects (using the configured
`connectionRetryCount` + backoff) and then issues one capability fetch. There are no additional
retries for the capability request itself. If the fetch fails, the connection returns the error
or falls back to cached capabilities when the cache is still within TTL.

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

Headless STDIO mode additionally persists raw `ServerCapabilities` snapshots to disk and warms
`CapabilitiesCache` at startup so downstream OAuth/connect is not triggered during client attach
when cached entries are still fresh. See `docs/inbound_transports.md` for details.

## Per-server isolation (JVM runtime)

To keep server lifecycles independent, the JVM proxy runtime wraps each downstream connection with
`core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/IsolatedMcpServerConnection.kt`:

- each server gets its own single-thread dispatcher;
- each server has its own `CoroutineScope(SupervisorJob + dispatcher)` and a per-server queue
  (actor-style) that serializes connect/call/fetch operations;
- connect/disconnect/edit/capability fetch calls run on that dedicated thread;
- operations on one server do not block or restart other servers.
- cancelled operations are removed from the queue, and shutdown cancels pending work so close does not block.
- initial capability refresh is concurrency-limited (max 4 or CPU count, whichever is smaller) and
  runs under a supervisor scope so one server failure/cancellation does not cancel other servers' jobs.
- a periodic refresh loop uses `capabilitiesRefreshIntervalSeconds` to retry missing/failed servers and
  keep cached capabilities up to date.

This wrapper is applied by `ProxyControllerJvm` when building downstreams, and also in the
STDIO headless entrypoint (`headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`).
It preserves per-server caches while allowing dynamic add/remove
of servers without affecting others.

## KtorMcpClient (HTTP SSE / Streamable HTTP / WebSocket)

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`

TLS/certificate behavior:

- Global `config.json` setting `ignoreHttpsCertificateErrors` controls HTTPS/WSS certificate validation for downstream HTTP/SSE/Streamable HTTP/WebSocket sessions.
- When enabled, Ktor CIO clients use a permissive trust manager for both MCP transport connections and OAuth HTTP discovery/token calls.
- This is intended for trusted corporate/self-signed environments only.

Timeout behavior:

- The client sets Ktor `HttpTimeout` for connect timeouts only; request/socket timeouts are not set so
  per-call coroutine timeouts control request duration.
- For Streamable HTTP, if the follow-up SSE `GET` endpoint responds with `404 Not Found`, `405 Method Not Allowed`,
  or `Content-Type: application/json`, Broxy treats the server as JSON-only and keeps the connection alive.
- Broxy uses the SDK-native Streamable HTTP client transport (`mcpStreamableHttp`) rather than a forked/custom transport;
  the JSON-only fallback behavior above comes from MCP Kotlin SDK 0.10.0+.
- `fetchCapabilities()` calls `getTools()`, `getResources()`, and `getPrompts()` in parallel with per-call
  timeouts, so the total wait is bounded by the slowest category.
- `RealSdkClientFacade` skips list calls when the server capabilities (from `initialize`) report
  that a category is unsupported, and caches the unsupported flag to avoid repeated list attempts.
- If a list operation times out or fails, the client returns an empty list for that category and
  continues immediately. The overall `fetchCapabilities()` call still succeeds unless the client is not connected.
- `callTool()`, `getPrompt()`, and `readResource()` timeouts are enforced by `DefaultMcpServerConnection`
  via the outer `withTimeout` around each operation.

### OAuth for remote HTTP/WS servers

`KtorMcpClient` supports OAuth for HTTP-based downstream transports (SSE, Streamable HTTP, WebSocket):

- Uses `WWW-Authenticate` + `resource_metadata` when present, otherwise probes `.well-known`.
- Discovers authorization server metadata via OAuth 2.0 and OpenID Connect well-known endpoints.
- Requires PKCE (`S256`) support and sends the `resource` parameter on auth/token requests.
- Handles step-up authorization on `insufficient_scope` and refresh tokens when available.
- For dynamic registration, sends `token_endpoint_auth_method` only when configured.
- If registration returns `token_endpoint_auth_method`, Broxy uses that server-issued value even if
  discovery metadata advertises a different set.
- If registration omits `token_endpoint_auth_method` and returns `client_secret`, Broxy infers one from
  server metadata and OAuth defaults (prefers `client_secret_basic`, then `client_secret_post`).
- If token exchange/refresh fails with `invalid_client` (or unsupported auth method), Broxy retries
  with alternate auth methods and stores the working method in in-memory OAuth state.
- When resource metadata is available and includes `authorization_servers`, Broxy completes OAuth
  before connecting to the MCP endpoint.
- Protected resource metadata field `resource` is accepted as both a single string and an array of
  strings; when an array is returned Broxy uses the first non-empty value.
- If pre-authorization metadata is present but does not include `authorization_servers`, Broxy treats
  OAuth metadata as unavailable and continues without a token until an auth challenge is received.
- In challenge-driven flows (`401/403`), missing `authorization_servers` remains a hard error.

If the server supports dynamic client registration, Broxy can auto-discover OAuth parameters via
`/.well-known` endpoints. Use the `oauth` block in `mcp.json` only for pre-registered credentials
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

If the process exits right after startup (before MCP initialization completes), the client
fails the connect attempt with a `ConnectionError` so upstream retry logic can run.

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

Failure isolation:

- `fetchAllCapabilities()` runs each server fetch in a supervisor scope and collects results
  opportunistically; failures/cancellations for one server do not cancel other fetches.

`RequestDispatcher` uses `MultiServerClient` as a fallback when prompt/resource routing
maps are missing.
