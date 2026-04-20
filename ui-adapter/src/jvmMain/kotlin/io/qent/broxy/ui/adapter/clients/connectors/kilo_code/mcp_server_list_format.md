# MCP server list format

- Client: KILO CODE
- Config path: `~/Library/Application Support/Code/User/globalStorage/kilocode.kilo-code/settings/mcp_settings.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

### STDIO (local)

```json
{
  "mcpServers": {
    "local-server": {
      "command": "node",
      "args": ["/path/to/server.js"],
      "env": {
        "API_KEY": "your_api_key"
      },
      "alwaysAllow": ["tool1", "tool2"],
      "disabled": false
    }
  }
}
```

### Streamable HTTP (remote)

```json
{
  "mcpServers": {
    "remote-server": {
      "type": "streamable-http",
      "url": "https://your-server-url.com/mcp",
      "headers": {
        "Authorization": "Bearer your-token"
      },
      "alwaysAllow": ["tool3"],
      "disabled": false
    }
  }
}
```

Notes:

- KILO CODE docs explicitly document `streamable-http` type for remote transport.
- `alwaysAllow` and `disabled` are operational flags in KILO CODE UI flow.

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
- `type` (including `streamable-http` aliases)
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
- other KILO-specific runtime metadata

## References

- https://kilo.ai/docs/automate/mcp/using-in-kilo-code
