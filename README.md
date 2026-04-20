[![CI](https://github.com/qent/broxy/actions/workflows/ci.yml/badge.svg)](https://github.com/qent/broxy/actions/workflows/ci.yml)

<div align="center">
  <h1>Broxy — your single MCP endpoint</h1>
  <p>
    Broxy fronts multiple upstream MCP servers as one, aggregates capabilities across protocols,
    and exposes only what presets allow. It runs locally, can be securely accessed over the internet,
    and supports OAuth plus dynamic client registration via upstream integrations where available.
  </p>
  <p>
    <a href="https://broxy.run">broxy.run</a> · <a href="#features">Features</a> · <a href="#connections">Connections</a> · <a href="#cli-jar">CLI jar</a>
  </p>
</div>

## Why Broxy

<ul style="list-style: none; padding-left: 0;">
  <li>💰 Save tokens and money by exposing only the essential tools, prompts, and resources per task.</li>
  <li>🎯 Build more efficient agents with focused toolsets and fewer distractions.</li>
  <li>🔌 Connect ChatGPT and other HTTP-only MCP clients to local STDIO MCP servers.</li>
  <li>⚡ Instantly context-switch by hot-swapping presets.</li>
  <li>🔐 Sign in with Google to access the dashboard and remote connectivity features.</li>
  <li>📄 Apache 2.0 licensed for personal or commercial use.</li>
</ul>

## Features

<table role="presentation">
  <tr>
    <td>
      <img src="https://broxy.run/static/resources/features/mcp_list.png" alt="Set up your MCP servers once, use it with any client" />
      <h3>Set up your MCP servers once</h3>
      Centralize MCP servers without duplicating per-client settings. Connect local or remote servers over
      STDIO, HTTP, SSE, or WebSocket, and add custom icons to keep large lists readable.
      <br />
      &nbsp;
    </td>
    <td>
      <img src="https://broxy.run/static/resources/features/preset_creation.png" alt="Mix capabilities from servers into one preset" />
      <h3>Mix capabilities from MCP servers</h3>
      Build purpose-built presets by mixing tools, prompts, and resources from any connected server.
      Each preset exposes only what an agent needs and reduces context noise.
      <br />
      &nbsp;
    </td>
  </tr>
  <tr>
    <td>
      <img src="https://broxy.run/static/resources/features/chatgpt_to_local_mcp.png" alt="Connect ChatGPT to local MCP servers" />
      <h3>Connect ChatGPT to local MCP servers</h3>
      Use local MCP servers inside ChatGPT on web, desktop, or mobile. Broxy Cloud bridges the
      connection securely so private tools are available anywhere you sign in.
      <br />
      &nbsp;
    </td>
    <td>
      <img src="https://broxy.run/static/resources/features/popular_clients.png" alt="Connect to popular clients in one click" />
      <h3>Connect to popular clients in one click</h3>
      Auto-configure popular AI clients across HTTP, SSE, and STDIO modes. Broxy updates only the MCP
      section so the rest of your client configuration stays untouched.
      <br />
      &nbsp;
    </td>
  </tr>
  <tr>
    <td>
      <img src="https://broxy.run/static/resources/features/dynamic_client_with_oauth.png" alt="Dynamic client registration with OAuth" />
      <h3>Dynamic client registration with OAuth</h3>
      Authorize servers that require personal data without manual client setup. Broxy discovers OAuth
      metadata, registers clients dynamically, and works even when the target client lacks OAuth support.
      <br />
      &nbsp;
    </td>
    <td>
      <img src="https://broxy.run/static/resources/features/adapter_mode.png" alt="Stable MCP surface for preset changes" />
      <h3>Stable MCP surface for preset changes</h3>
      Expose a stable MCP surface so clients do not need to refresh capabilities when presets change.
      Adapter mode also surfaces prompts and resources for clients that only support tools, including ChatGPT.
      <br />
      &nbsp;
    </td>
  </tr>
</table>

## Connections

**STDIO (local connection)**

Use STDIO when your client expects a local MCP process.

```json
{
    "mcpServers": {
        "broxy": {
            "command": "/Applications/broxy.app/Contents/MacOS/broxy",
            "args": [
                "--stdio-proxy"
            ]
        }
    }
}
```

**Streamable HTTP**

Broxy exposes Streamable HTTP on `http://localhost:3335/mcp` by default.

```json
{
    "mcpServers": {
        "broxy": {
            "url": "http://localhost:3335/mcp"
        }
    }
}
```

**SSE inbound**

SSE is served on the same host and port at `/sse`.

- `GET http://localhost:3335/sse` opens the SSE stream and returns the session endpoint.
- `POST http://localhost:3335/sse?sessionId=...` forwards MCP JSON-RPC messages into the session.

## Developer Documentation

For contributors and AI code agents:

- Start with `docs/readme.md` (2-level docs index).
- Use `docs/agent_quickstart.md` for task-to-doc mapping, invariants, and required checks.
- Canonical runtime contracts are in:
  - `docs/core_contracts.md`
  - `docs/proxy_facade.md`
  - `docs/inbound_transports.md`
  - `docs/presets_and_filtering.md`
  - `docs/configuration_and_hot_reload.md`

## CLI jar

**Build the jar**

```bash
./gradlew :cli:shadowJar
```

The jar is generated at `cli/build/libs/broxy-cli.jar`.

**Run STDIO**

```bash
java -jar cli/build/libs/broxy-cli.jar proxy \
  --preset-id <preset-id> \
  --config-dir ~/.config/broxy \
  --inbound stdio
```

**Run Streamable HTTP + SSE**

```bash
java -jar cli/build/libs/broxy-cli.jar proxy \
  --preset-id <preset-id> \
  --config-dir ~/.config/broxy \
  --inbound http \
  --url http://localhost:3335/mcp
```

The CLI expects `mcp.json` and `preset_<id>.json` in the config directory (default `~/.config/broxy`).
