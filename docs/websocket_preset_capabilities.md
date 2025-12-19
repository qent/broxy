# WebSocket payloads for preset capabilities

This document describes what broxy sends to the backend over WebSocket in remote mode and how
capabilities for the active preset are represented.

## Transport and envelope

- WebSocket URL: `wss://broxy.run/ws/{serverIdentifier}` with headers `Authorization: Bearer <jwt>` and
  `Sec-WebSocket-Protocol: mcp`. The URL and token are created by `RemoteConnectorImpl`.
- Each text frame is JSON with a multi-session envelope:

```json
// From backend to proxy
{
  "session_identifier": "uuid",
  "message": {
    /* MCP JSON-RPC */
  }
}

// From proxy to backend
{
  "session_identifier": "uuid",
  "target_server_identifier": "server-id",
  "message": {
    /* MCP JSON-RPC */
  }
}
```

`message` is always an MCP JSON-RPC message serialized via `McpJson`.

## Capabilities for the current preset

- After `initialize`, the backend requests `tools/list`, `prompts/list`, and `resources/list`.
- `ProxyMcpServer` produces a filtered view based on the active preset. Tools are already
  prefixed as `serverId:tool`.

Example response:

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "tools": [
      {
        "name": "s1:search",
        "description": "Search",
        "input_schema": {
          "type": "object",
          "properties": {
            "query": {
              "type": "string"
            },
            "top_k": {
              "type": "integer"
            }
          },
          "required": [
            "query"
          ]
        },
        "output_schema": {
          "type": "object",
          "properties": {
            "results": {
              "type": "array"
            }
          }
        },
        "annotations": {
          /* MCP annotations, if present */
        }
      }
    ],
    "prompts": [
      {
        "name": "hello",
        "description": "Greet user",
        "arguments": []
      }
    ],
    "resources": [
      {
        "name": "doc",
        "uri": "docs/{id}",
        "description": "Doc page",
        "mime_type": "text/html"
      }
    ]
  }
}
```

Notes:

- `input_schema` and `output_schema` are passed through from downstream; the proxy does not trim them.
- `resources.uri` may contain `{param}` placeholders; prompt arguments and tool input schemas convey required fields.

## Tool, prompt, and resource calls

Tool call:

```json
{
  "jsonrpc": "2.0",
  "id": "42",
  "method": "tools/call",
  "params": {
    "name": "s1:search",
    "arguments": {
      "query": "kotlin",
      "top_k": 5
    },
    "meta": {
      /* RequestMeta */
    }
  }
}
```

Tool response:

```json
{
  "jsonrpc": "2.0",
  "id": "42",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Result preview"
      }
    ],
    "structured_content": {
      "results": [
        {
          "title": "..."
        }
      ]
    },
    "meta": {
      "trace_id": "..."
    },
    "is_error": false
  }
}
```

Prompts and resources use the same params fields:

- `prompts/get` -> `name` + `arguments`
- `resources/read` -> `uri`

Responses are forwarded without structural changes (except for decode normalization).

## Logging and verification

- `ProxyWebSocketTransport.describeJsonRpcPayload` logs a summary for each JSON-RPC message:
  counts, names, schema fields, target tool, argument keys, and response structure.
- Tests in `ui-adapter/src/jvmTest/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransportTest.kt` verify:
    - presence of schema fields and entity counts in `tools/list` responses;
    - reflection of request argument keys and response structure for `tools/call`.

These logs confirm that the backend receives a complete snapshot of the active preset capabilities.
