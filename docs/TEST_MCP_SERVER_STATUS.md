# Test MCP server self-check

This repository includes a lightweight MCP server used by integration flows. The `selfCheck` task
verifies that the server builds, starts, and responds correctly in both STDIO and Streamable HTTP
modes.

## How to run the self-check

```bash
./gradlew :test-mcp-server:selfCheck --console=plain
```

The task installs the test server distribution, launches it in STDIO mode, and then starts a
separate Streamable HTTP instance on a random free port. It uses the Broxy MCP clients to verify:

- Capabilities include `add` and `subtract` tools, `test://resource/alpha` and
  `test://resource/beta` resources, and `hello` / `bye` prompts.
- Tool calls return structured results (addition and subtraction with numeric payloads).
- Prompt lookups include the expected greeting and farewell text.
- Resource reads return the expected alpha and beta content.

The task exits non-zero if any checks fail or if the Streamable HTTP server is unreachable.
Successful output ends with `All SimpleTestMcpServer checks passed`.
