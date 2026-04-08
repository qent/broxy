# MCP server list format

- Client: Kiro
- Config path: `~/.kiro/settings/mcp.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

Kiro stores MCP servers as a map `mcpServers.<server-id>`.

### Local STDIO server

```json
{
  "mcpServers": {
    "local-server": {
      "command": "uvx",
      "args": ["mcp-server-fetch"],
      "env": {
        "API_KEY": "${API_KEY}"
      },
      "autoApprove": ["toolA"]
    }
  }
}
```

### Remote server

```json
{
  "mcpServers": {
    "remote-server": {
      "serverUrl": "https://api.example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${API_TOKEN}"
      },
      "disabled": false
    }
  }
}
```

Common fields shown in Kiro docs/examples:

- `command`, `args`, `env` for local servers
- `url` / `serverUrl` and `headers` for remote servers
- operational flags like `disabled` and approval lists (`autoApprove`)

## Broxy entry used by Broxy connector

```json
{
  "mcpServers": {
    "broxy": {
      "url": "http://localhost:{port}/mcp"
    }
  }
}
```

## Broxy import coverage (current)

Broxy imports from each `mcpServers.<id>` entry:

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
- missing `type`: `command` -> Stdio, `url`/`serverUrl` -> Streamable HTTP

Not imported yet:

- `autoApprove` / per-tool allowlists
- other Kiro-specific UI/runtime metadata

## References

- https://kiro.dev/docs/mcp/configuration/
