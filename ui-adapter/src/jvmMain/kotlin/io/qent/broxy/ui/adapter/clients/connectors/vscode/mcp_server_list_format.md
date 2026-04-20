# MCP server list format

- Client: Visual Studio Code
- Config path: `~/Library/Application Support/Code/User/mcp.json`
- Alternative project path in docs: `.vscode/mcp.json`
- Format: JSON object with `servers` map (not `mcpServers`)

## Expected configuration shape

VS Code MCP config uses `servers.<server-id>`.

### Remote server

```json
{
  "servers": {
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp"
    }
  }
}
```

### Local STDIO server

```json
{
  "servers": {
    "playwright": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@microsoft/mcp-server-playwright"],
      "env": {
        "DEBUG": "1"
      }
    }
  }
}
```

Common fields from VS Code docs/examples:

- `type`
- `command`, `args`, `env` for local servers
- `url` for remote servers
- optional sandbox-related metadata for local servers (`sandboxEnabled`, `sandbox`)

## Broxy entry used by Broxy connector

```json
{
  "servers": {
    "broxy": {
      "url": "http://localhost:{port}/mcp"
    }
  }
}
```

## Broxy import coverage (current)

Broxy imports from each `servers.<id>` entry:

- `name`
- `enabled` and `disabled` (inverted into `enabled`)
- `type`
- `command`
- `args`
- `url` and aliases (`serverUrl`, `httpUrl`)
- `headers`
- `env`

Transport mapping on import:

- `stdio` -> Stdio
- `http` / `streamable-http` / `streamable_http` / `streamablehttp` -> Streamable HTTP
- `sse` -> SSE
- `ws` / `websocket` -> WebSocket
- missing `type`: `command` -> Stdio, `url` -> Streamable HTTP

Not imported yet:

- VS Code sandbox metadata (`sandboxEnabled`, `sandbox`)
- other VS Code MCP UI/runtime state (stored outside `mcp.json`)

## References

- https://code.visualstudio.com/docs/copilot/customization/mcp-servers
