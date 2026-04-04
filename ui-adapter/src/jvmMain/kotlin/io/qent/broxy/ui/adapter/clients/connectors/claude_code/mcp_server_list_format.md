# MCP server list format

- Client: Claude Code
- Config path used by connector: `~/.claude.json`
- Related project-scoped file in Claude docs: `.mcp.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

```json
{
  "mcpServers": {
    "<server-id>": {
      "type": "http",
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${API_KEY}"
      }
    }
  }
}
```

Common fields used by Claude Code MCP config:

- `type` (transport, e.g. `stdio`, `http`, `sse`, `ws`)
- `command`, `args`, `env` for local stdio servers
- `url`, `headers` for remote servers
- `oauth` block for OAuth flows

Notes:

- Claude Code supports user/local/project scopes; project configs are typically stored in `.mcp.json`.
- Environment variable interpolation is documented for `command`, `args`, `env`, `url`, and `headers`.

## Broxy entry used by Broxy connector

```json
{
  "mcpServers": {
    "broxy": {
      "type": "http",
      "url": "http://localhost:{port}/mcp"
    }
  }
}
```

## Broxy import coverage (current)

Broxy imports these fields from each `mcpServers.<id>` entry:

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

- `oauth` / `auth` objects
- `envFile`
- other client-specific metadata fields

## References

- https://code.claude.com/docs/en/mcp
- https://code.claude.com/docs/en/settings
