# Cursor + Claude MCP format (`mcp.json`)

## Purpose

Define Broxy interoperability rules for Cursor/Claude-style `mcp.json` files and canonical save output.

## When to read

- When changing `mcp.json` parser/mapping behavior.
- When changing placeholder interpolation or OAuth field mapping.
- When changing compatibility guarantees with external AI clients.

## Source-of-truth files

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/FileConfigModels.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigMapper.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/TransportMapping.kt`
- `core/src/commonMain/resources/schemas/mcp.schema.json`

## Behavior contract

Broxy accepts both `oauth` and Cursor alias `auth` on load and writes canonical output using `oauth`.

This document defines the MCP servers file format Broxy uses for interoperability with Cursor and Claude Code.

Broxy stores MCP server definitions in `mcp.json` (`mcpServers` map), while app settings are stored separately in
`config.json`.

## Official references

- [Claude Code MCP docs](https://code.claude.com/docs/en/mcp)
- [Claude Code settings and scopes (`.mcp.json`)](https://code.claude.com/docs/en/settings)
- [Cursor MCP docs](https://docs.cursor.com/context/model-context-protocol)
- Optional SDK references for MCP-related types:
  - [Agent SDK TypeScript](https://platform.claude.com/docs/en/agent-sdk/typescript)
  - [Agent SDK Python](https://platform.claude.com/docs/en/agent-sdk/python)

## File shape

```json
{
  "mcpServers": {
    "server-id": {
      "type": "http",
      "url": "https://example.com/mcp"
    }
  }
}
```

- Top-level key: `mcpServers`.
- `mcpServers` is a map of `serverId -> server config`.
- `serverId` becomes part of Broxy tool namespace (`serverId_toolName`).

## Supported server fields (input)

| Field | Type | Notes |
| --- | --- | --- |
| `type` | string | Optional on load. Broxy supports `stdio`, `http`, `sse`, `ws`. |
| `command` | string | Required for `type: "stdio"`. |
| `args` | string[] | Optional process args for `stdio`. |
| `env` | object | Optional env vars map (`string -> string`). |
| `envFile` | string | Optional `.env` file path for `stdio` servers. |
| `url` | string | Required for `type: "http"`, `"sse"`, `"ws"`. |
| `headers` | object | Optional request headers for HTTP/SSE/WS. |
| `oauth` | object | OAuth config for HTTP/SSE/WS servers. |
| `auth` | object | Cursor OAuth alias accepted on load (`auth` -> `oauth`). |
| `name` | string | Broxy extension (Claude ignores unknown fields). |
| `enabled` | boolean | Broxy extension (Claude ignores unknown fields). |
| `iconPath` | string | Broxy extension (Claude ignores unknown fields). |

Type inference when `type` is omitted:

- `command` present -> `stdio`
- `url` present -> `http`

## Interpolation compatibility

Broxy resolves placeholders on load in these string fields:

- `command`
- `args[]`
- `env` values
- `url`
- `headers` values
- OAuth string fields (`oauth.*`)

Supported placeholder syntax:

- `${env:VAR}`
- `${VAR}`
- `${VAR:-default}`
- `{VAR}`
- `${workspaceFolder}` (directory of active `mcp.json`)
- `${workspaceFolderBasename}`
- `${userHome}`
- `${pathSeparator}` / `${/}`
- `${input:NAME}` (lookup `NAME`, then fallback to uppercased `NAME` with `-` -> `_`)

Round-trip rule:

- for unchanged values, Broxy preserves raw placeholders on save (including transport fields).

## `oauth` fields

Broxy accepts Claude-compatible OAuth fields:

- `type` (`"oauth"`)
- `clientId`
- `clientSecret`
- `clientIdMetadataUrl`
- `redirectUri`
- `callbackPort`
- `clientName`
- `tokenEndpointAuthMethod` (`none`, `client_secret_basic`, `client_secret_post`)
- `authorizationServer`
- `authServerMetadataUrl`
- `scopes`
- `allowDynamicRegistration`

OAuth loopback redirect behavior:

- `redirectUri` supports `http://` and `https://` loopback URLs (`localhost` / `127.0.0.1`, explicit port required).
- `callbackPort` remains a convenience for `http://localhost:<callbackPort>/callback` when `redirectUri` is omitted.
- When both `redirectUri` and `callbackPort` are omitted, Broxy defaults to
  `http://localhost:<random-port>/oauth/callback`.
- When `redirectUri` uses `https`, Broxy starts a temporary OAuth callback listener with an auto-generated
  self-signed certificate (no root certificate installation).

## Canonical output (Broxy save)

Broxy writes a canonical `mcp.json` that is readable by Cursor and Claude Code:

- always writes explicit `type`
- writes OAuth only in `oauth`
- does not write `auth`
- does not write `headersHelper`
- writes `envFile` only for `stdio`

## Unknown field behavior

Interoperability rule:

- Broxy ignores unsupported or unknown JSON fields while parsing `mcp.json`.
- Claude Code may ignore Broxy-specific fields (`name`, `enabled`, `iconPath`, etc.).

This allows round-trip exchange between Broxy, Cursor, and Claude Code configs without strict schema coupling.

## Broxy <-> Claude mapping

| Broxy runtime model | File field |
| --- | --- |
| `TransportConfig.StdioTransport` | `type: "stdio"`, `command`, `args` |
| `TransportConfig.StreamableHttpTransport` | `type: "http"`, `url`, `headers` |
| `TransportConfig.HttpTransport` | `type: "sse"`, `url`, `headers` |
| `TransportConfig.WebSocketTransport` | `type: "ws"`, `url`, `headers` |
| `AuthConfig.OAuth` | `oauth` |

Runtime does not support legacy Broxy `transport`-based config shape anymore.

## Example: mixed Cursor/Claude + Broxy fields

```json
{
  "mcpServers": {
    "github": {
      "name": "GitHub MCP",
      "enabled": true,
      "command": "npx",
      "args": ["@modelcontextprotocol/server-github"],
      "envFile": ".env.github",
      "env": {
        "GITHUB_TOKEN": "${GITHUB_TOKEN}"
      },
      "iconPath": "icons/github.png"
    },
    "remote-api": {
      "type": "http",
      "url": "https://api.example.com/mcp",
      "headers": {
        "X-Client": "broxy"
      },
      "oauth": {
        "type": "oauth",
        "clientId": "client-id",
        "authServerMetadataUrl": "https://auth.example.com/.well-known/openid-configuration"
      }
    }
  }
}
```
