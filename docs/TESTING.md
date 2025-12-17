# Unit Testing Guidelines

This project follows strict, lightweight unit testing practices tailored for the core (blocking) logic. Keep tests fast, deterministic, and focused on contract behavior.

What to test first (critical path):
- Core MCP flows: server connection, capabilities, tool routing.
- Proxy filtering and routing: filtering by preset, allowed set enforcement, prompt/resource routing.
- Client adapters (HTTP/SSE, WebSocket, STDIO): capability mapping, tool/prompt/resource calls.
- Inbound HTTP Streamable: request/response over POST (JSON-only mode).
- Caching and backoff: TTL behavior, backoff boundaries (no sleeps).

Style and patterns:
- AAA structure: Arrange data/mocks, Act once, Assert results and essential interactions.
- Constructor injection only. Don’t mutate private internals from tests.
- Mockito-Kotlin for doubles: `val dep: Dep = mock()`, `whenever(dep.suspending()).thenReturn(value)` inside `runBlocking {}`.
- Prefer fakes (e.g., `FakeSdkClientFacade`) for happy paths. Use `mockito` for error branches and verification.
- Don’t use `Thread.sleep`. For time-sensitive code (TTL), use minimal TTL and `delay` in tests or `kotlinx-coroutines-test` when appropriate.

Locations and naming:
- JVM unit tests live under `core/src/jvmTest/kotlin/...`.
- Name tests by behavior, not by method: `filters_and_prefixes_with_mappings`, `connect_and_capabilities_and_callTool_with_mockito`.

Running tests:
- `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew allTests` runs all project tests, including:
  - unit tests (`:core:jvmTest`, `:ui-adapter:jvmTest`)
  - CLI integration tests (`:cli:integrationTest`)
  - test MCP server tests (`:test-mcp-server:test`)

Helpful test utilities already in repo:
- `FakeSdkClientFacade` for client adapters.
- `SdkConnector` to inject a fake façade into clients.

Examples in this repo:
- Default server connection cache/refresh fallback: `DefaultMcpServerConnectionTest`.
- Multi-server routing and parsing: `MultiServerClientTest`.
- Proxy filtering and routing: `DefaultToolFilterTest`, `RequestRouterTest`, `ProxyMcpServerTest`.
- Client adapters: `HttpMcpClientTest`, `StdioMcpClientTest`, `WebSocketMcpClientTest`.
- Utilities: `ExponentialBackoffTest`, `CapabilitiesCacheTest`.

Adding new tests:
- Cover both happy and failure paths for core flows.
- Keep tests independent; no external network or disk I/O (loopback/embedded servers are OK when testing inbound transports).
- Prefer verifying public outcomes; verify interactions only for critical delegation/guard logic.
