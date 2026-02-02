# AI clients

Broxy can act as an MCP server for supported AI clients. The UI exposes a dedicated Clients screen
that lists available client connectors and their connection status. The list order follows the
connector list provided by `provideAiClientConnectors` (currently a fixed, curated order in
`ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/AiClientConnectorsJvm.kt`).

## Architecture

- Connector abstraction: `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/clients/AiClientConnector.kt`
- JVM implementations: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/clients/*`
- UI model: `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/models/UiAiClient.kt`

Each connector is responsible for:

- discovering the client’s configuration on disk;
- reporting connection readiness and warnings (via `AiClientStatus.notice`);
- adding/removing the Broxy MCP server configuration while leaving other entries untouched.

The UI drives connectors through `UIState.Intents.connectAiClient` and
`UIState.Intents.disconnectAiClient`. Connector status is reloaded after each action and when the
HTTP port changes.

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
- Most clients use a `url` entry; Claude uses a local STDIO command entry instead. Claude Code
  requires `type: "http"` alongside the URL.
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

The app must create this file first; Broxy only updates `mcpServers`. Claude Code requires the
`type` field to be set to `broxy` alongside the URL:

```json
{
  "mcpServers": {
    "broxy": {
      "type": "broxy",
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
