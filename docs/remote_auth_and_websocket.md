# Remote auth (OAuth) for downstream HTTP/WS

Broxy supports OAuth authorization for downstream MCP servers using HTTP-based transports. If the
server supports dynamic client registration, Broxy can auto-discover OAuth parameters via the
well-known metadata endpoints and no explicit `auth` block is required.

- HTTP SSE
- Streamable HTTP
- WebSocket (via HTTP handshake)

STDIO transports do **not** use OAuth; use environment variables for credentials instead.

## Discovery flow (resource server)

Broxy follows the MCP OAuth specification with a pre-authorization step:

1. Probe well-known Protected Resource Metadata endpoints before connecting to the MCP URL.
2. If metadata is found, complete OAuth (including dynamic registration when enabled) before opening the MCP session.
3. If metadata is not available, fall back to an unauthenticated MCP request and parse `WWW-Authenticate`
   (including `resource_metadata` and `scope`) for step-up authorization.

Well-known probe targets:
- `/.well-known/oauth-protected-resource/<mcp-path>`
- `/.well-known/oauth-protected-resource`

The resource metadata **must** include `authorization_servers`.

## Authorization server metadata

For each authorization server issuer, Broxy attempts OAuth 2.0 and OpenID Connect discovery
endpoints in priority order, including the RFC 8414 path insertion rules. PKCE support is
required; if `code_challenge_methods_supported` does not include `S256`, authorization fails.

## Scope selection

Broxy selects scopes in this order:

1. `scope` from the `WWW-Authenticate` challenge (when present)
2. `scopes_supported` from the Protected Resource Metadata document
3. `auth.scopes` from configuration (fallback)

## OAuth config (mcp.json)

Add an `auth` block to a server to enable OAuth:

```json
{
  "mcpServers": {
    "secured": {
      "name": "Secured MCP",
      "transport": "streamable-http",
      "url": "https://mcp.example.com/mcp",
      "auth": {
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
- `redirectUri`: Loopback callback (`http://localhost:<port>/...`) for the authorization code flow.
- `authorizationServer`: Optional issuer override if resource metadata is unavailable.
- `tokenEndpointAuthMethod`: `none`, `client_secret_basic`, or `client_secret_post`.
- `scopes`: Fallback scopes when discovery provides none.
- `allowDynamicRegistration`: Enables dynamic client registration when supported.

## Authorization flow

- Uses OAuth 2.1 Authorization Code with PKCE (S256).
- Includes the `resource` parameter in both authorization and token requests, preferring the
  `resource` value from Protected Resource Metadata when present.
- Performs step-up authorization on `insufficient_scope` challenges.
- Uses refresh tokens when provided.
- When OAuth is required, Broxy extends the connect timeout to allow the user to complete the
  browser consent flow and only connects to the MCP endpoint after authorization succeeds.

## OAuth secure storage

Broxy stores OAuth tokens and dynamic client registration data in the system secure storage
(Keychain on macOS, Secret Service on Linux). On restart, cached tokens are reused when still
valid, and refresh tokens are used when available to avoid interactive login. If the cached
resource URL does not match the current server URL, the cached entry is ignored.

If secure storage is unavailable, Broxy keeps OAuth state in memory for the current session only
and requires re-authorization after restart.

## WebSocket notes

WebSocket transports include the OAuth Bearer token during the HTTP handshake. If scopes
change (step-up authorization), Broxy reconnects using the updated token.
