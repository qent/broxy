# MCP server list format

- Client: Codex
- Config path: `~/.codex/config.toml`
- Format: TOML tables under `[mcp_servers.<serverName>]`

## Expected configuration shape

### STDIO servers

```toml
[mcp_servers.context7]
command = "npx"
args = ["-y", "@upstash/context7-mcp"]

[mcp_servers.context7.env]
MY_ENV_VAR = "MY_ENV_VALUE"
```

Common stdio fields in Codex docs:

- `command` (required for stdio)
- `args` (optional)
- `env` table (optional)
- `env_vars` (optional allow-forward list)

### Streamable HTTP servers

```toml
[mcp_servers.figma]
url = "https://mcp.figma.com/mcp"
bearer_token_env_var = "FIGMA_OAUTH_TOKEN"
http_headers = { "X-Figma-Region" = "us-east-1" }
```

Common remote fields in Codex docs:

- `url` (required for remote)
- `bearer_token_env_var` (optional)
- `http_headers` (optional static headers)
- `env_http_headers` (optional header values sourced from env vars)

### Other documented per-server options

- `startup_timeout_sec`
- `tool_timeout_sec`

## Broxy entry used by Broxy connector

```toml
[mcp_servers.broxy]
url = "http://localhost:{port}/mcp"
```

## Broxy import coverage (current)

Broxy currently imports from `config.toml`:

- top-level server table name as `sourceServerId`
- `name`
- `enabled`
- `type`
- `command`
- `args`
- `url`
- `env` from both inline form and `[mcp_servers.<id>.env]`
- `headers` from `headers`, `http_headers`, and `env_http_headers`

Not imported yet:

- `env_vars`
- `bearer_token_env_var`
- timeout fields (`startup_timeout_sec`, `tool_timeout_sec`)
- OAuth callback globals and other Codex-global MCP settings

## References

- https://developers.openai.com/codex/mcp
- https://developers.openai.com/codex/config-basic
