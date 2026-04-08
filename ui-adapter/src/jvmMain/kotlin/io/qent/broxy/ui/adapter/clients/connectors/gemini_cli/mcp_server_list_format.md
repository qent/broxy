# MCP server list format

- Client: Gemini CLI
- Config path: `~/.gemini/settings.json`
- Format: JSON object with top-level `mcpServers` map

## Expected configuration shape

Gemini CLI supports multiple transports in the same `mcpServers` map.

### Local stdio server

```json
{
  "mcpServers": {
    "local-server": {
      "command": "node",
      "args": ["server.js"],
      "env": {
        "API_KEY": "${API_KEY}"
      }
    }
  }
}
```

### Streamable HTTP server

```json
{
  "mcpServers": {
    "remote-http": {
      "httpUrl": "https://api.example.com/mcp",
      "headers": {
        "Authorization": "Bearer abc123"
      }
    }
  }
}
```

### SSE server

```json
{
  "mcpServers": {
    "remote-sse": {
      "url": "https://api.example.com/sse",
      "headers": {
        "Authorization": "Bearer abc123"
      }
    }
  }
}
```

Documented Gemini CLI MCP config also includes:

- per-server timeout options
- OAuth fields under server config (for remote auth flows)

## Broxy entry used by Broxy connector

```json
{
  "mcpServers": {
    "broxy": {
      "url": "http://localhost:{port}/sse"
    }
  }
}
```

## Broxy import coverage (current)

Broxy imports:

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
- missing `type`: `command` -> Stdio, `url`/`httpUrl` -> Streamable HTTP

Not imported yet:

- OAuth settings
- timeout fields
- other Gemini CLI specific metadata

## References

- https://geminicli.com/docs/tools/mcp-server/
