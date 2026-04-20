# MCP server list format

- Client: Cursor
- Config path: `~/.cursor/mcp.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

Cursor MCP configs are `serverId -> server object` under `mcpServers`.

### Local stdio server example

```json
{
  "mcpServers": {
    "local-server": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
      "env": {
        "TOKEN": "${TOKEN}"
      }
    }
  }
}
```

### Remote server example

```json
{
  "mcpServers": {
    "remote-server": {
      "type": "http",
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${API_KEY}"
      }
    }
  }
}
```

Common fields seen across Cursor-compatible MCP configs:

- `type`
- `command`, `args`, `env`
- `url`, `headers`
- `envFile` (in some compatible configs)

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

Broxy imports:

- `name`
- `enabled` and `disabled` (inverted into `enabled`)
- `type`
- `command`
- `args`
- `url` (plus aliases `serverUrl`, `httpUrl`)
- `headers`
- `env`

Transport mapping on import:

- `stdio` -> Stdio
- `http` / `streamable-http` / `streamable_http` / `streamablehttp` -> Streamable HTTP
- `sse` -> SSE
- `ws` / `websocket` -> WebSocket
- missing `type`: `command` -> Stdio, `url` -> Streamable HTTP

Not imported yet:

- auth/oauth blocks
- `envFile`
- client-only custom metadata

## References

- https://docs.cursor.com/context/model-context-protocol
- https://developers.openai.com/learn/docs-mcp
- https://firebase.google.com/docs/ai-assistance/mcp-server
