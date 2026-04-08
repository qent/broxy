# Remote auth (OAuth) for downstream HTTP/WS

## Purpose

Define OAuth behavior for downstream HTTP/SSE/WS transports and related remote-session considerations.

## When to read

- When changing OAuth discovery, registration, token handling, or redirect behavior.
- When changing auth popups/headless auth timeout behavior.
- When changing WebSocket auth/reconnect interactions.

## Source-of-truth files

- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/auth/OAuthManager.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/AuthCoordinator.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/BroCloudRemoteConnectorAdapter.kt`

## Behavior contract

If explicit `Authorization` headers are configured for a downstream transport, OAuth auto-discovery is skipped.
Otherwise Broxy performs pre-auth/challenge-driven OAuth according to MCP/OAuth metadata and challenge flow.

Broxy supports OAuth authorization for downstream MCP servers using HTTP-based transports. If the
server supports dynamic client registration, Broxy can auto-discover OAuth parameters via the
well-known metadata endpoints and no explicit `oauth` block is required.

- HTTP SSE
- Streamable HTTP
- WebSocket (via HTTP handshake)

STDIO transports do **not** use OAuth; use environment variables for credentials instead.
If the transport `headers` include an explicit `Authorization` header, Broxy skips OAuth discovery
and uses the provided headers as-is.

## Discovery flow (resource server)

Broxy follows the MCP OAuth specification with a pre-authorization step:

1. Probe well-known Protected Resource Metadata endpoints before connecting to the MCP URL.
2. If metadata is found and contains `authorization_servers`, complete OAuth (including dynamic registration when enabled) before opening the MCP session.
3. If metadata is not available, fall back to an unauthenticated MCP request and parse `WWW-Authenticate`
   (including `resource_metadata` and `scope`) for step-up authorization.

Well-known probe targets:
- `/.well-known/oauth-protected-resource/<mcp-path>`
- `/.well-known/oauth-protected-resource`

Protected resource metadata compatibility:
- Broxy accepts `resource` as either a single string or an array of strings.
- When an array is returned, Broxy uses the first non-empty value as the canonical resource URI.

During challenge-driven authorization (`401/403`), the resource metadata **must** include
`authorization_servers`. During pre-authorization (no challenge yet), Broxy treats metadata without
`authorization_servers` as "OAuth not available yet" and continues unauthenticated.

## Authorization server metadata

For each authorization server issuer, Broxy attempts OAuth 2.0 and OpenID Connect discovery
endpoints in priority order, including the RFC 8414 path insertion rules. PKCE support is
required; if `code_challenge_methods_supported` does not include `S256`, authorization fails.

## Scope selection

Broxy selects scopes in this order:

1. `scope` from the `WWW-Authenticate` challenge (when present)
2. `scopes_supported` from the Protected Resource Metadata document
3. `oauth.scopes` from configuration (fallback)

## OAuth config (mcp.json)

Add an `oauth` block to a server to enable OAuth:

```json
{
  "mcpServers": {
    "secured": {
      "name": "Secured MCP",
      "type": "http",
      "url": "https://mcp.example.com/mcp",
      "oauth": {
        "type": "oauth",
        "clientId": "client-id",
        "clientSecret": "${TOKEN}",
        "redirectUri": "http://localhost:8080/callback",
        "authorizationServer": "https://auth.example.com",
        "tokenEndpointAuthMethod": "client_secret_post",
        "scopes": ["files:read"],
        "allowDynamicRegistration": true
      }
    }
  }
}
```

### Supported OAuth fields

- `clientId`: Pre-registered client ID (preferred when present).
- `clientSecret`: Optional secret for confidential clients.
- `clientIdMetadataUrl`: HTTPS URL for Client ID Metadata Documents.
- `redirectUri`: Loopback callback URI for the authorization code flow. Supported schemes are
  `http` and `https` on `localhost` or `127.0.0.1` with an explicit port.
  When `https` is used, Broxy starts a temporary loopback HTTPS listener with an auto-generated
  self-signed certificate (no root certificate install required).
- `callbackPort`: Convenience override for loopback callback
  (`http://localhost:<callbackPort>/callback`). This remains HTTP by default.
  When both `redirectUri` and `callbackPort` are omitted, Broxy defaults to
  `http://localhost:<random-port>/oauth/callback`.
- `authorizationServer`: Optional issuer override if resource metadata is unavailable.
- `authServerMetadataUrl`: Optional HTTPS metadata URL override for OAuth discovery.
- `tokenEndpointAuthMethod`: `none`, `client_secret_basic`, or `client_secret_post`.
- `scopes`: Fallback scopes when discovery provides none.
- `allowDynamicRegistration`: Enables dynamic client registration when supported.

Slack note:

- Slack MCP (`https://mcp.slack.com/mcp`) currently requires a pre-registered confidential client.
- Recommended config: `clientId`, `clientSecret`, `tokenEndpointAuthMethod=client_secret_post`,
  `callbackPort=3118` (or another fixed registered loopback port),
  `redirectUri=https://localhost:3118/callback`, `allowDynamicRegistration=false`.

## Authorization flow

- Uses OAuth 2.1 Authorization Code with PKCE (S256).
- For dynamic client registration, requests `grant_types` with both `authorization_code` and `refresh_token`.
- During dynamic registration, Broxy sends `token_endpoint_auth_method` only when it is explicitly configured.
- Includes the `resource` parameter in both authorization and token requests, preferring the
  `resource` value from Protected Resource Metadata when present.
- For dynamic registration, if the registration response includes `token_endpoint_auth_method`,
  Broxy uses that value as-is (even when authorization-server metadata differs).
- If dynamic registration omits `token_endpoint_auth_method` and returns `client_secret`,
  Broxy infers a usable method from authorization-server metadata and OAuth defaults (prefers
  `client_secret_basic` per RFC 7591, falls back to `client_secret_post` when required) before token exchange.
- If token exchange returns `invalid_client` (or reports unsupported client auth), Broxy retries
  the token request with alternate supported auth methods (`client_secret_basic` /
  `client_secret_post` / `none`) and keeps the working method in the current OAuth state.
- On `invalid_token` bearer challenges, Broxy clears the cached access token and forces a new OAuth flow.
- Performs step-up authorization on `insufficient_scope` challenges.
- Uses refresh tokens when provided.
- Headless/CLI OAuth waits are bounded by `authorizationTimeoutSeconds`.

## UI authorization popup

When running the desktop UI, Broxy shows a `Server Authorization` popup and asks for explicit
permission before opening the authorization URL in the system browser:

- The popup is centered in the main window and includes a close button (outside clicks do not dismiss).
- The popup title and server name stay the same across both interactive states.
- First state: permission prompt with `Cancel` and `Continue in Browser`.
- Second state (after `Continue in Browser`): waiting-for-completion popup with `Cancel` only.
- Broxy opens the OAuth URL in the user's default browser only after the user clicks `Continue in Browser`.
- If multiple servers request OAuth at startup, Broxy queues those popups and shows them one-by-one.
- The loopback callback success page is context-aware: it shows `<Server Name> Authorized` and uses the
  resolved registry icon URL when one is available for that server.
- If the OAuth redirect contains an `error` parameter (for example `error=access_denied`), the callback page
  shows `<Server Name> Authorization failed` with a failed status instead of a success title.
- For successful redirects with no server context/icon, the callback page falls back to the generic
  `Authorization complete` title and check icon.
- After a successful OAuth redirect, the popup closes automatically and capabilities are refreshed.
- If the user closes the popup or authorization fails, the popup closes and the server is disabled
  (the UI toggle turns off).
- If the user clicks Cancel, Broxy disables the server and stops OAuth retries for that attempt.
- While the popup is open, Broxy listens for the loopback callback without applying the authorization timeout.
- Interactive authorization does not use the connect retry timeout, so the popup is not reopened mid-flow.
- For `https://localhost` / `https://127.0.0.1` redirect URIs, the loopback callback listener
  uses an auto-generated self-signed certificate for that OAuth session.

## OAuth secure storage

Broxy stores OAuth tokens and dynamic client registration data in the system secure storage
(Keychain on macOS, Secret Service on Linux). On restart, cached tokens are reused when still
valid, and refresh tokens are used when available to avoid interactive login. If the cached
resource URL does not match the current server URL, the cached entry is ignored.

Agent runtimes also reuse this storage. Both LangChain and Codex agent executions restore OAuth
state for scoped downstream HTTP/WS servers and persist updates using the same `serverId +
resourceUrl` keying rules, so repeated runs avoid re-running interactive OAuth when cached state
remains valid.

OAuth state snapshots are persisted asynchronously after authorization changes, so the final
secure storage write may complete shortly after a connection teardown.

If secure storage is unavailable, Broxy keeps OAuth state in memory for the current session only
and requires re-authorization after restart.

When a server is removed from `mcp.json`, Broxy deletes the cached OAuth entry for that server ID.

On macOS, Broxy resolves the Keychain `security` tool from standard system paths so Keychain
storage works even when PATH is minimal.

If a cached OAuth entry is corrupted or cannot be decoded, Broxy deletes the entry and requires
re-authorization.

OAuth cache entries are stored as compact JSON to avoid non-printable characters that some
Keychain tooling renders as hex output.

On macOS, Broxy writes OAuth entries to the Keychain using multiple update strategies and verifies
the stored value after each attempt. If verification fails, it retries with the next strategy.
Empty Keychain values are treated as missing entries.

## WebSocket notes

WebSocket transports include the OAuth Bearer token during the HTTP handshake. If scopes
change (step-up authorization), Broxy reconnects using the updated token.

When the UI is in a disconnected state (cloud icon with a slash) or the proxy stops,
Broxy closes active WebSocket sessions to the remote backend. After logout, reconnect
attempts are suppressed until the user authorizes or connects again.
