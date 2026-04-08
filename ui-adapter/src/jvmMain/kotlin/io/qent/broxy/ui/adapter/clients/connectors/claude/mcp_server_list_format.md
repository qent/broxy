# MCP server list format

- Client: Claude Desktop
- Config path: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

Top-level object:

```json
{
  "mcpServers": {
    "<server-id>": {
      "command": "...",
      "args": ["..."],
      "env": {
        "KEY": "VALUE"
      }
    }
  }
}
```

Notes:

- In Claude Desktop docs, this file is primarily used for local `stdio` servers.
- `mcpServers` is a map of `serverId -> server config`.
- Typical local server fields: `command` (required), `args` (optional), `env` (optional).

## Broxy entry used by Broxy connector

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

## Broxy import coverage (current)

When importing from this file, Broxy reads these server fields when present:

- `name`
- `enabled` and `disabled` (inverted into `enabled`)
- `type`
- `command`
- `args`
- `url` (plus aliases `serverUrl`, `httpUrl`)
- `headers`
- `env`

Transport mapping on import:

- `type: "stdio"` or `command` without `type` -> Stdio
- `type: "http" | "streamable-http" | "streamable_http" | "streamablehttp"` -> Streamable HTTP
- `type: "sse"` -> SSE
- `type: "ws" | "websocket"` -> WebSocket
- `url` without `type` -> Streamable HTTP

Not imported into Broxy server model yet:

- client-specific policy fields (for example allow/auto-approve lists)
- OAuth/auth blocks
- unknown custom fields

## References

- https://modelcontextprotocol.io/docs/develop/connect-local-servers
- https://code.claude.com/docs/en/mcp
