# broxy - multiplatform MCP proxy

[![Tests](https://github.com/qent/broxy/actions/workflows/tests.yml/badge.svg)](https://github.com/qent/broxy/actions/workflows/tests.yml) [![Build Installers](https://github.com/qent/broxy/actions/workflows/release.yml/badge.svg)](https://github.com/qent/broxy/actions/workflows/release.yml)

## Overview

broxy is a Kotlin-based proxy for the Model Context Protocol (MCP). It sits between an MCP client
(such as Claude Desktop) and multiple MCP servers, aggregates their capabilities, and exposes a
filtered view based on presets.

Key capabilities:

- Downstream transport support: STDIO, HTTP SSE, Streamable HTTP, WebSocket.
- Preset-based filtering and strict tool allow lists.
- Desktop UI (Compose Multiplatform) and CLI mode.
- Environment variable placeholders for sensitive values in `env`.

## Architecture

Conceptual flow:

```
MCP client
    |
    v
  broxy (proxy facade)
    |
    +-- downstream MCP server A
    +-- downstream MCP server B
    +-- downstream MCP server C
```

Modules:

1) `core` - platform-independent proxy logic, routing, configuration.
2) `ui` - Compose Desktop UI.
3) `ui-adapter` - presentation adapter (UDF/MVI, stores, remote mode).
4) `cli` - command-line entry point.

See `docs/readme.md` for detailed subsystem documentation.

## Configuration

Default config directory:

- `~/.config/broxy` (all platforms)

Main config file: `mcp.json`.

Supported downstream transports in `mcp.json`:

- `stdio`, `http` (SSE), `streamable-http`, `websocket`.

Example `mcp.json`:

```json
{
  "defaultPresetId": "developer",
  "inboundSsePort": 3335,
  "requestTimeoutSeconds": 60,
  "capabilitiesTimeoutSeconds": 30,
  "capabilitiesRefreshIntervalSeconds": 300,
  "showTrayIcon": true,
  "mcpServers": {
    "github": {
      "name": "GitHub MCP Server",
      "transport": "stdio",
      "command": "npx",
      "args": ["@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_TOKEN": "${GITHUB_TOKEN}"
      }
    },
    "slack": {
      "name": "Slack MCP Server",
      "transport": "http",
      "url": "https://slack-mcp.example.com",
      "headers": {
        "Authorization": "Bearer token-value"
      }
    },
    "realtime": {
      "name": "Realtime MCP Server",
      "transport": "websocket",
      "url": "ws://localhost:8080/ws"
    }
  }
}
```

Notes:

- Environment placeholders are resolved only inside `env` values.
- `headers` and `url` are not interpolated; provide final values.
- `env` applies to STDIO processes only.

## Presets

A preset is an allow list of tools (and optionally prompts/resources).

Example `preset_developer.json`:

```json
{
  "id": "developer",
  "name": "Developer Assistant",
  "tools": [
    { "serverId": "github", "toolName": "create_issue", "enabled": true },
    { "serverId": "github", "toolName": "create_pull_request", "enabled": true },
    { "serverId": "filesystem", "toolName": "read_file", "enabled": true },
    { "serverId": "filesystem", "toolName": "write_file", "enabled": true }
  ]
}
```

If `prompts` or `resources` are omitted, broxy includes all prompts/resources from in-scope
servers. If they are present but empty, none are included.

## Running the proxy

### CLI

STDIO inbound (for desktop MCP clients):

```bash
java -jar cli/build/libs/broxy-cli-<version>.jar proxy \
  --config-dir ~/.config/broxy \
  --preset-id developer \
  --inbound stdio \
  --log-level info
```

Streamable HTTP inbound:

```bash
java -jar cli/build/libs/broxy-cli-<version>.jar proxy \
  --config-dir ~/.config/broxy \
  --preset-id developer \
  --inbound http \
  --url http://localhost:3335/mcp
```

Notes:

- `--preset-id` is required.
- `--inbound` defaults to `stdio`.

### Desktop UI

1) Open broxy
2) Select a preset
3) Start the proxy

### STDIO mode from a packaged app

The packaged desktop app can run in headless STDIO mode:

- macOS: `/Applications/broxy.app/Contents/MacOS/broxy --stdio-proxy`
- Windows: `"C:\Program Files\broxy\broxy.exe" --stdio-proxy`

Preset resolution order:

1) explicit override (if provided by the app entrypoint),
2) `defaultPresetId` from `mcp.json`,
3) the only preset if exactly one exists,
4) otherwise an empty preset.

## Connecting from Claude Desktop

### Using the packaged app (recommended)

Select a preset in the broxy UI. It is stored as `defaultPresetId` in `mcp.json` and used by the
STDIO facade.

macOS:

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

Windows:

```json
{
  "mcpServers": {
    "broxy": {
      "command": "C:\\Program Files\\broxy\\broxy.exe",
      "args": ["--stdio-proxy"]
    }
  }
}
```

### Using the CLI jar

```json
{
  "mcpServers": {
    "broxy": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/cli/build/libs/broxy-cli-<version>.jar",
        "proxy",
        "--config-dir", "/Users/you/.config/broxy",
        "--preset-id", "developer",
        "--inbound", "stdio",
        "--log-level", "info"
      ]
    }
  }
}
```

## Logging

- Logs are stored in `~/.config/broxy/logs/YYYY-MM-DD.log`.
- STDIO mode writes logs to stderr to keep stdout clean for MCP.

## Testing

- `./gradlew testAll` runs all tests (unit + integration).
- `./gradlew :cli:integrationTest` runs CLI STDIO and Streamable HTTP integration tests.
- `./gradlew :test-mcp-server:selfCheck` runs the test server self-check.

## Build and packaging

Requirements:

- JDK 17 or 21 (Gradle 8.13 Kotlin DSL does not support JDK 25+).
- macOS, Windows, or Linux.

Build from source:

```bash
./gradlew build
./gradlew testAll
```

Build CLI jar:

```bash
./gradlew :cli:shadowJar
```

Build desktop installers:

```bash
./gradlew :ui:packageDistributionForCurrentOS
```

Artifacts are created under `ui/build/compose/binaries/main/<format>/`.

Signing (optional, env-driven):

- macOS: `MACOS_SIGN=true`, `MACOS_IDENTITY=...`, `MACOS_NOTARY_APPLE_ID`, `MACOS_NOTARY_PASSWORD`,
  `MACOS_NOTARY_TEAM_ID`
- Windows: `WIN_SIGN=true`, `WIN_CERT_PATH=...`, `WIN_KEY_PATH=...`, `WIN_CERT_PASSWORD=...`,
  `WIN_TIMESTAMP_URL` (optional)

## Security notes

- Use `env` placeholders for secrets: `${TOKEN}`.
- Values in `headers` are not interpolated; set explicit values.
- Logs are sanitized by key for TOKEN/SECRET/PASSWORD/KEY.

## Useful links

- Model Context Protocol: https://modelcontextprotocol.io
- MCP Kotlin SDK: https://github.com/modelcontextprotocol/kotlin-sdk
- Compose Multiplatform: https://www.jetbrains.com/lp/compose-multiplatform/

## License

MIT - see `LICENSE`.
