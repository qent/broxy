# MCP server list format

- Client: Roo Code
- Config path: `~/Library/Application Support/Code/User/globalStorage/rooveterinaryinc.roo-cline/settings/mcp_settings.json`
- Format: JSON object with `mcpServers` map
- Related project-level format in Roo docs: `.roo/mcp.json`

## Expected configuration shape

Roo Code keeps a server map `mcpServers.<server-id>`.

### Local server

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me"],
      "env": {
        "API_KEY": "${API_KEY}"
      },
      "alwaysAllow": ["read_file"],
      "disabled": false
    }
  }
}
```

### Remote server

```json
{
  "mcpServers": {
    "remote-api": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer your-token"
      },
      "alwaysAllow": [],
      "disabled": false
    }
  }
}
```

Common fields shown in Roo docs/examples:

- `command`, `args`, `env` for local servers
- `url` and `headers` for remote servers
- operational fields like `alwaysAllow` and `disabled`

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

- `alwaysAllow` / tool allowlists
- Roo-specific operational metadata (timeouts, UI state)

## References

- https://docs.roocode.com/features/mcp/using-mcp-in-roo
