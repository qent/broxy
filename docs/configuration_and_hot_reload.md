# Configuration, presets, and hot reload

## Purpose

Define config file ownership, parsing/normalization behavior, and watcher-driven hot reload semantics.

## When to read

- When changing config keys, defaults, or placeholder resolution.
- When changing watcher behavior or file ownership across UI/CLI.
- When changing `mcp.json` compatibility mappings.

## Source-of-truth files

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigMapper.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`
- `core/src/commonMain/resources/schemas/config.schema.json`

## Behavior contract

Global runtime settings are stored in `config.json`; server definitions are stored in `mcp.json`;
UI-only settings are stored in `ui.json`.

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
- `ui.json` - desktop UI settings (UI-only, not used by CLI).
- `preset_<id>.json` - presets for filtering (`tools/prompts/resources` + `agentTools`).
- `agents/<id>.md` - Claude-compatible agent definitions (frontmatter + markdown system prompt).
- `agents/metadata/agent_<id>.json` - Broxy sidecar metadata for agent-only fields
  (`tools`, `agentTools`, `prompts`, `resources`, `orderIndex`, `schedule`, `manualLaunchDefaults`).
- `agents/runs/run_<runId>.json` - full structured run trace (dialogue + runtime actions + tool call/result payloads).
- `agents/runs_index.json` - compact run summaries used for `Runs` list rendering.
- `agents/agents_settings.json` - non-secret agent provider settings (endpoint overrides + per-provider model cache +
  optional `agentsDirectoryPath` for external Claude-agent folders).
- `agents/agents_secrets.json` - fallback API key storage for agents when secure storage is unavailable.
- OAuth cache for HTTP/WS servers is stored in system secure storage.
  - Cache entries are deleted when a server is removed from `mcp.json`.

### Source-of-truth matrix

| Data | Canonical file/store | Used by |
| --- | --- | --- |
| Runtime/global settings (`defaultPresetId`, timeouts, retry, `adapterMode`, etc.) | `config.json` | UI + CLI + headless |
| MCP server definitions (`mcpServers`) | `mcp.json` (via `mcpFilePath`) | UI + CLI + headless |
| UI-only settings (`showTrayIcon`) | `ui.json` | UI only |
| Presets | `preset_<id>.json` | UI + CLI + headless |
| OAuth token/cache state | OS secure storage | downstream HTTP/SSE/WS clients |
| Capability snapshot cache (UI) | `${AppCacheDir}/capabilities/` | UI/preset-management fallback |
| Catalog cache | `${AppCacheDir}/catalog/catalog_bundle.json` | UI catalog |

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

Desktop Settings note:

- `mcpFilePath` is no longer editable from the desktop `Settings` screen.
- To change the active MCP servers file, edit `config.json` directly and set `mcpFilePath`.

Desktop UI startup behavior:

- when loaded config has no `defaultPresetId` and there are no file-backed presets, `AppStore` activates
  `__preset_management__` as the active preset;
- this is a runtime UI fallback; `config.json` remains unchanged until the user explicitly selects/saves a preset.

`ignoreHttpsCertificateErrors` is a global setting for all downstream HTTPS/WSS connections.
When enabled, Broxy disables certificate validation in Ktor CIO clients (MCP transport and OAuth HTTP flows)
and in agent LLM provider HTTP clients (OpenAI/Anthropic endpoints, including endpoint overrides), which
allows corporate/self-signed certificates but reduces TLS security guarantees.

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

For full field reference and interoperability rules, see `docs/claude_code_mcp_format.md`.

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

## ui.json (UI-only settings)

UI-only settings are stored in `ui.json` next to `mcp.json`. These settings are not read by CLI or core.

- `showTrayIcon` (default true): whether the desktop UI displays a system tray icon.
- `agentRunNotificationsEnabled` (default true): whether desktop agent runs emit system notifications.
  - macOS uses native UserNotifications only (`UNUserNotificationCenter`) via JNI bridge
    (`broxy_notifications_bridge.m` + `MacOsNotificationNativeBridge.kt`).
    - Broxy can issue up to three native notification authorization requests per app session via
      `requestAuthorization(...)` while authorization is not yet granted.
    - the native bridge sets a notification center delegate so alerts can be presented while the app window is active.
    - notifications are enabled only when the process is launched from a macOS `.app` bundle.
      Non-bundle runs (for example IDE/Gradle JVM launch) skip notifications to avoid
      UserNotifications runtime crashes (`bundleProxyForCurrentProcess is nil`).
    - no fallback to deprecated `NSUserNotification*` APIs is implemented.
  - Windows uses native toast notifications (`Windows.UI.Notifications`).
    - requires a desktop AppUserModelID shortcut (installer-managed) and PowerShell WinRT APIs.
    - when toast notifications are disabled in system settings, Broxy opens Windows notification settings on the first manual agent launch.
  - Linux uses freedesktop notifications (`notify-send` with actions).
    - requires `notify-send` with action support and an active desktop notification daemon.

### ui.json example

```json
{
  "showTrayIcon": true,
  "agentRunNotificationsEnabled": true
}
```

### serverId in Desktop UI

- In `mcp.json`, `serverId` is the key of `mcpServers` and is part of the tool namespace: `serverId_toolName`.
- Desktop UI auto-generates `serverId` from `name` (slugified).
- Desktop UI server cards support drag-and-drop reordering; the saved `mcpServers` object keeps that order.
- When renaming a server, `ConfigurationManager.renameServer(...)` updates `mcp.json` and rewrites all
  `preset_*.json` references from the old id to the new id (best-effort; errors are logged).
- New server ids created via UI/CLI must not contain `_` (to avoid `serverId_tool` namespace collisions).
  Existing configs with `_` are supported but log a warning on load.
- While editing a STDIO server, the Desktop UI checks command availability against the resolved user `PATH`
  (login + interactive shell, plus standard Homebrew paths on macOS) when the command field loses focus and
  shows a warning if the command cannot be found.

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

Agent reference semantics in config files:

- `preset_<id>.json.agentTools[*].agentId` and `agents/metadata/agent_<id>.json.agentTools[*].agentId`
  may point to currently missing agents; these refs are preserved and not auto-cleaned.
- deleting an agent does not rewrite other config files.
- renaming an agent id through UI flow rewrites `agentTools` refs in presets and agents before old file removal.

## Hot reload: ConfigurationWatcher

Watcher observes:

- `config.json`
- active MCP servers file resolved from `config.json` (`mcpFilePath`, can be outside config dir)
- `preset_*.json` in config dir

Behavior:

- `config.json` change -> reload config and rebind watched MCP path if `mcpFilePath` changed.
- active MCP file change -> `onConfigurationChanged(config)`.
- `preset_*.json` change -> `onPresetChanged(preset)`.
- debounce default: 300 ms.

Manual triggers (tests/headless):

- `triggerConfigReload()`
- `triggerPresetReload(id)`

`emitInitialState`:

- If `true`, the watcher emits an initial config event after debounce.
- CLI uses `emitInitialState = false` because config is loaded before start. Desktop UI refreshes
  configuration via `AppStore` intents and does not wire `ConfigurationWatcher` by default.
- Agent files (`agents/*.md`, `agents/metadata/*.json`, `agents/runs/*.json`, `agents/runs_index.json`,
  `agents/agents_settings.json`) are not observed by `ConfigurationWatcher`;
  the desktop app updates agent state via `AgentGateway` after agent operations and on startup.

## Agent provider settings (`agents/agents_settings.json`)

`agents/agents_settings.json` stores non-secret provider runtime config used by agent launches:

- `agentsDirectoryPath`: optional path to directory with Claude subagent markdown files (`<id>.md`).
  when omitted/blank, Broxy uses `~/.config/broxy/agents`.

- endpoint overrides:
  - `openAi.baseUrl` (default `https://api.openai.com/v1`)
  - `anthropic.baseUrl` (default `https://api.anthropic.com`)
  - `lmStudio.baseUrl` (default `http://127.0.0.1:1234/v1`)
- model cache:
  - `modelCache.openAi`
  - `modelCache.anthropic`
  - `modelCache.lmStudio`

Model cache flow:

1) launch form picks provider;
2) UI reads cached models from `agents/agents_settings.json`;
3) if cache is empty, Broxy requests provider models API and persists the result into cache;
4) refresh icon in launch form forces API reload and cache overwrite.

Secrets are not stored in this file:

- OpenAI/Anthropic API keys live in secure storage (`agents/agents_secrets.json` fallback only).
- LM Studio does not require API key and has endpoint-only settings.
- LM Studio HTTP requests for model listing and execution use HTTP/1.1 to avoid `h2c` upgrade issues
  on local endpoints.
## CLI usage of hot reload

File:

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

Behavior:

- config or active MCP file change -> `ProxyLifecycle.updateServers(config)`
- preset change -> `ProxyLifecycle.applyPreset(preset)`

Inbound server stays up; SDK server is resynced in place.
