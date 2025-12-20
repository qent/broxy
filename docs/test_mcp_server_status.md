# Test MCP server self-check

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

- STDIO: `add_stdio`, `hello_stdio`, `test://resource/stdio`
- Streamable HTTP: `subtract_http`, `hello_http`, `test://resource/http`
- HTTP SSE: `multiply_sse`, `hello_sse`, `test://resource/sse`
- WebSocket: `divide_ws`, `hello_ws`, `test://resource/ws`

The task exits non-zero if any checks fail or if any of the HTTP/WebSocket servers are unreachable.
Successful output ends with `All SimpleTestMcpServer checks passed`.
