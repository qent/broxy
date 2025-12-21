# Remote auth (OAuth) for downstream HTTP/WS

broxy supports OAuth authorization for downstream MCP servers using HTTP-based transports. If the
server supports dynamic client registration, broxy can auto-discover OAuth parameters via the
well-known metadata endpoints and no explicit `auth` block is required.

- HTTP SSE
- Streamable HTTP
- WebSocket (via HTTP handshake)

STDIO transports do **not** use OAuth; use environment variables for credentials instead.

## Discovery flow (resource server)

broxy follows the MCP OAuth specification:

1. Attempt an unauthenticated request and parse `WWW-Authenticate` if present.
2. If `resource_metadata` is provided, fetch the Protected Resource Metadata document.
3. Otherwise, probe well-known URIs:
   - `/.well-known/oauth-protected-resource/<mcp-path>`
   - `/.well-known/oauth-protected-resource`

The resource metadata **must** include `authorization_servers`.

## Authorization server metadata

For each authorization server issuer, broxy attempts OAuth 2.0 and OpenID Connect discovery
endpoints in priority order, including the RFC 8414 path insertion rules. PKCE support is
required; if `code_challenge_methods_supported` does not include `S256`, authorization fails.

## Scope selection

broxy selects scopes in this order:

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
- When OAuth is required, broxy extends the connect timeout to allow the user to complete the
  browser consent flow.

## WebSocket notes

WebSocket transports include the OAuth Bearer token during the HTTP handshake. If scopes
change (step-up authorization), broxy reconnects using the updated token.
