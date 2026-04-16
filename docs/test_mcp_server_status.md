# Test MCP server self-check

## Purpose

Specify the test MCP server self-check contract and expected capability/operation verification by transport.

## When to read

- When changing `test-mcp-server` behavior.
- When changing CLI/integration test assumptions about test server capabilities.

## Source-of-truth files

- `test-mcp-server/src/main/kotlin/io/qent/broxy/testserver/SimpleTestMcpServer.kt`
- `test-mcp-server/src/main/kotlin/io/qent/broxy/testserver/SimpleTestMcpServerSelfCheck.kt`
- `test-mcp-server/src/main/kotlin/io/qent/broxy/testserver/TestServerProfiles.kt`

## Behavior contract

Self-check must verify capability discovery and tool/prompt/resource round-trips for each transport mode.

This repository includes a lightweight MCP server used by integration flows. The `selfCheck` task
verifies that the server builds, starts, and responds correctly in all supported modes:
STDIO, Streamable HTTP, HTTP SSE, and WebSocket.

## How to run the self-check

```bash
./gradlew :test-mcp-server:selfCheck --console=plain
```

Optional flags:

- `--skip-http` to skip Streamable HTTP
- `--skip-sse` to skip HTTP SSE
- `--skip-ws` to skip WebSocket

The task installs the test server distribution and launches separate instances for each transport
mode on random free ports. It uses the Broxy MCP clients to verify:

- Capabilities per mode (tool/prompt/resource are distinct for each transport).
- Tool calls return structured results with the expected operation and numeric payload.
- Prompt lookups include the expected greeting text for each mode.
- Resource reads return the expected mode-specific content.

Mode-specific capabilities:

- STDIO: `add_stdio`, `hello_stdio`, `hello_stdio_plain`, `test://resource/stdio`, `test://resource/stdio/{id}`
- Streamable HTTP: `subtract_http`, `hello_http`, `hello_http_plain`, `test://resource/http`, `test://resource/http/{id}`
- HTTP SSE: `multiply_sse`, `hello_sse`, `hello_sse_plain`, `test://resource/sse`, `test://resource/sse/{id}`
- WebSocket: `divide_ws`, `hello_ws`, `hello_ws_plain`, `test://resource/ws`, `test://resource/ws/{id}`

The task exits non-zero if any checks fail or if any of the HTTP/WebSocket servers are unreachable.
Successful output ends with `All SimpleTestMcpServer checks passed`.

## Test server CLI notes

`test-mcp-server` accepts `--capabilities tools,prompts,resources` (or `all`) to expose a subset of
capabilities. Integration tests use this to validate mixed-capability merges.
