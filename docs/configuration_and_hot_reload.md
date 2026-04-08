# Configuration, presets, and hot reload

## Configuration files

Default config directory:

- `~/.config/broxy` (all platforms; Windows uses the same `~/.config` pattern based on user home).

Logs are written next to configuration:

- `~/.config/broxy/logs/YYYY-MM-DD.log`

Custom server icons (desktop UI) are stored next to configuration:

- `~/.config/broxy/icons/`

MCP catalog cache (desktop UI) is stored in the app cache directory:

- `${AppCacheDir}/catalog/catalog_bundle.json`

Key files:

- `config.json` - Broxy global settings (timeouts, retry, adapter mode, `mcpFilePath`, etc.).
- `mcp.json` - downstream server definitions in Claude-compatible `mcpServers` format.
- `ui.json` - desktop UI-only settings (not used by CLI).
- `preset_<id>.json` - presets for filtering.
- OAuth cache for HTTP/WS servers is stored in secure storage.
  - Cache entries are deleted when a server is removed from the active MCP servers file.

Loader:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`

## Split config model

`JsonConfigurationRepository.loadMcpConfig()`:

1. reads `config.json` (if missing, defaults are used);
2. resolves `mcpFilePath`:
   - default: `~/.config/broxy/mcp.json`;
   - absolute path: used as-is;
   - relative path: resolved against Broxy config directory;
   - `~` prefix is expanded to user home;
3. reads MCP servers from resolved `mcp.json`;
4. maps `mcpServers` to `McpServerConfig`;
5. validates transports, ids, env placeholders, and OAuth settings;
6. applies defaults for global settings from `config.json`.

Runtime no longer supports legacy server shape with `transport` object in parser logic.
`auth` is accepted as an input OAuth alias for Cursor compatibility and is normalized to `oauth`.

## `config.json` (global settings)

Supported keys:

- `mcpFilePath`
- `defaultPresetId`
- `inboundHttpPort`
- `requestTimeoutSeconds`
- `capabilitiesTimeoutSeconds`
- `authorizationTimeoutSeconds`
- `connectionRetryCount`
- `ignoreHttpsCertificateErrors`
- `capabilitiesRefreshIntervalSeconds`
- `fallbackPromptsAndResourcesToTools`
- `adapterMode`

### `config.json` example

```json
{
  "mcpFilePath": "~/.config/broxy/mcp.json",
  "defaultPresetId": "developer",
  "inboundHttpPort": 3335,
  "requestTimeoutSeconds": 60,
  "capabilitiesTimeoutSeconds": 30,
  "authorizationTimeoutSeconds": 120,
  "connectionRetryCount": 3,
  "ignoreHttpsCertificateErrors": false,
  "capabilitiesRefreshIntervalSeconds": 300,
  "fallbackPromptsAndResourcesToTools": false,
  "adapterMode": false
}
```

## `mcp.json` (Cursor/Claude-compatible server file)

`mcp.json` keeps only server definitions:

```json
{
  "mcpServers": {
    "github": {
      "name": "GitHub MCP",
      "enabled": true,
      "command": "npx",
      "args": ["@modelcontextprotocol/server-github"],
      "envFile": ".env.github",
      "env": {
        "GITHUB_TOKEN": "${GITHUB_TOKEN}"
      },
      "iconPath": "icons/github.png"
    },
    "remote-api": {
      "type": "http",
      "url": "https://api.example.com/mcp",
      "headers": {
        "X-Client": "broxy"
      },
      "oauth": {
        "type": "oauth",
        "clientId": "client-id",
        "authServerMetadataUrl": "https://auth.example.com/.well-known/openid-configuration"
      }
    }
  }
}
```

For full field reference and interoperability rules, see `docs/claude_code_mcp_format.md`.

## Supported downstream transports

Parsing `type` into `TransportConfig`:

- `"stdio"` -> `TransportConfig.StdioTransport(command, args)`
- `"http"` -> `TransportConfig.StreamableHttpTransport(url, headers)`
- `"sse"` -> `TransportConfig.HttpTransport(url, headers)`
- `"ws"` -> `TransportConfig.WebSocketTransport(url, headers)`

Notes:

- On load, `type` can be omitted for Cursor-style entries:
  - `command` present -> inferred `stdio`
  - `url` present -> inferred `http`
- On save, Broxy always writes explicit canonical `type`.
- `headers` are supported for HTTP (streamable), SSE, and WebSocket.
- `env` is used only for STDIO processes at runtime; for HTTP/WS it is stored but not consumed by transports.
- `envFile` is processed only for `stdio`; for non-stdio transports it is ignored.

## OAuth block (`oauth`)

Downstream HTTP/SSE/WS servers can include an `oauth` block.
If the server supports dynamic client registration, Broxy can auto-discover OAuth parameters and `oauth` may be omitted.

Supported fields include:

- `type`, `clientId`, `clientSecret`, `clientIdMetadataUrl`, `redirectUri`, `callbackPort`,
  `authorizationServer`, `authServerMetadataUrl`, `tokenEndpointAuthMethod`, `scopes`, `allowDynamicRegistration`.
- `redirectUri` supports loopback `http://...` and `https://...` (`localhost` / `127.0.0.1` only, explicit port required).
  For `https://...`, Broxy automatically starts the OAuth callback listener with a temporary self-signed certificate.
- `callbackPort` still builds `http://localhost:<callbackPort>/callback` when `redirectUri` is not explicitly set.
  If both `redirectUri` and `callbackPort` are omitted, Broxy uses
  `http://localhost:<random-port>/oauth/callback`.

Slack-specific recommendation (remote `https://mcp.slack.com/mcp`):

- use pre-registered credentials (`clientId` + `clientSecret`);
- set `tokenEndpointAuthMethod` to `client_secret_post`;
- set `callbackPort` (for example `3118`) and set/register `https://localhost:<port>/callback` in the Slack app;
- set `allowDynamicRegistration` to `false`.

`oauth` is ignored for STDIO transports.

Input normalization:

- Broxy accepts both `oauth` (Claude) and `auth` (Cursor) on load.
- Runtime uses a single OAuth model.
- On save, Broxy writes only `oauth` (canonical output).

## Environment placeholders

Placeholders are supported in string values:

- `${env:VAR}`
- `${VAR}`
- `${VAR:-default}`
- `{VAR}`
- `${workspaceFolder}`
- `${workspaceFolderBasename}`
- `${userHome}`
- `${pathSeparator}`
- `${/}`
- `${input:NAME}`

Implementation:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/EnvironmentVariableResolver.kt`

Behavior:

- Interpolation is applied on load for transport fields (`command`, `args[]`, `url`, `headers` values),
  `env`, and OAuth string fields.
- `workspaceFolder` is the directory of the active `mcp.json`.
- `workspaceFolderBasename` is the directory name of `workspaceFolder`.
- `${input:NAME}` lookup order:
  - exact env key `NAME`;
  - fallback env key with `-` replaced by `_` and uppercased.
- Missing placeholders fail config load with a clear error.
- `${VAR:-default}` uses `default` when env var is missing or empty.
- Cursor placeholders outside the supported set (for example `${file}`) are kept as-is.
- Sensitive values are sanitized in logs.
- On save, raw placeholder values are preserved for unchanged `env`/`oauth` fields.
- On save, raw placeholder values are also preserved for unchanged transport fields (`command`, `args`, `url`, `headers`).

## `envFile` loading and merge

- `envFile` path resolution:
  - absolute path: used as-is;
  - `~` prefix: expanded to user home;
  - relative path: resolved against the directory of the active `mcp.json`.
- Missing/unreadable `envFile` fails configuration load for `stdio` servers.
- Merge order:
  - first values from `envFile`;
  - then inline `env` values from `mcp.json` override (`env` has higher priority).

## `ui.json` (UI-only settings)

UI-only settings are stored in `ui.json` and ignored by CLI/core.

- `showTrayIcon` (default `true`): whether desktop UI displays system tray icon.

## MCP catalog startup refresh

Desktop UI loads a bundled MCP catalog resource at startup and then performs an async refresh check against
the GitHub registry source (`qent/broxy-registry`).

- bundled resource: `catalog/catalog_bundle.json` (generated at build time in `server-registry`)
- repository implementation: `server-registry` (`GithubCatalogRepository`), wired through
  `ui-adapter` composition root
- refresh policy: on each startup Broxy sends `HEAD` to `index.json` and compares `Last-Modified` with local metadata
- cache write: atomic temp file move
- `index.json` and `servers/*.json` are downloaded only when `Last-Modified` changed (or cache is missing)

## `preset_<id>.json`

Preset loading:

- `JsonConfigurationRepository.loadPreset(id)` verifies file exists, parses JSON, and validates id match.

Preset listing:

- `JsonConfigurationRepository.listPresets()` reads `preset_*.json`, skips invalid files with warnings,
  and sorts by `orderIndex`.

## Hot reload (`ConfigurationWatcher`)

File:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`

Watcher observes:

- `config.json`
- active MCP servers file resolved from `config.json` (`mcpFilePath`, can be outside config dir)
- `preset_*.json` in config dir

Behavior:

- `config.json` change -> reload config and rebind watched MCP path if `mcpFilePath` changed.
- active MCP file change -> `onConfigurationChanged(config)`.
- `preset_*.json` change -> `onPresetChanged(preset)`.
- debounce default: 300 ms.

## CLI usage of hot reload

File:

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

Behavior:

- config or active MCP file change -> `ProxyLifecycle.updateServers(config)`
- preset change -> `ProxyLifecycle.applyPreset(preset)`

Inbound server stays up; SDK server is resynced in place.
