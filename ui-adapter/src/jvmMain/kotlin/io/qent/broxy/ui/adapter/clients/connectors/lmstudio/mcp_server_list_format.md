# MCP server list format

- Client: LM Studio
- Config path: `~/.lmstudio/mcp.json`
- Format: JSON object with `mcpServers` map

## Expected configuration shape

LM Studio MCP setup uses the standard `mcpServers` map (`serverId -> server config`).

### Local STDIO server

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me"],
      "env": {
        "DEBUG": "true"
      }
    }
  }
}
```

### Remote server

```json
{
  "mcpServers": {
    "hf-mcp-server": {
      "url": "https://huggingface.co/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_HF_TOKEN>"
      }
    }
  }
}
```

Common fields used in docs/examples:

- `command`, `args`, `env` for local servers
- `url` and `headers` for remote servers

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

- client-specific metadata not represented in Broxy server model

## References

- https://lmstudio.ai/docs/app/mcp
