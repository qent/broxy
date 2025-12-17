# Test MCP Server Status

This repository contains a lightweight MCP server used by the integration flows. The `selfCheck` task validates that the server builds, starts, and responds correctly in both STDIO and HTTP Streamable modes without hanging.

## How to run the self-check

```bash
./gradlew :test-mcp-server:selfCheck --console=plain
```

The task installs the test server distribution, launches it in STDIO mode, and then starts a separate HTTP Streamable instance on a random free port. It uses the Broxy MCP clients to verify that:

- Capabilities include the `add` and `subtract` tools, the `test://resource/alpha` and `test://resource/beta` resources, and the `hello` and `bye` prompts.
- Tool calls return structured results (addition and subtraction with numeric payloads) without errors.
- Prompt lookups include the expected greeting and farewell text.
- Resource reads return the expected alpha and beta content.

The task exits non-zero if any of the above checks fail or if the HTTP Streamable server cannot be reached. Successful output ends with `All SimpleTestMcpServer checks passed`.
