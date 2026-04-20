# MCP server list format

- Client: Google Antigravity
- Config path: `~/.gemini/antigravity/mcp_config.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

Antigravity uses the same MCP JSON map style as other IDE clients:

```json
{
  "mcpServers": {
    "firebase": {
      "command": "npx",
      "args": ["-y", "firebase-tools@latest", "mcp"]
    }
  }
}
```

In practice, server entries can include the usual MCP JSON fields:

- local: `command`, `args`, `env`
- remote: `url`/`serverUrl`/`httpUrl`, `headers`, and optional `type`

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
- missing `type`: `command` -> Stdio, `url` -> Streamable HTTP

Not imported yet:

- client-specific flags/metadata outside the core MCP transport fields
- auth/oauth objects when present in custom extensions

## References

- https://firebase.google.com/docs/ai-assistance/mcp-server
- https://docs.cloud.google.com/alloydb/docs/connect-ide-using-mcp-toolbox
