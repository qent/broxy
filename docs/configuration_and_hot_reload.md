# Configuration, presets, and hot reload

## Configuration files

Default config directory:

- `~/.config/broxy` (all platforms; Windows uses the same `~/.config` pattern based on user home).

Logs are written next to configuration:

- `~/.config/broxy/logs/YYYY-MM-DD.log`

Key files:

- `mcp.json` - downstream server list and global settings.
- `preset_<id>.json` - presets for filtering.
- OAuth cache for HTTP/WS servers is stored in system secure storage.
  - Cache entries are deleted when a server is removed from `mcp.json`.

Loader:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`

## mcp.json structure and validation

`JsonConfigurationRepository.loadMcpConfig()`:

1) reads `mcp.json` (missing file -> empty config);
2) decodes into `FileMcpRoot`;
3) expands `mcpServers: Map<String, FileMcpServer>` into `McpServerConfig` list;
4) validates:
    - transport type and required fields (`command`/`url`);
    - unique `serverId`;
    - non-blank `id` and `name`;
    - presence of env vars used by placeholders;
5) applies defaults:
    - `requestTimeoutSeconds` (default 60)
    - `capabilitiesTimeoutSeconds` (default 30)
    - `authorizationTimeoutSeconds` (default 120)
    - `connectionRetryCount` (default 3)
    - `capabilitiesRefreshIntervalSeconds` (default 300)
    - `showTrayIcon` (default true)
    - `inboundSsePort` (default 3335; historical name, used for local Streamable HTTP)
    - `defaultPresetId` (optional)

`capabilitiesRefreshIntervalSeconds` drives both the proxy background refresh loop and
the UI refresh cadence (minimum 30 seconds for each).

### mcp.json example

```json
{
  "defaultPresetId": "developer",
  "inboundSsePort": 3335,
  "requestTimeoutSeconds": 60,
  "capabilitiesTimeoutSeconds": 30,
  "authorizationTimeoutSeconds": 120,
  "connectionRetryCount": 3,
  "capabilitiesRefreshIntervalSeconds": 300,
  "showTrayIcon": true,
  "mcpServers": {
    "github": {
      "name": "GitHub MCP",
      "transport": "stdio",
      "command": "npx",
      "args": [
        "@modelcontextprotocol/server-github"
      ],
      "env": {
        "GITHUB_TOKEN": "${GITHUB_TOKEN}"
      }
    },
    "slack": {
      "name": "Slack MCP",
      "transport": "http",
      "url": "https://slack-mcp.example.com",
      "auth": {
        "type": "oauth",
        "clientId": "slack-client-id",
        "clientSecret": "${SLACK_CLIENT_SECRET}",
        "redirectUri": "http://localhost:8080/callback",
        "authorizationServer": "https://auth.slack.com",
        "tokenEndpointAuthMethod": "client_secret_post"
      }
    },
    "realtime": {
      "name": "Realtime MCP",
      "transport": "websocket",
      "url": "ws://localhost:8080/ws",
      "headers": {
        "X-Client": "broxy"
      }
    }
  }
}
```

### serverId in Desktop UI

- In `mcp.json`, `serverId` is the key of `mcpServers` and is part of the tool namespace: `serverId:toolName`.
- Desktop UI auto-generates `serverId` from `name` (slugified).
- When renaming a server, `ConfigurationManager.renameServer(...)` updates `mcp.json` and rewrites all
  `preset_*.json` references from the old id to the new id (best-effort; errors are logged).
- While editing a STDIO server, the Desktop UI checks command availability against the resolved user `PATH`
  (login + interactive shell, plus standard Homebrew paths on macOS) when the command field loses focus and
  shows a warning if the command cannot be found.

## Supported downstream transports

Parsing `transport` (string) into `TransportConfig`:

- `"stdio"` -> `TransportConfig.StdioTransport(command, args)`
- `"http"` -> `TransportConfig.HttpTransport(url, headers)`
- `"streamable-http"` (and aliases) -> `TransportConfig.StreamableHttpTransport(url, headers)`
- `"ws"`/`"websocket"` -> `TransportConfig.WebSocketTransport(url, headers)`

Notes:

- `headers` are supported for HTTP/SSE, Streamable HTTP, and WebSocket.
- `env` is used only for STDIO processes; for HTTP/WS it is stored but not consumed by transports.

## OAuth auth block

Downstream HTTP/SSE, Streamable HTTP, and WebSocket servers can include an `auth` block for OAuth.
If the server supports dynamic client registration, Broxy can auto-discover OAuth parameters via
`/.well-known` endpoints and no `auth` block is required.

- `type`: `"oauth"`
- `clientId`: pre-registered client id (preferred when present)
- `clientSecret`: optional secret for confidential clients
- `clientIdMetadataUrl`: HTTPS URL for Client ID Metadata Documents
- `redirectUri`: loopback callback (`http://localhost:<port>/...`)
- `redirectUri` requires an explicit port; only loopback HTTP is supported.
- `authorizationServer`: issuer override if resource metadata is unavailable
- `tokenEndpointAuthMethod`: `none`, `client_secret_basic`, `client_secret_post`
- `scopes`: fallback scopes when discovery provides none
- `allowDynamicRegistration`: toggle dynamic registration support

Use `auth` only when the server does **not** support dynamic client registration or when you need
pre-registered client credentials. `auth` is ignored for STDIO transports; use environment variables
instead.

## Environment placeholders

Placeholders are supported in `env` values in two forms:

- `${VAR}`
- `{VAR}`

Implementation:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/EnvironmentVariableResolver.kt`

Behavior:

- `JsonConfigurationRepository` checks missing placeholders and fails with a clear error.
- For logging, env values are sanitized by key: TOKEN/SECRET/PASSWORD/KEY -> `"***"`.

## preset_<id>.json

Preset loading:

- `JsonConfigurationRepository.loadPreset(id)`:
    - verifies the file exists;
    - parses JSON into `Preset`;
    - validates that `preset.id` matches the file id.

Rename semantics:

- A preset id is part of the file name. Renaming means save under a new id and delete the old file.
- Desktop UI generates ids from `name` and performs rename (save new id + delete old file).

Preset listing:

- `JsonConfigurationRepository.listPresets()` reads all `preset_*.json` files and skips invalid ones with a warning.

## Hot reload: ConfigurationWatcher

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`

Purpose: watch the config directory and notify observers.

Key behaviors:

- Uses `WatchService` with `ENTRY_CREATE/MODIFY/DELETE`.
- Debounce (`debounceMillis`, default 300 ms).
- When `mcp.json` changes -> `onConfigurationChanged(config)`.
- When `preset_*.json` changes -> `onPresetChanged(preset)` if the file exists.
- If the directory does not exist, the watcher logs and remains idle.

Manual triggers (tests/headless):

- `triggerConfigReload()`
- `triggerPresetReload(id)`

`emitInitialState`:

- If `true`, the watcher emits an initial config event after debounce.
- UI uses `emitInitialState = true`; CLI uses `false` because config is loaded before start.

## CLI usage of hot reload

File: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- `mcp.json` change -> `ProxyLifecycle.restartWithConfig(config)` (restart inbound + downstream).
- `preset_*.json` change -> `ProxyLifecycle.applyPreset(preset)` (re-sync SDK server, no inbound restart).
