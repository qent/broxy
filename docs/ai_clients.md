# AI clients

Broxy can act as an MCP server for supported AI clients. The UI exposes a dedicated Clients screen
that lists available client connectors and their connection status. The list order follows the
connector list provided by `provideAiClientConnectors` (currently a fixed, curated order in
`ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/AiClientConnectorsJvm.kt`).

## Architecture

- Connector abstraction: `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/clients/AiClientConnector.kt`
- JVM implementations: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/*`
- UI model: `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/models/UiAiClient.kt`
- Shared format contracts: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/common/*`
- Shared format implementations:
  - JSON: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/formats/json/*`
  - TOML: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/formats/toml/*`

Each connector is responsible for:

- discovering the client’s configuration on disk;
- reporting connection readiness and warnings (via `AiClientStatus.notice`);
- adding/removing the Broxy MCP server configuration while leaving other entries untouched;
- reading importable MCP servers from the client config via
  `AiClientConnector.loadImportableServers()` and returning normalized
  `AiClientImportServer` items (`sourceServerId`, `name`, `enabled`, `transport`, `env`).

Format handling is split into a reusable internal layer:

- `listServers(content)` - lists server IDs from the client config format;
- `listServerEntries(content)` - returns normalized server entries with source ID and transport fields used by import;
- `readBroxyStatus(content)` - checks if `broxy` is configured and extracts URL when available;
- `upsertBroxy(content, entry)` - inserts or updates `broxy` entry;
- `removeBroxy(content)` - removes only `broxy` entry.

The UI drives connectors through `UIState.Intents.connectAiClient` and
`UIState.Intents.disconnectAiClient`. Connector status is reloaded after each action and when the
HTTP port changes.

## MCP import section on Servers screen

The MCP Servers screen now has an `Import` section rendered below the main Broxy server list.

Flow:

1. `AppStore.start()` launches a background import scan job.
2. The scan calls `loadImportableServers()` for each available connector.
3. Items are filtered:
   - skip malformed/unsupported transport entries (warning in logs, no UI failure);
   - skip entries where source ID or name equals `broxy` (case-insensitive);
   - skip entries hidden manually via `Hide`;
   - skip entries marked as installed when mapping `<clientId>::<sourceServerId> -> <serverId>` exists and
     `<serverId>` is currently present in Broxy MCP servers.
4. Results are grouped by client and sorted in the adapter:
   - clients by `client.name` (A-Z);
   - servers within each client by `server.name` (A-Z).
5. The prepared groups are published in `UIState.Ready.importedServerGroups`; the UI flattens these groups into one
   import list and sorts all rows by server name (A-Z, with client/source as tie-breakers).

Rendering rules:

- Header `Import` is plain (not a card).
- Import server rows use server-like cards with:
  - no reorder handle;
  - auto-resolved icon only (no upload/remove);
  - title + transport label;
  - inline client name (smaller text) shown on the right side of the transport row;
  - actions: `Import` and `Hide`.
- Search on MCP screen applies to both the main server list and import cards.

Import behavior:

- `Import` does not save or connect anything.
- `Import` only opens `ServerEditorState.CreateFromImport` with prefilled draft data from the selected client
  server.
- If the user presses `Cancel` in the editor, no MCP server is created.
- On `Save`, the standard `upsertServer` flow creates/updates the server, and Broxy stores install mapping
  `<clientId>::<sourceServerId> -> <savedServerId>`.
- Installed mappings are not migrated on ID rename. If user renames the imported server ID later, the import card
  appears again.

Hide/reset behavior:

- `Hide` persists a key `<clientId>::<sourceServerId>` in OS-native storage (JVM: `java.util.prefs.Preferences`).
- Hidden entries are excluded on all subsequent scans even if the previously installed server was removed.
- Settings include `Reset hidden MCP servers`, which clears only manual hide keys and triggers a rescan.
- Installed mappings are stored separately from hidden keys and are not reset by `Reset hidden MCP servers`.
- If an installed mapped server is deleted from Broxy MCP servers, that import card is shown again.
- Hidden and installed states are not stored in project JSON configuration files.

## Universal connection snippets

The Clients screen starts with a single container that includes HTTP, STDIO, and SSE tabs (in that
order) for the universal JSON snippets. HTTP is selected by default, and switching tabs updates the
snippet shown.
HTTP and SSE examples use the current inbound HTTP port from Settings.

STDIO:

```json
{
  "mcpServers": {
    "broxy": {
      "command": "/Applications/broxy.app/Contents/MacOS/broxy",
      "args": ["--stdio-proxy"]
    }
  }
}
```

HTTP:

```json
{
  "mcpServers": {
    "broxy": {
      "url": "http://localhost:{port}/mcp"
    }
  }
}
```

Preset-specific Streamable HTTP connections are also supported:

- replace `/mcp` with `/mcp/{presetId}` to pin that client session to a specific preset;
- preset-pinned sessions ignore later preset switching in the Broxy UI/CLI;
- the Presets screen exposes a copy action on each preset card that copies
  `http://localhost:{port}/mcp/{presetId}` and shows a confirmation toast.
- using `presetId = __preset_management__` pins the client to the management-only MCP surface
  (`get_preset_creation_algorithm`, `list_server_names`, `get_server_description`,
  `list_preset_names`, `get_preset_description`, `create_preset`).

SSE:

```json
{
  "mcpServers": {
    "broxy": {
      "url": "http://localhost:{port}/sse"
    }
  }
}
```

Preset-specific SSE connections are also supported by replacing `/sse` with `/sse/{presetId}`.
`/sse/__preset_management__` provides the same fixed management-only tool surface.

## Codex connector

Codex configuration lives in the user’s home directory:

- `~/.codex/config.toml`

Broxy writes the following block when connecting:

```
[mcp_servers.broxy]
url = "http://localhost:{port}/mcp"

```

Rules:

- If `~/.codex/config.toml` is missing, the Connect button is disabled and the UI shows
  `~/.codex/config.toml was not found.` after the description.
- If the Broxy block is present and matches the current HTTP port, the UI shows Disconnect.
- If the Broxy block exists but points at another port, the UI shows a warning notice while still
  allowing Connect/Disconnect.
- Only the `[mcp_servers.broxy]` block is modified; other sections remain untouched.
- Connect/disconnect avoids adding extra blank lines; the file keeps a single blank separator around
  the Broxy block.

## MCP JSON connectors

Some clients store an `mcp.json`-compatible file with a shared schema and client-specific base paths.
The connector logic is reusable and only varies by directory location.

Rules:

- `mcp.json`-compatible files are parsed and written as JSON (no line-based edits).
- Connection status is based on the presence of `mcpServers.broxy` (or `servers.broxy` for VS Code).
- Most clients use a `url` entry.
- Claude Desktop uses a local STDIO command entry.
- Claude Code uses `url` and requires `type: "http"` in the server entry.
- If the client base directory is missing, Connect is disabled and the UI shows
  `Configuration for <Client> was not found.`.
- If the directory exists but the client config file is missing, Connect creates the file unless the
  client uses an app-level config file (Claude Code, Gemini CLI). Those require the config file to
  exist already and show `Configuration for <Client> was not found.` when missing.
- Disconnect removes only the `broxy` entry from `mcpServers`/`servers` and keeps all other fields intact.

### LM Studio connector

LM Studio configuration lives in the user’s home directory:

- `~/.lmstudio/mcp.json`

Broxy adds:

```json
{
  "mcpServers": {
    "broxy": {
      "url": "http://localhost:{port}/mcp"
    }
  }
}
```

### Claude Code connector

Claude Code configuration lives in the user’s home directory:

- `~/.claude.json`

The app must create this file first; Broxy only updates `mcpServers`.
Claude Code requires `type: "http"` alongside the URL:

```json
{
  "mcpServers": {
    "broxy": {
      "type": "http",
      "url": "http://localhost:{port}/mcp"
    }
  }
}
```

### Gemini CLI connector

Gemini CLI configuration lives in the user’s home directory:

- `~/.gemini/settings.json`

The app must create this file first; Broxy only updates `mcpServers`.
Gemini CLI uses the SSE endpoint (`http://localhost:{port}/sse`) instead of `/mcp`.

### Cursor connector

Cursor configuration lives in the user’s home directory:

- `~/.cursor/mcp.json`

### Google Antigravity connector

Google Antigravity configuration lives in the user’s home directory:

- `~/.gemini/antigravity/mcp_config.json`

### Roo Code connector

Roo Code configuration lives in the user’s home directory:

- `~/Library/Application Support/Code/User/globalStorage/rooveterinaryinc.roo-cline/settings/mcp_settings.json`

### KILO CODE connector

KILO CODE configuration lives in the user’s home directory:

- `~/Library/Application Support/Code/User/globalStorage/kilocode.kilo-code/settings/mcp_settings.json`

### Cline connector

Cline configuration lives in the user’s home directory:

- `~/Library/Application Support/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json`

### Claude connector

Claude configuration lives in the user’s home directory:

- `~/Library/Application Support/Claude/claude_desktop_config.json`

Broxy adds a local STDIO server entry:

```json
{
  "mcpServers": {
    "broxy": {
      "command": "/Applications/broxy.app/Contents/MacOS/broxy",
      "args": ["--stdio-proxy"]
    }
  }
}
```

### Windsurf connector

Windsurf configuration lives in the user’s home directory:

- `~/.codeium/windsurf/mcp_config.json`

### Visual Studio Code connector

Visual Studio Code configuration lives in the user’s home directory:

- `~/Library/Application Support/Code/User/mcp.json`

VS Code uses `servers` as the MCP map key instead of `mcpServers`.

### Kiro connector

Kiro configuration lives in the user’s home directory:

- `~/.kiro/settings/mcp.json`

## Per-client format notes

Each connector directory includes `mcp_server_list_format.md` with client-specific config path,
Broxy entry shape, and up-to-date references:

- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/claude/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/claude_code/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/cline/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/codex/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/cursor/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/gemini_cli/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/google_antigravity/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/kilo_code/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/kiro/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/lmstudio/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/roo_code/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/vscode/mcp_server_list_format.md`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/connectors/windsurf/mcp_server_list_format.md`

## Client icons

PNG client icons live under:

- `ui/src/desktopMain/resources/icons/clients/`

For Codex, the expected asset name is `codex.png`.
For Cursor, the expected asset name is `cursor.png`.
For LM Studio, the expected asset name is `lmstudio.png`.
For Claude, the expected asset name is `claude.png`.
For Windsurf, the expected asset name is `windsurf.png`.
For Visual Studio Code, the expected asset name is `vscode.png`.
For Kiro, the expected asset name is `kiro.png`.
For Google Antigravity, the expected asset name is `antigravity.png`.
For Roo Code, the expected asset name is `roo-code.png`.
For KILO CODE, the expected asset name is `kilo.png`.
For Cline, the expected asset name is `cline.png`.
For Claude Code, the expected asset name is `claude_code.png`.
For Gemini CLI, the expected asset name is `gemini.png`.
