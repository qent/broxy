# MCP server list format

- Client: Windsurf
- Config path: `~/.codeium/windsurf/mcp_config.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

Windsurf stores servers in `mcpServers.<server-id>` and documents local + remote transports.

### Local STDIO server

```json
{
  "mcpServers": {
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory"],
      "env": {
        "NODE_ENV": "production"
      }
    }
  }
}
```

### Remote server

```json
{
  "mcpServers": {
    "remote-server": {
      "serverUrl": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${API_TOKEN}"
      }
    }
  }
}
```

Common fields in Windsurf docs/examples:

- `command`, `args`, `env` for local servers
- `serverUrl`/`url` and `headers` for remote servers
- docs mention transport coverage for stdio, HTTP and SSE usage patterns

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

- client-specific team/admin policy metadata
- other Windsurf-specific runtime settings not part of transport fields

## References

- https://docs.windsurf.com/windsurf/cascade/mcp
