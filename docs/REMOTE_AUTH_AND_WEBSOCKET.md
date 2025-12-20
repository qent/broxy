# Remote mode: OAuth, registration, and WebSocket transport

## Purpose

Remote mode lets a local broxy instance connect to the `broxy.run` backend. The backend proxies
MCP JSON-RPC sessions over WebSocket.

Key idea:

- locally build an MCP SDK `Server` on top of `ProxyMcpServer` (same as inbound);
- connect that server to a `ProxyWebSocketTransport` that wraps MCP messages in the backend envelope.

## Availability and build flags

Remote backend integration is implemented in the private `bro-cloud` build. It is wired via a JVM adapter in
`ui-adapter` and controlled by Gradle properties:

- `broCloudEnabled` (default `true`) - enables/disables remote auth + WebSocket integration.
- `broCloudUseLocal` (default `false`) - use local `bro-cloud/` sources via composite build; otherwise load
  the obfuscated jar from `bro-cloud/libs/bro-cloud-obfuscated.jar`.

When disabled, the UI hides remote actions and the runtime uses a no-op connector.

## Remote subsystem components

- Remote controller (state machine + OAuth + tokens + WS):
    - `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnectorImpl.kt`
- WebSocket client:
    - `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/ws/RemoteWsClient.kt`
- MCP SDK <-> WS envelope adapter:
    - `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`
- Token storage:
    - `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/storage/RemoteConfigStore.kt`
    - `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/storage/SecureStore.kt`
- OAuth callback server:
    - `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/auth/LoopbackCallbackServer.kt`
- UI adapter bridge (Cloud API -> UI state):
    - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/BroCloudRemoteConnectorAdapter.kt`

## RemoteConnectorImpl: state and interface

Interface:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnector.kt`

UI state:

- `UiRemoteConnectionState` contains:
    - `serverIdentifier`
    - `email`
    - `hasCredentials`
    - `status` (`UiRemoteStatus`)
    - `message`

### serverIdentifier

Default is generated in `defaultRemoteServerIdentifier()`:

- format: `broxy-<uuid>`;
- lowercased and capped at 64 characters;
- persisted to `remote.json` on first startup so it stays stable across restarts.

File: `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/CloudDefaults.kt`

## OAuth flow (beginAuthorization)

File: `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnectorImpl.kt`

Constants:

- `BASE_URL = "https://broxy.run"`
- `WS_BASE_URL = "wss://broxy.run"`
- `WS_PATH = "/ws"`
- `REDIRECT_URI = "http://127.0.0.1:8765/oauth/callback"`

Sequence:

1) UI calls `RemoteConnector.beginAuthorization()`.
2) `GET https://broxy.run/auth/mcp/login?redirect_uri=<REDIRECT_URI>` ->
   `LoginResponse(authorization_url, state)`.
3) Validate `authorization_url` contains the same `redirect_uri` (guards against stale backend).
4) Open browser to `authorization_url`.
5) Start loopback server on `127.0.0.1:8765` and wait for callback:
    - `LoopbackCallbackServer.awaitCallback(expectedState)`
    - parse `code` + `state` and validate `state`.
6) Exchange code for access token:
    - `POST https://broxy.run/auth/mcp/callback` with JSON body
      `CallbackRequest(code, state, audience="mcp", redirect_uri=REDIRECT_URI)`
    - response: `TokenResponse(access_token, token_type, expires_at, scope)`
    - validate `token_type == bearer` and `scope == mcp`.
7) Register server identifier:
    - `POST https://broxy.run/auth/mcp/register` with `Authorization: Bearer <access_token>`
    - body: `RegisterRequest(serverIdentifier, name, capabilities={prompts/tools/resources=true})`
    - response: `RegisterResponse(server_identifier, status, jwt_token)` (JWT for WebSocket).
8) Persist config and connect WebSocket if proxy is already running.

### Email extraction (best-effort)

`RemoteConnectorImpl.extractEmail(token)`:

- base64url-decodes the JWT payload;
- reads the `email` field if present.

Used only for UI display; failure is not fatal.

## Token storage and security

`RemoteConfigStore` splits storage:

1) `remote.json` (non-secret):
    - `serverIdentifier`
    - `email`
    - `accessTokenExpiresAt`
    - `wsTokenExpiresAt`

2) `SecureStore` (secrets):
    - `remote.access_token`
    - `remote.ws_token`

Default implementation is filesystem-based:

- `~/.config/broxy/secrets/`
- attempts to set POSIX permissions `0600` when supported.

Files:

- `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/storage/RemoteConfigStore.kt`
- `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/storage/SecureStore.kt`

Tokens are redacted in logs using `RemoteConnectorImpl.redactToken(...)`.

## Auto-connect and refresh

On `RemoteConnectorImpl.start()`:

- load cached config;
- if both access and WS tokens are expired -> clear config and wait for authorization;
- if credentials exist and local proxy is running -> `connectWithCachedTokens(auto=true)`.

`connectWithCachedTokens(auto)`:

1) If valid `wsToken` exists -> `connectWebSocket(wsToken)`.
2) Else if access token is valid -> `registerServer(accessToken)` to obtain new `wsToken`.
3) Else -> require re-authorization and clear credentials.

Notes:

- `wsTokenExpiresAt` is stored as `accessTokenExpiresAt + 24h` (best-effort; backend controls the real lifetime).

Expiry validation:

- `isExpired(expiry) = now() > expiry - 60s`

## WebSocket protocol: URL, headers, envelope

### Connection

`RemoteConnectorImpl.connectWebSocket(jwt)` builds:

- `wss://broxy.run/ws/{serverIdentifier}`

`RemoteWsClient.connect()` uses headers:

- `Authorization: Bearer <jwt>`
- `Sec-WebSocket-Protocol: mcp`

File: `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/ws/RemoteWsClient.kt`

### Envelope messages

Serialization uses `McpJson` (MCP SDK JSON).

Inbound (from backend):

```json
{
  "session_identifier": "uuid",
  "message": {
    /* MCP JSON-RPC */
  }
}
```

Outbound (to backend):

```json
{
  "session_identifier": "uuid",
  "target_server_identifier": "server-id",
  "message": {
    /* MCP JSON-RPC */
  }
}
```

Structures:

- `McpProxyRequestPayload`
- `McpProxyResponsePayload`

File: `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`

### MCP over WebSocket: where the SDK server lives

On connect:

1) `proxyProvider()` must return an active `ProxyMcpServer`.
2) `ProxyWebSocketTransport(serverIdentifier, sender=...)` is created.
3) MCP SDK `Server` is built via `buildSdkServer(proxy)`.
4) `server.createSession(transport)` starts a dedicated SDK session.
5) Reader loop:
    - reads `Frame.Text`;
    - decodes `McpProxyRequestPayload`;
    - decodes `JSONRPCMessage` from `message`;
    - calls `transport.handleIncoming(message, session_identifier)`.

Synchronization details:

- `ProxyWebSocketTransport` stores the current `sessionIdentifier`.
- `RemoteWsClient` uses a `Mutex` to serialize inbound handling per session.

### Auth errors

`RemoteWsClient.connect()` error handling:

- on `ClientRequestException` with status `401/403`, it invokes `onAuthFailure("WebSocket unauthorized ...")`.
- on other connection failures, it also calls `onAuthFailure(reason)` with the failure message.

`RemoteConnectorImpl` does not clear tokens on WS failures by default. It sets the UI status
to `Error` (or `WsOffline`) and keeps credentials for manual recovery.

## Logging and WS diagnostics

`ProxyWebSocketTransport.describeJsonRpcPayload(...)` logs a compact summary per JSON-RPC message:

- message type (request/response/notification/error)
- id/method
- params keys (`name/uri`, arguments/meta keys)
- for capabilities: counts and schema field previews

See also:

- `docs/websocket_preset_capabilities.md`
